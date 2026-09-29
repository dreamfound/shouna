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

    private fun seedLocations(): List<LocationEntity> = listOf(
        LocationEntity(
            id = BuiltInData.UNSPECIFIED_LOCATION_ID,
            name = "未指定位置",
            parentId = null,
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
