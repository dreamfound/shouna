package com.dream.shouna.data.repository

import com.dream.shouna.data.local.FakeCategoryDao
import com.dream.shouna.data.local.FakeItemDao
import com.dream.shouna.data.local.FakeLocationDao
import com.dream.shouna.data.local.FakeRecentSearchDao
import com.dream.shouna.data.local.dao.RecentSearchDao
import com.dream.shouna.data.local.entity.LocationEntity
import com.dream.shouna.data.local.entity.RecentSearchEntity
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.util.PinyinUtil
import com.dream.shouna.util.TextNormalizer
import com.dream.shouna.util.TimeUtil
import com.dream.shouna.util.UuidIdGenerator
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * P0-01 / P0-02 / P0-04 单测（纯 JVM）：
 * 落库字段口径、位置必填的写入路径、`touchLastUsed`、时间戳矩阵、拼音列、映射往返、连续录入不丢条目、
 * 检索文档的三表派生（拼音 / 路径 / 确认时间）、最近搜索词的写入与上限。
 *
 * 构造依赖：`FakeItemDao` / `FakeLocationDao` / `FakeCategoryDao` / `FakeRecentSearchDao`
 *           （内存替身，见 `data/local/FakeDaos.kt`）
 *           + `UuidIdGenerator` / `TimeUtil` / `PinyinUtil`（真实实现，均为无状态纯工具）。
 *
 * **不在本类覆盖**：SQL 正确性、外键 RESTRICT、种子完整性、schema 导出 —— 那些走 androidTest。
 */
class ItemRepositoryImplTest {

    private val itemDao = FakeItemDao()
    private val locationDao = FakeLocationDao(initial = listOf(bedroom()))
    private val categoryDao = FakeCategoryDao()
    private val recentSearchDao = FakeRecentSearchDao()
    private val timeUtil = TimeUtil()
    private val pinyinUtil = PinyinUtil()
    private val repository = newRepository()

    /** 换一条 `recent_search` 替身（需要预置数据的用例）时重建仓库，其余依赖复用同一批替身。 */
    private fun newRepository(recentDao: FakeRecentSearchDao = recentSearchDao): ItemRepositoryImpl =
        ItemRepositoryImpl(
            itemDao = itemDao,
            locationDao = locationDao,
            categoryRepository = CategoryRepositoryImpl(categoryDao, UuidIdGenerator()),
            recentSearchDao = recentDao,
            idGenerator = UuidIdGenerator(),
            timeUtil = timeUtil,
            pinyinUtil = pinyinUtil,
        )

    @Test
    fun createItemQuick_writesGivenLocationAndInStorageStatus() = runTest {
        val uncategorized = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )
        val categorized = repository.createItemQuick(
            name = "电风扇",
            categoryId = "builtin-category-appliance",
            note = "卧室那台",
            locationId = BEDROOM_ID,
        )

        listOf(uncategorized, categorized).forEach { item ->
            // 位置必填（ARCHITECTURE-P0 §0）：落库的就是调用方给的真实位置，不再是哨兵。
            assertThat(item.locationId).isEqualTo(BEDROOM_ID)
            assertThat(item.id.isNotBlank()).isTrue()
            assertThat(item.status).isEqualTo(ItemStatus.IN_STORAGE)
            assertThat(item.normalizedName).isEqualTo(TextNormalizer.normalize(item.name))
            // 仍置空的字段：别名与数量属后续版本。
            assertThat(item.aliasBlob).isEmpty()
            assertThat(item.quantity).isEqualTo(1)
        }
        // 同名物品按 id 区分。
        assertThat(uncategorized.id).isNotEqualTo(categorized.id)
        assertThat(uncategorized.name).isEqualTo(categorized.name)
    }

    @Test
    fun createItemQuick_refreshesLastUsedAtOfThatLocation() = runTest {
        assertThat(locationDao.findById(BEDROOM_ID)?.lastUsedAt).isNull()

        repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )

        // FR-11：这次录入即「使用」了该位置 → 下次录入据此预选。
        assertThat(locationDao.findById(BEDROOM_ID)?.lastUsedAt).isNotNull()
    }

    @Test
    fun createItemQuick_persistsPinyinColumns() = runTest {
        repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )

        val row = itemDao.snapshot().single()
        // FR-20：全拼与首字母在**写入时**派生并持久化（A-2 允许），不依赖查询期计算。
        assertThat(row.pinyinFull).isEqualTo("dianfengshan")
        assertThat(row.pinyinInitial).isEqualTo("dfs")
    }

    @Test
    fun createItemQuick_writesAllThreeTimestampsFromOneNow() = runTest {
        val before = timeUtil.nowMillis()
        val item = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = "卧室",
            locationId = BEDROOM_ID,
        )
        val after = timeUtil.nowMillis()

        assertThat(item.createdAt).isEqualTo(item.lastModifiedAt)
        assertThat(item.createdAt).isAtLeast(before)
        assertThat(item.createdAt).isAtMost(after)
        // 2026-09-30 口径：录入即一次确认 → 三个时间列同值（原为 lastConfirmedAt 保持 null）。
        assertThat(item.lastConfirmedAt).isEqualTo(item.createdAt)
    }

    @Test
    fun createItemQuick_isNotOverdueRightAfterCreation() = runTest {
        repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )

        // 反证「刚存进去就带 ⚠ 并进 C-4 清单」这个症状：新物品不在超期清单里。
        assertThat(repository.observeOverdueItems(thresholdMonths = 6).first()).isEmpty()
    }

    @Test
    fun confirmItem_touchesLastConfirmedAtOnly() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )
        assertThat(created.lastConfirmedAt).isEqualTo(created.createdAt)

        val confirmed = requireNotNull(repository.confirmItem(created.id))

        assertThat(confirmed.lastConfirmedAt).isNotNull()
        // 创建时已写入确认时间 → 本次「还在」必须把它推进（同一毫秒内允许相等）。
        assertThat(confirmed.lastConfirmedAt!!).isAtLeast(created.lastConfirmedAt!!)
        // 时间戳矩阵：只动 lastConfirmedAt。
        assertThat(confirmed.lastModifiedAt).isEqualTo(created.lastModifiedAt)
        assertThat(confirmed.createdAt).isEqualTo(created.createdAt)
        assertThat(confirmed.name).isEqualTo(created.name)
        assertThat(confirmed.normalizedName).isEqualTo(created.normalizedName)
        assertThat(itemDao.snapshot()).hasSize(1)
        assertThat(itemDao.findById(created.id)?.lastConfirmedAt).isEqualTo(confirmed.lastConfirmedAt)
    }

    @Test
    fun confirmItem_returnsNullWhenNotFound() = runTest {
        // 未命中：受影响行数为 0 → 返回 null（不抛异常）。
        assertThat(repository.confirmItem("no-such-id")).isNull()
    }

    // ---- FR-25 状态切换（P0-03） ---------------------------------------------------

    @Test
    fun setStatus_marksGoneAndTouchesLastModifiedAtOnly() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )
        repository.confirmItem(created.id)
        val confirmed = requireNotNull(itemDao.findById(created.id))

        val gone = requireNotNull(repository.setStatus(created.id, ItemStatus.GONE))

        assertThat(gone.status).isEqualTo(ItemStatus.GONE)
        // 「不在了」**不是**一次确认 → lastConfirmedAt 不动（§3.5 矩阵）。
        assertThat(gone.lastConfirmedAt).isEqualTo(confirmed.lastConfirmedAt)
        // 而是归属/状态的变动 → lastModifiedAt 刷新。
        assertThat(gone.lastModifiedAt).isAtLeast(confirmed.lastModifiedAt)
    }

    @Test
    fun restore_returnsItemToInStorage() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )
        repository.setStatus(created.id, ItemStatus.GONE)

        val restored = requireNotNull(repository.restore(created.id))

        assertThat(restored.status).isEqualTo(ItemStatus.IN_STORAGE)
        assertThat(restored.status.isActive).isTrue()
    }

    @Test
    fun setStatus_returnsNullWhenNotFound() = runTest {
        assertThat(repository.setStatus("no-such-id", ItemStatus.GONE)).isNull()
        assertThat(repository.restore("no-such-id")).isNull()
    }

    @Test
    fun goneItem_disappearsFromSummariesAndReappearsAfterRestore() = runTest {
        val item = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )

        repository.setStatus(item.id, ItemStatus.GONE)
        assertThat(repository.observeItemSummaries().first()).isEmpty()

        repository.restore(item.id)
        assertThat(repository.observeItemSummaries().first().map { it.id }).containsExactly(item.id)
    }

    @Test
    fun observeItemSummaries_hidesInactiveItems() = runTest {
        val alive = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )
        val removed = repository.createItemQuick(
            name = "旧台灯",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )
        itemDao.updateStatus(removed.id, ItemStatus.GONE, timeUtil.nowMillis())

        val summaries = repository.observeItemSummaries().first()

        assertThat(summaries.map { it.id }).containsExactly(alive.id)
    }

    @Test
    fun createItemQuick_thirtyConsecutiveTimes_keepsEveryItem() = runTest {
        repeat(30) { index ->
            repository.createItemQuick(
                name = "物品 $index",
                categoryId = if (index % 2 == 0) "builtin-category-other" else null,
                note = null,
                locationId = BEDROOM_ID,
            )
        }

        val all = itemDao.snapshot()
        assertThat(all).hasSize(30)
        assertThat(all.map { it.id }.toSet()).hasSize(30)
        assertThat(all.map { it.name }.toSet()).hasSize(30)
        assertThat(all.count { it.categoryId != null }).isEqualTo(15)
        // 30 次都落在同一位置（连续录入沿用同一位置的行为基础）。
        assertThat(all.all { it.locationId == BEDROOM_ID }).isTrue()
    }

    @Test
    fun entityRoundTrip_keepsEveryField() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = "builtin-category-appliance",
            note = "卧室",
            locationId = BEDROOM_ID,
        )
        val row = itemDao.snapshot().single()

        assertThat(row.toDomain()).isEqualTo(created)
    }

    @Test
    fun filterActive_keepsOnlyActiveStatuses() {
        val items = listOf(
            storedItem(id = "alive", status = ItemStatus.IN_STORAGE),
            storedItem(id = "pending", status = ItemStatus.TO_BE_PUT_BACK),
            storedItem(id = "removed", status = ItemStatus.GONE),
        )

        // 「待归位」仍是 active（继续参与常规检索），只有「已不在」被过滤。
        assertThat(filterActive(items).map { it.id }).containsExactly("alive", "pending").inOrder()
    }

    // ---- P0-04 检索文档派生（拼音 / 路径 / 确认时间） ------------------------------

    @Test
    fun observeSearchDocs_carriesPinyinPathAndConfirmTime() = runTest {
        locationDao.insert(box())
        val item = repository.createItemQuick(
            name = "电风扇",
            categoryId = "builtin-category-appliance",
            note = null,
            locationId = BOX_ID,
        )
        repository.confirmItem(item.id)

        val doc = repository.observeSearchDocs().first().single()

        // 拼音读**持久化列**（写入时派生），不是查询期重算。
        assertThat(doc.pinyinFull).isEqualTo("dianfengshan")
        assertThat(doc.pinyinInitial).isEqualTo("dfs")
        // 位置路径与路径拼音均为构建时实时派生（A-2：不冗余进物品记录）。
        assertThat(doc.locationPath).isEqualTo("卧室 › 纸箱-07")
        assertThat(doc.normalizedLocationPath).isEqualTo("卧室 › 纸箱-07")
        assertThat(doc.pinyinLocationPath).isEqualTo("woshi › zhixiang-07")
        // FR-27：结果行要的「最后确认」时间随文档带出。
        assertThat(doc.lastConfirmedAt).isEqualTo(itemDao.findById(item.id)?.lastConfirmedAt)
        assertThat(doc.lastConfirmedAt).isNotNull()
    }

    @Test
    fun observeSearchDocs_reflectsLocationRenameWithoutTouchingItem() = runTest {
        locationDao.insert(box())
        repository.createItemQuick(name = "电风扇", categoryId = null, note = null, locationId = BOX_ID)
        assertThat(repository.observeSearchDocs().first().single().locationPath).isEqualTo("卧室 › 纸箱-07")

        locationDao.rename(BOX_ID, "纸箱-08")

        val renamed = repository.observeSearchDocs().first().single()
        // FR-03：改名后路径（及其拼音）**即时跟随**。
        assertThat(renamed.locationPath).isEqualTo("卧室 › 纸箱-08")
        assertThat(renamed.pinyinLocationPath).isEqualTo("woshi › zhixiang-08")
        // 而物品记录一字未动（A-2：改名的代价与物品数脱钩）。
        assertThat(itemDao.snapshot().single().name).isEqualTo("电风扇")
        assertThat(itemDao.snapshot().single().pinyinFull).isEqualTo("dianfengshan")
    }

    @Test
    fun observeSearchDocs_hidesGoneItems() = runTest {
        val item = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )

        repository.setStatus(item.id, ItemStatus.GONE)

        // `gone` 不参与常规检索（§3.4 不变量 5）。
        assertThat(repository.observeSearchDocs().first()).isEmpty()
    }

    @Test
    fun observeSearchDocs_filtersInactiveStatusesBeforeIndexing() = runTest {
        val alive = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )
        val removed = repository.createItemQuick(
            name = "旧台灯",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )
        repository.setStatus(removed.id, ItemStatus.GONE)

        assertThat(repository.observeSearchDocs().first().map { it.itemId }).containsExactly(alive.id)
    }

    // ---- P0-04 最近搜索词（FR-23） ------------------------------------------------

    @Test
    fun recordRecentQuery_dedupesByNormalizedQuery() = runTest {
        repository.recordRecentQuery("风扇")
        // 归一化后同词（含首尾空白差异）→ 只留一条（UNIQUE(normalized_query) 的 REPLACE 语义）。
        repository.recordRecentQuery("  风扇  ")

        assertThat(recentSearchDao.snapshot()).hasSize(1)
        // 存的是**用户原样输入**（仅去首尾空白），供 chips 原样回显。
        assertThat(repository.observeRecentQueries().first()).containsExactly("风扇")
    }

    @Test
    fun recordRecentQuery_ignoresBlankQuery() = runTest {
        repository.recordRecentQuery("   ")
        repository.recordRecentQuery("")

        assertThat(recentSearchDao.snapshot()).isEmpty()
    }

    @Test
    fun recordRecentQuery_trimsToLimit() = runTest {
        repeat(RecentSearchDao.RECENT_SEARCH_LIMIT + 5) { index ->
            repository.recordRecentQuery("词-$index")
        }

        assertThat(recentSearchDao.snapshot()).hasSize(RecentSearchDao.RECENT_SEARCH_LIMIT)
        assertThat(repository.observeRecentQueries().first()).hasSize(RecentSearchDao.RECENT_SEARCH_LIMIT)
    }

    @Test
    fun observeRecentQueries_returnsNewestFirst() = runTest {
        val repository = newRepository(
            recentDao = FakeRecentSearchDao(
                initial = listOf(
                    recentQuery("旧词", at = 100L),
                    recentQuery("新词", at = 300L),
                    recentQuery("中词", at = 200L),
                ),
            ),
        )

        assertThat(repository.observeRecentQueries().first())
            .containsExactly("新词", "中词", "旧词").inOrder()
    }

    // ---- P1-04（别名 / 数量 / 补丁式写入）------------------------------------------

    @Test
    fun updateFields_writesOnlyGivenFieldsAndKeepsEveryTimestamp() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = "备用",
            locationId = BEDROOM_ID,
        )
        val before = itemDao.findById(created.id)!!

        val updated = repository.updateFields(
            itemId = created.id,
            patch = ItemFieldPatch(
                categoryId = "builtin-category-appliance",
                aliases = listOf("台扇"),
                quantity = 3,
            ),
        )

        assertThat(updated?.categoryId).isEqualTo("builtin-category-appliance")
        assertThat(updated?.quantity).isEqualTo(3)
        assertThat(updated?.aliases).containsExactly("台扇")
        // 未给出的字段原样（补丁语义：没给 = 不动）。
        assertThat(updated?.name).isEqualTo("电风扇")
        assertThat(updated?.note).isEqualTo("备用")
        assertThat(updated?.locationId).isEqualTo(BEDROOM_ID)
        assertThat(updated?.status).isEqualTo(ItemStatus.IN_STORAGE)
        // P1 §3.4-10 / §3.5：改分类 / 别名 / 数量**不刷新**任何时间戳。
        val after = itemDao.findById(created.id)!!
        assertThat(after.createdAt).isEqualTo(before.createdAt)
        assertThat(after.lastModifiedAt).isEqualTo(before.lastModifiedAt)
        assertThat(after.lastConfirmedAt).isEqualTo(before.lastConfirmedAt)
    }

    @Test
    fun updateFields_clearCategoryFallsBackToUncategorized() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = "builtin-category-appliance",
            note = null,
            locationId = BEDROOM_ID,
        )

        // `categoryId = null` 已是「不改」的含义 → 置空必须走 `clearCategory`。
        val cleared = repository.updateFields(created.id, ItemFieldPatch(clearCategory = true))

        assertThat(cleared?.categoryId).isNull()
    }

    @Test
    fun updateFields_blankNoteClearsIt() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = "备用",
            locationId = BEDROOM_ID,
        )

        assertThat(repository.updateFields(created.id, ItemFieldPatch(note = "   "))?.note).isNull()
        assertThat(itemDao.findById(created.id)?.note).isNull()
    }

    @Test
    fun updateFields_rejectsNonPositiveQuantityAndKeepsStoredValue() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )

        assertThat(repository.updateFields(created.id, ItemFieldPatch(quantity = 0))).isNull()
        assertThat(itemDao.findById(created.id)?.quantity).isEqualTo(1)
    }

    @Test
    fun updateFields_emptyPatchIsNoOpAndStillReturnsCurrentRow() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )

        val unchanged = repository.updateFields(created.id, ItemFieldPatch())

        assertThat(unchanged).isEqualTo(created)
        assertThat(repository.updateFields("no-such-id", ItemFieldPatch(quantity = 2))).isNull()
    }

    @Test
    fun updateFields_aliasChangeRecomputesPinyinToCoverBothNameAndAlias() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )
        assertThat(itemDao.findById(created.id)?.pinyinFull).isEqualTo("dianfengshan")

        repository.updateFields(created.id, ItemFieldPatch(aliases = listOf("台扇")))

        // 别名与名称同档参与检索 → 拼音键是**两者合并**，不是替换。
        val withAlias = itemDao.findById(created.id)!!.pinyinFull
        assertThat(withAlias).contains("dianfengshan")
        assertThat(withAlias).contains("taishan")

        // 删掉别名 → 拼音键回落为名称单键。
        repository.updateFields(created.id, ItemFieldPatch(aliases = emptyList()))
        assertThat(itemDao.findById(created.id)?.pinyinFull).isEqualTo("dianfengshan")
    }

    @Test
    fun observeSearchDocs_carriesAliasesAndLocationId() = runTest {
        locationDao.insert(box())
        val item = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BOX_ID,
        )
        repository.updateFields(item.id, ItemFieldPatch(aliases = listOf("台扇", "风扇")))

        val doc = repository.observeSearchDocs().first().single()

        assertThat(doc.aliases).containsExactly("台扇", "风扇").inOrder()
        assertThat(doc.normalizedAliases).containsExactly("台扇", "风扇").inOrder()
        // P1-06（FR-22）：位置筛选靠 id 落到位置树的前缀关系，不靠会随改名而变的名称路径。
        assertThat(doc.locationId).isEqualTo(BOX_ID)
    }

    // ---- P1-02 ⑥（FR-28 按位置批量确认）-------------------------------------------

    @Test
    fun confirmByLocation_confirmsWholeSubtreeSkipsGoneAndLeavesOtherBranches() = runTest {
        locationDao.insert(box())
        locationDao.insert(livingRoom())
        wireLocationPaths()
        val inBedroom = repository.createItemQuick("电风扇", null, null, BEDROOM_ID)
        val inBox = repository.createItemQuick("说明书", null, null, BOX_ID)
        val elsewhere = repository.createItemQuick("台灯", null, null, LIVING_ROOM_ID)
        val goneInBox = repository.createItemQuick("旧电池", null, null, BOX_ID)
        repository.setStatus(goneInBox.id, ItemStatus.GONE)
        val modifiedBefore = itemDao.findById(inBox.id)!!.lastModifiedAt
        // 录入即已确认（本次口径变更）→ 先存四条的现值，再验证批量确认只推进子树内的两条活跃物品。
        // 若只断言 `isNotNull()` 会变成恒真的空断言，故这里一律与「确认前」的值比对。
        val confirmedBefore = itemDao.snapshot().associate { it.id to it.lastConfirmedAt }

        val affected = repository.confirmByLocation(BEDROOM_ID)

        // 含子层：卧室 + 纸箱-07 各 1 件活跃物品（`gone` 不计）。
        assertThat(affected).isEqualTo(2)
        assertThat(itemDao.findById(inBedroom.id)?.lastConfirmedAt)
            .isAtLeast(confirmedBefore.getValue(inBedroom.id)!!)
        assertThat(itemDao.findById(inBox.id)?.lastConfirmedAt)
            .isAtLeast(confirmedBefore.getValue(inBox.id)!!)
        // 子树之外与 `gone` 都不受影响。
        assertThat(itemDao.findById(elsewhere.id)?.lastConfirmedAt)
            .isEqualTo(confirmedBefore.getValue(elsewhere.id))
        assertThat(itemDao.findById(goneInBox.id)?.lastConfirmedAt)
            .isEqualTo(confirmedBefore.getValue(goneInBox.id))
        // 批量确认只写 `last_confirmed_at`（P1 §3.5 矩阵末行）。
        assertThat(itemDao.findById(inBox.id)?.lastModifiedAt).isEqualTo(modifiedBefore)
    }

    @Test
    fun confirmByLocation_returnsZeroWhenPathIsBlank() = runTest {
        // 脏数据（`path` 未回填）下**必须为 0**：空前缀的区间查询会命中全表。
        locationDao.insert(livingRoom().copy(id = "dirty", parentId = null, path = ""))
        repository.createItemQuick("台灯", null, null, "dirty")

        assertThat(repository.confirmByLocation("dirty")).isEqualTo(0)
        assertThat(repository.confirmByLocation("no-such-id")).isEqualTo(0)
    }

    // ---- P1-06 ②（FR-14 重名查重）-------------------------------------------------

    @Test
    fun findSimilar_matchesExactNormalizedName() = runTest {
        val created = repository.createItemQuick("电风扇", null, null, BEDROOM_ID)

        assertThat(repository.findSimilar("  电风扇 ").map { it.id }).containsExactly(created.id)
    }

    @Test
    fun findSimilar_matchesSubstringWithinLengthDeltaOnly() = runTest {
        val created = repository.createItemQuick("电风扇", null, null, BEDROOM_ID)
        // 互为子串且长度差 1 ≤ 2 → 高度相似。
        assertThat(repository.findSimilar("风扇").map { it.id }).containsExactly(created.id)

        repository.createItemQuick("电风扇三档调速款", null, null, BEDROOM_ID)
        // 长度为 3 的查询对 8 字条目：虽互为子串，长度差 5 > 2 → 不算相似（噪声全靠这条挡）。
        assertThat(repository.findSimilar("电风扇").map { it.id }).containsExactly(created.id)
    }

    @Test
    fun findSimilar_ignoresGoneItemsAndBlankInput() = runTest {
        val created = repository.createItemQuick("电风扇", null, null, BEDROOM_ID)
        repository.setStatus(created.id, ItemStatus.GONE)

        assertThat(repository.findSimilar("电风扇")).isEmpty()
        assertThat(repository.findSimilar("   ")).isEmpty()
    }

    // ---- P1-03 ③ / P1-04 ⑥（归位到… / 移动到…）-------------------------------------

    @Test
    fun putBack_movesToTargetAndLeavesPendingState_refreshingLastModifiedOnly() = runTest {
        locationDao.insert(livingRoom())
        val item = repository.createItemQuick("电风扇", null, null, BEDROOM_ID)
        repository.setStatus(item.id, ItemStatus.TO_BE_PUT_BACK)
        val confirmedBefore = itemDao.findById(item.id)!!.lastConfirmedAt

        val putBack = repository.putBack(item.id, LIVING_ROOM_ID)

        // FR-38：一次操作同时完成「换位置」与「离开待归位」。
        assertThat(putBack?.locationId).isEqualTo(LIVING_ROOM_ID)
        assertThat(putBack?.status).isEqualTo(ItemStatus.IN_STORAGE)
        // 状态变化属「变动」→ 刷新 last_modified_at（P1 §3.5 矩阵）；归位**不是**一次确认。
        assertThat(itemDao.findById(item.id)!!.lastModifiedAt).isAtLeast(confirmedBefore ?: 0L)
        assertThat(itemDao.findById(item.id)?.lastConfirmedAt).isEqualTo(confirmedBefore)
    }

    @Test
    fun putBack_returnsNullWhenItemMissing() = runTest {
        assertThat(repository.putBack("no-such-id", LIVING_ROOM_ID)).isNull()
    }

    @Test
    fun moveItem_changesLocationOnly_andDoesNotCountAsConfirmation() = runTest {
        locationDao.insert(livingRoom())
        val item = repository.createItemQuick("电风扇", null, null, BEDROOM_ID)
        val modifiedBefore = item.lastModifiedAt
        val confirmedBefore = itemDao.findById(item.id)!!.lastConfirmedAt

        val moved = repository.moveItem(item.id, LIVING_ROOM_ID)

        assertThat(moved?.locationId).isEqualTo(LIVING_ROOM_ID)
        // 移动不改 status、不写确认时间；位置变化本身属「变动」→ 刷新 last_modified_at。
        assertThat(moved?.status).isEqualTo(ItemStatus.IN_STORAGE)
        assertThat(itemDao.findById(item.id)?.lastConfirmedAt).isEqualTo(confirmedBefore)
        assertThat(itemDao.findById(item.id)!!.lastModifiedAt).isAtLeast(modifiedBefore)
    }

    @Test
    fun moveItem_returnsNullWhenItemMissing() = runTest {
        assertThat(repository.moveItem("no-such-id", LIVING_ROOM_ID)).isNull()
    }

    private fun recentQuery(query: String, at: Long): RecentSearchEntity = RecentSearchEntity(
        query = query,
        normalizedQuery = TextNormalizer.normalize(query),
        searchedAt = at,
    )

    private fun storedItem(id: String, status: ItemStatus): StoredItem = StoredItem(
        id = id,
        name = "物品-$id",
        normalizedName = "物品-$id",
        locationId = BEDROOM_ID,
        status = status,
        createdAt = 0L,
        lastModifiedAt = 0L,
    )

    private fun bedroom(): LocationEntity = LocationEntity(
        id = BEDROOM_ID,
        name = "卧室",
        parentId = null,
        // P1-01：夹具同步补 `path`（ID 序列、含自身、前后带 `/`），与建库种子同形。
        path = "/$BEDROOM_ID/",
        isBuiltIn = false,
        isTemporary = false,
        note = null,
        sortOrder = 1,
        lastUsedAt = null,
        createdAt = 0L,
    )

    /** 卧室下的子位置（用于验证多级路径拼装：`卧室 › 纸箱-07`）。 */
    private fun box(): LocationEntity = LocationEntity(
        id = BOX_ID,
        name = "纸箱-07",
        parentId = BEDROOM_ID,
        path = "/$BEDROOM_ID/$BOX_ID/",
        isBuiltIn = false,
        isTemporary = false,
        note = null,
        sortOrder = 1,
        lastUsedAt = null,
        createdAt = 0L,
    )

    /** 根级位置「客厅」：用于验证**子树之外**的分支不被批量确认波及。 */
    private fun livingRoom(): LocationEntity = LocationEntity(
        id = LIVING_ROOM_ID,
        name = "客厅",
        parentId = null,
        path = "/$LIVING_ROOM_ID/",
        isBuiltIn = false,
        isTemporary = false,
        note = null,
        sortOrder = 2,
        lastUsedAt = null,
        createdAt = 0L,
    )

    /**
     * 把位置 id → ID 序列路径灌进 `FakeItemDao`。
     *
     * 假的 `item` 表没有 `location` 表可 join，而按位置批量确认靠的正是位置路径的区间匹配
     * —— 不喂这张映射，`confirmActiveInSubtree` 就无从判定范围。
     */
    private fun wireLocationPaths() {
        itemDao.locationPaths = locationDao.snapshot().associate { it.id to it.path }
    }

    private companion object {
        const val BEDROOM_ID = "location-bedroom"
        const val BOX_ID = "location-box-07"
        const val LIVING_ROOM_ID = "location-living-room"
    }
}
