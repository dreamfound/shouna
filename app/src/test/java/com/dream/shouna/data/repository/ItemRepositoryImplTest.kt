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
            categoryRepository = CategoryRepositoryImpl(categoryDao),
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
    fun createItemQuick_writesCreatedAtAndLastModifiedAt_only() = runTest {
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
        assertThat(item.lastConfirmedAt).isNull()
    }

    @Test
    fun confirmItem_touchesLastConfirmedAtOnly() = runTest {
        val created = repository.createItemQuick(
            name = "电风扇",
            categoryId = null,
            note = null,
            locationId = BEDROOM_ID,
        )
        assertThat(created.lastConfirmedAt).isNull()

        val confirmed = requireNotNull(repository.confirmItem(created.id))

        assertThat(confirmed.lastConfirmedAt).isNotNull()
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

    private companion object {
        const val BEDROOM_ID = "location-bedroom"
        const val BOX_ID = "location-box-07"
    }
}
