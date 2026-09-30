package com.dream.shouna.data.repository

import com.dream.shouna.data.local.FakeItemDao
import com.dream.shouna.data.local.FakeLocationDao
import com.dream.shouna.data.local.TransactionRunner
import com.dream.shouna.data.local.entity.ItemEntity
import com.dream.shouna.data.local.entity.LocationEntity
import com.dream.shouna.data.memory.BuiltInData
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.util.TimeUtil
import com.dream.shouna.util.UuidIdGenerator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P0-02 单测（纯 JVM）：位置树派生、增删改、**删除两档**、最近使用。
 *
 * 事务边界用直通实现（`ImmediateTransactionRunner`）注入 —— 见
 * [com.dream.shouna.data.local.TransactionRunner] 的说明：仓库层的**逻辑**在 JVM 覆盖，
 * 「事务是否真的原子」由 Room 自身保证（androidTest 覆盖 SQL 与约束）。
 *
 * 种子（等价于 `SeedCallback` 的结果）：哨兵 + 家 › 储物间 › 纸箱-07。
 */
class LocationRepositoryImplTest {

    private val locationDao = FakeLocationDao(initial = seedLocations())
    private val itemDao = FakeItemDao()
    private val timeUtil = TimeUtil()
    private val repository = LocationRepositoryImpl(
        transactionRunner = ImmediateTransactionRunner,
        locationDao = locationDao,
        itemDao = itemDao,
        idGenerator = UuidIdGenerator(),
        timeUtil = timeUtil,
    )

    // ---- 树派生 ------------------------------------------------------------------

    @Test
    fun observeTree_flattensDepthFirstOrderAndHidesBuiltInSentinel() = runTest {
        val rows = repository.observeTree().first()

        // 深度优先：父在前、其子紧随。
        assertThat(rows.map { it.location.id })
            .containsExactly("home", "storage", "box").inOrder()
        assertThat(rows.map { it.depth }).containsExactly(0, 1, 2).inOrder()
        // 位置必填口径：内置哨兵不出现在树里（用户选不到它）。
        assertThat(rows.map { it.location.id }).doesNotContain(BuiltInData.UNSPECIFIED_LOCATION_ID)
    }

    @Test
    fun observeTree_reportsDirectItemCountAndChildFlag() = runTest {
        insertItem(id = "item-1", name = "电风扇", locationId = "box")
        insertItem(id = "item-2", name = "说明书", locationId = "storage")

        val rows = repository.observeTree().first()
        val byId = rows.associateBy { it.location.id }

        assertThat(byId.getValue("box").itemCount).isEqualTo(1)
        assertThat(byId.getValue("storage").itemCount).isEqualTo(1)
        assertThat(byId.getValue("home").itemCount).isEqualTo(0)
        assertThat(byId.getValue("home").hasChildren).isTrue()
        assertThat(byId.getValue("box").hasChildren).isFalse()
    }

    @Test
    fun observeTree_buildsBreadcrumbForEveryRow() = runTest {
        val rows = repository.observeTree().first()
        val byId = rows.associateBy { it.location.id }

        assertThat(byId.getValue("box").pathText).isEqualTo("家 › 储物间 › 纸箱-07")
    }

    // ---- 增改 --------------------------------------------------------------------

    @Test
    fun create_appendsAtEndOfSiblings() = runTest {
        val created = repository.create(name = " 抽屉 ", parentId = "storage")

        assertThat(created.name).isEqualTo("抽屉")
        assertThat(created.parentId).isEqualTo("storage")
        assertThat(created.isBuiltIn).isFalse()
        // 同级已有 box（sortOrder = 1）→ 新节点排到 2。
        assertThat(created.sortOrder).isEqualTo(2)
    }

    @Test
    fun create_canBuildAtRootLevel() = runTest {
        val created = repository.create(name = "车库", parentId = null)

        assertThat(created.parentId).isNull()
        assertThat(locationDao.findById(created.id)?.parentId).isNull()
    }

    @Test
    fun rename_updatesNameAndRejectsBlank() = runTest {
        assertThat(repository.rename("storage", "储藏室")).isTrue()
        assertThat(locationDao.findById("storage")?.name).isEqualTo("储藏室")

        // 空白名不入库（避免出现无法点选的空名字节点）。
        assertThat(repository.rename("storage", "   ")).isFalse()
        assertThat(locationDao.findById("storage")?.name).isEqualTo("储藏室")
    }

    // ---- 删除两档 -----------------------------------------------------------------

    @Test
    fun delete_archiveMode_keepsItemsAsGoneAndSheltersThem() = runTest {
        val inBox = insertItem(id = "item-box", name = "电风扇", locationId = "box")
        val inStorage = insertItem(id = "item-storage", name = "说明书", locationId = "storage")
        val outside = insertItem(id = "item-home", name = "水杯", locationId = "home")

        val deleted = repository.delete(id = "storage", mode = LocationDeleteMode.ARCHIVE)

        assertThat(deleted).isTrue()
        // 子树的位置全删（含自身）。
        assertThat(locationDao.findById("storage")).isNull()
        assertThat(locationDao.findById("box")).isNull()
        // 物品**不删**：标记 gone 并收容到哨兵（location_id 非空，必须给去处）。
        listOf(inBox, inStorage).forEach { item ->
            val row = itemDao.findById(item.id)
            assertThat(row?.status).isEqualTo(ItemStatus.GONE)
            assertThat(row?.locationId).isEqualTo(BuiltInData.UNSPECIFIED_LOCATION_ID)
        }
        // 子树之外的物品完全不受影响。
        assertThat(itemDao.findById(outside.id)?.status).isEqualTo(ItemStatus.IN_STORAGE)
        assertThat(itemDao.findById(outside.id)?.locationId).isEqualTo("home")
    }

    @Test
    fun delete_migrateMode_movesItemsAndLeavesNoOrphan() = runTest {
        val inBox = insertItem(id = "item-box", name = "电风扇", locationId = "box")
        val inStorage = insertItem(id = "item-storage", name = "说明书", locationId = "storage")

        val deleted = repository.delete(
            id = "storage",
            mode = LocationDeleteMode.MIGRATE,
            migrateTargetId = "home",
        )

        assertThat(deleted).isTrue()
        assertThat(locationDao.findById("storage")).isNull()
        assertThat(locationDao.findById("box")).isNull()
        // 档 1：只搬家，不改状态。
        listOf(inBox, inStorage).forEach { item ->
            val row = itemDao.findById(item.id)
            assertThat(row?.locationId).isEqualTo("home")
            assertThat(row?.status).isEqualTo(ItemStatus.IN_STORAGE)
        }
        // 孤儿防线：所有物品的 location_id 都能在位置表里找到（哨兵也是合法行）。
        val knownIds = locationDao.snapshot().map { it.id }.toSet()
        assertThat(itemDao.snapshot().all { it.locationId in knownIds }).isTrue()
    }

    @Test
    fun delete_migrateIntoOwnSubtree_isRejected() = runTest {
        insertItem(id = "item-box", name = "电风扇", locationId = "box")

        val deleted = repository.delete(
            id = "storage",
            mode = LocationDeleteMode.MIGRATE,
            migrateTargetId = "box",
        )

        assertThat(deleted).isFalse()
        // 全部原样：位置还在、物品还在原处。
        assertThat(locationDao.findById("storage")).isNotNull()
        assertThat(locationDao.findById("box")).isNotNull()
        assertThat(itemDao.findById("item-box")?.locationId).isEqualTo("box")
    }

    @Test
    fun delete_migrateWithoutTarget_isRejected() = runTest {
        assertThat(repository.delete(id = "storage", mode = LocationDeleteMode.MIGRATE, migrateTargetId = null))
            .isFalse()
        assertThat(locationDao.findById("storage")).isNotNull()
    }

    @Test
    fun delete_builtInSentinel_isRejected() = runTest {
        val deleted = repository.delete(
            id = BuiltInData.UNSPECIFIED_LOCATION_ID,
            mode = LocationDeleteMode.ARCHIVE,
        )

        assertThat(deleted).isFalse()
        assertThat(locationDao.findById(BuiltInData.UNSPECIFIED_LOCATION_ID)).isNotNull()
    }

    @Test
    fun delete_unknownId_returnsFalse() = runTest {
        assertThat(repository.delete(id = "no-such-id", mode = LocationDeleteMode.ARCHIVE)).isFalse()
    }

    // ---- 最近使用 -----------------------------------------------------------------

    @Test
    fun recentUsed_ordersByLastUsedAndSkipsSentinelAndUnused() = runTest {
        locationDao.touchLastUsed("box", at = 100L)
        locationDao.touchLastUsed("home", at = 300L)
        locationDao.touchLastUsed(BuiltInData.UNSPECIFIED_LOCATION_ID, at = 999L)
        locationDao.touchLastUsed("storage", at = 200L)

        val recent = repository.recentUsed(limit = 5)

        assertThat(recent.map { it.id }).containsExactly("home", "storage", "box").inOrder()
    }

    @Test
    fun touchLastUsed_stampsNow() = runTest {
        repository.touchLastUsed("box")

        val stamped = locationDao.findById("box")?.lastUsedAt
        assertThat(stamped).isNotNull()
        assertThat(stamped!!).isAtMost(timeUtil.nowMillis())
    }

    // ---- P1-02（FR-04 移动 / 合并）--------------------------------------------------

    @Test
    fun move_rewritesWholeSubtreePathAndLeavesNoOrphan() = runTest {
        locationDao.insert(bag())
        insertItem(id = "item-bag", name = "票据", locationId = "bag")

        val moved = repository.move(nodeId = "box", newParentId = "home")

        assertThat(moved).isTrue()
        assertThat(locationDao.findById("box")?.parentId).isEqualTo("home")
        assertThat(locationDao.findById("box")?.path).isEqualTo("/home/box/")
        // 子孙路径随子树整体改前缀（`path` 与 `parent_id` 恒一致，§3.4-7）。
        assertThat(locationDao.findById("bag")?.path).isEqualTo("/home/box/bag/")
        assertThat(locationDao.findById("bag")?.parentId).isEqualTo("box")
        // 物品只认 `location_id`，位置移动不动物品归属。
        assertThat(itemDao.findById("item-bag")?.locationId).isEqualTo("bag")
        // 面包屑是派生值 → 移动后即时跟随。
        val byId = repository.observeTree().first().associateBy { it.location.id }
        assertThat(byId.getValue("bag").pathText).isEqualTo("家 › 纸箱-07 › 袋")
    }

    @Test
    fun move_toRootLevelAndToSameParentAreAllowed() = runTest {
        assertThat(repository.move(nodeId = "box", newParentId = null)).isTrue()
        assertThat(locationDao.findById("box")?.parentId).isNull()
        assertThat(locationDao.findById("box")?.path).isEqualTo("/box/")

        // 父级未变 = 无事发生，但仍算成功（不是失败）。
        assertThat(repository.move(nodeId = "box", newParentId = null)).isTrue()
        assertThat(locationDao.findById("box")?.path).isEqualTo("/box/")
    }

    @Test
    fun move_intoOwnSubtreeOrBuiltInTargetIsRejected() = runTest {
        // §3.4-8：自身与自身子孙都不可作目标。
        assertThat(repository.move(nodeId = "storage", newParentId = "storage")).isFalse()
        assertThat(repository.move(nodeId = "storage", newParentId = "box")).isFalse()
        // 内置哨兵双方都不参与。
        assertThat(repository.move(nodeId = BuiltInData.UNSPECIFIED_LOCATION_ID, newParentId = "home")).isFalse()
        assertThat(repository.move(nodeId = "box", newParentId = BuiltInData.UNSPECIFIED_LOCATION_ID)).isFalse()
        // 不存在的一方。
        assertThat(repository.move(nodeId = "no-such-id", newParentId = "home")).isFalse()
        assertThat(repository.move(nodeId = "box", newParentId = "no-such-id")).isFalse()

        // 拒绝时数据零变动。
        assertThat(locationDao.findById("storage")?.parentId).isEqualTo("home")
        assertThat(locationDao.findById("box")?.path).isEqualTo("/home/storage/box/")
    }

    @Test
    fun merge_rehangsChildrenAndItemsThenRemovesSource() = runTest {
        insertItem(id = "item-storage", name = "说明书", locationId = "storage")
        insertItem(id = "item-box", name = "电风扇", locationId = "box")

        val merged = repository.merge(sourceId = "storage", targetId = "home")

        assertThat(merged).isTrue()
        // source 消失，子位置与直属物品改挂 target。
        assertThat(locationDao.findById("storage")).isNull()
        assertThat(locationDao.findById("box")?.parentId).isEqualTo("home")
        assertThat(locationDao.findById("box")?.path).isEqualTo("/home/box/")
        assertThat(itemDao.findById("item-storage")?.locationId).isEqualTo("home")
        // 状态不动（合并是位置变动，不是状态变化）。
        assertThat(itemDao.findById("item-storage")?.status).isEqualTo(ItemStatus.IN_STORAGE)
        // 深层物品跟着它的位置走，不需要单独搬。
        assertThat(itemDao.findById("item-box")?.locationId).isEqualTo("box")
        // 无孤儿：每个物品的 `location_id` 都指向仍存在的位置。
        val aliveIds = locationDao.snapshot().map { it.id }.toSet()
        assertThat(itemDao.snapshot().all { it.locationId in aliveIds }).isTrue()
    }

    @Test
    fun merge_intoOwnSubtreeOrItselfIsRejected() = runTest {
        assertThat(repository.merge(sourceId = "storage", targetId = "box")).isFalse()
        assertThat(repository.merge(sourceId = "storage", targetId = "storage")).isFalse()
        assertThat(repository.merge(sourceId = "box", targetId = BuiltInData.UNSPECIFIED_LOCATION_ID)).isFalse()
        assertThat(repository.merge(sourceId = "no-such-id", targetId = "home")).isFalse()
        assertThat(repository.merge(sourceId = "storage", targetId = "no-such-id")).isFalse()

        assertThat(locationDao.findById("storage")).isNotNull()
        assertThat(locationDao.findById("box")?.parentId).isEqualTo("storage")
    }

    // ---- P1-02（FR-06 临时标记 / FR-21 递归计数）-------------------------------------

    @Test
    fun setTemporary_flipsFlagWithoutTouchingItemTimestamps() = runTest {
        val item = insertItem(id = "item-box", name = "电风扇", locationId = "box")

        assertThat(repository.setTemporary(nodeId = "box", flag = true)).isTrue()
        assertThat(locationDao.findById("box")?.isTemporary).isTrue()
        // 位置侧操作：物品时间戳一律不动（P1 §3.5）。
        assertThat(itemDao.findById(item.id)?.lastModifiedAt).isEqualTo(item.lastModifiedAt)
        assertThat(itemDao.findById(item.id)?.lastConfirmedAt).isNull()

        assertThat(repository.setTemporary(nodeId = "box", flag = false)).isTrue()
        assertThat(locationDao.findById("box")?.isTemporary).isFalse()
    }

    @Test
    fun setTemporary_rejectsBuiltInAndUnknown() = runTest {
        assertThat(repository.setTemporary(BuiltInData.UNSPECIFIED_LOCATION_ID, true)).isFalse()
        assertThat(repository.setTemporary("no-such-id", true)).isFalse()
    }

    @Test
    fun observeCounts_separatesDirectFromSubtreeAndExcludesGone() = runTest {
        insertItem(id = "item-storage", name = "说明书", locationId = "storage")
        insertItem(id = "item-box", name = "电风扇", locationId = "box")
        val gone = insertItem(id = "item-gone", name = "旧电池", locationId = "box")
        itemDao.updateStatus(gone.id, ItemStatus.GONE, 1L)

        val counts = repository.observeCounts("storage").first()

        assertThat(counts.nodeId).isEqualTo("storage")
        assertThat(counts.directCount).isEqualTo(1)
        // 含子层：storage(1) + box(1)；`gone` 不计（P1 §8.1-7）。
        assertThat(counts.subtreeCount).isEqualTo(2)
    }

    @Test
    fun observeTree_reportsSubtreeCountOnEveryRow() = runTest {
        insertItem(id = "item-storage", name = "说明书", locationId = "storage")
        insertItem(id = "item-box", name = "电风扇", locationId = "box")

        val byId = repository.observeTree().first().associateBy { it.location.id }

        assertThat(byId.getValue("home").subtreeItemCount).isEqualTo(2)
        assertThat(byId.getValue("storage").subtreeItemCount).isEqualTo(2)
        assertThat(byId.getValue("box").subtreeItemCount).isEqualTo(1)
        // 本层直属数与含子层数分开给：树行要同时说清两件事。
        assertThat(byId.getValue("home").itemCount).isEqualTo(0)
        assertThat(byId.getValue("storage").itemCount).isEqualTo(1)
    }

    // ---- 测试脚手架 ---------------------------------------------------------------

    private suspend fun insertItem(id: String, name: String, locationId: String): ItemEntity {
        val item = ItemEntity(
            id = id,
            name = name,
            normalizedName = name,
            pinyinFull = "",
            pinyinInitial = "",
            aliasBlob = "",
            locationId = locationId,
            categoryId = null,
            status = ItemStatus.IN_STORAGE,
            quantity = 1,
            note = null,
            createdAt = 0L,
            lastModifiedAt = 0L,
            lastConfirmedAt = null,
        )
        itemDao.insert(item)
        return item
    }

    /** 纸箱下的第三层：验证移动时**多层子孙**的路径整体改写。 */
    private fun bag(): LocationEntity = LocationEntity(
        id = "bag",
        name = "袋",
        parentId = "box",
        path = "/home/storage/box/bag/",
        isBuiltIn = false,
        isTemporary = false,
        note = null,
        sortOrder = 1,
        lastUsedAt = null,
        createdAt = 0L,
    )

    private fun seedLocations(): List<LocationEntity> = listOf(
        LocationEntity(
            id = BuiltInData.UNSPECIFIED_LOCATION_ID,
            name = "未指定位置",
            parentId = null,
            path = "/${BuiltInData.UNSPECIFIED_LOCATION_ID}/",
            isBuiltIn = true,
            isTemporary = false,
            note = null,
            sortOrder = 0,
            lastUsedAt = null,
            createdAt = 0L,
        ),
        LocationEntity(
            id = "home",
            name = "家",
            parentId = null,
            path = "/home/",
            isBuiltIn = false,
            isTemporary = false,
            note = null,
            sortOrder = 1,
            lastUsedAt = null,
            createdAt = 0L,
        ),
        LocationEntity(
            id = "storage",
            name = "储物间",
            parentId = "home",
            path = "/home/storage/",
            isBuiltIn = false,
            isTemporary = false,
            note = null,
            sortOrder = 1,
            lastUsedAt = null,
            createdAt = 0L,
        ),
        LocationEntity(
            id = "box",
            name = "纸箱-07",
            parentId = "storage",
            path = "/home/storage/box/",
            isBuiltIn = false,
            isTemporary = false,
            note = null,
            sortOrder = 1,
            lastUsedAt = null,
            createdAt = 0L,
        ),
    )
}

/** 直通事务：测试里不需要原子性保证，只需执行。 */
private object ImmediateTransactionRunner : TransactionRunner {
    override suspend fun <T> run(block: suspend () -> T): T = block()
}
