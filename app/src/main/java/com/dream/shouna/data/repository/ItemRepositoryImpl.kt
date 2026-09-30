package com.dream.shouna.data.repository

import com.dream.shouna.data.local.dao.ItemDao
import com.dream.shouna.data.local.dao.LocationDao
import com.dream.shouna.data.local.dao.RecentSearchDao
import com.dream.shouna.data.local.entity.ItemEntity
import com.dream.shouna.data.local.entity.LocationEntity
import com.dream.shouna.data.local.entity.RecentSearchEntity
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.ItemDetail
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.domain.search.SearchDoc
import com.dream.shouna.util.IdGenerator
import com.dream.shouna.util.LocationPath
import com.dream.shouna.util.PinyinUtil
import com.dream.shouna.util.TextNormalizer
import com.dream.shouna.util.TimeUtil
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * P0-01：内存源 → Room（`ItemDao`）。**接口形态、派生字段与失效过滤口径全部沿用 F1**，
 * 只有数据源与「写路径走事务」两处变化（ARCHITECTURE-P0 §2）。
 *
 * 拼音列（`pinyin_full` / `pinyin_initial`）在**写入时**由 [PinyinUtil] 派生并持久化——
 * 守 PRD 08 §7.6 的 A-2 约束：改名与移动的代价与物品数量脱钩（换个名字只需重算这一条）。
 *
 * 分类名与位置路径（`SearchDoc.categoryName` / `locationPath`、`ItemDetail.locationPath`）
 * 仍按 A-2 **不冗余写入物品记录**，一律在构建检索／详情数据时经 `category` / `location` 表实时拼入。
 *
 * 被调用方：QuickAddViewModel（[createItemQuick]）、HomeViewModel（[observeItemSummaries]）、
 *           SearchViewModel（[observeSearchDocs] / [observeRecentQueries] / [recordRecentQuery]）、
 *           ItemDetailViewModel（[getItemDetail] / [confirmItem] / [setStatus] / [restore]）
 */
@Singleton
class ItemRepositoryImpl @Inject constructor(
    private val itemDao: ItemDao,
    private val locationDao: LocationDao,
    private val categoryRepository: CategoryRepository,
    private val recentSearchDao: RecentSearchDao,
    private val idGenerator: IdGenerator,
    private val timeUtil: TimeUtil,
    private val pinyinUtil: PinyinUtil,
) : ItemRepository {

    override suspend fun createItemQuick(
        name: String,
        categoryId: String?,
        note: String?,
        locationId: String,
    ): StoredItem {
        // 时间戳矩阵（§3.5）：新建物品 createdAt = lastModifiedAt = 同一次 now；
        // lastConfirmedAt 保持 null（默认参数）。
        val now = timeUtil.nowMillis()
        val (pinyinFull, pinyinInitial) = pinyinUtil.keys(name)
        val item = StoredItem(
            id = idGenerator.newId(),
            name = name,
            normalizedName = deriveNormalizedName(name),
            // 兜底字段（§3.2）：aliasBlob = ""、status = IN_STORAGE、quantity = 1 均由默认参数给出。
            // 位置必填：由调用方（QuickAddViewModel 的 canSave）保证非空，这里落库并记一次「使用」。
            locationId = locationId,
            categoryId = categoryId,
            note = note,
            createdAt = now,
            lastModifiedAt = now,
        )
        itemDao.insert(item.toEntity(pinyinFull = pinyinFull, pinyinInitial = pinyinInitial))
        // FR-11：这次录入即「使用」了该位置 → 下次录入默认预选它。
        locationDao.touchLastUsed(id = locationId, at = now)
        return item
    }

    override fun observeItemSummaries(): Flow<List<StoredItem>> =
        itemDao.observeAll().map { rows -> filterActive(rows.map { it.toDomain() }) }

    /**
     * 检索文档 = `item` × `category` × `location` 三表联合（P0-04 ②③）。
     *
     * `combine` 三条流任一变化都会重建，这是**有意**的：位置改名 / 移动会改变路径与路径拼音，
     * 索引必须跟着重建才守得住 FR-03「改名后所有路径展示自动跟随」。
     */
    override fun observeSearchDocs(): Flow<List<SearchDoc>> =
        combine(
            itemDao.observeAll(),
            categoryRepository.observeCategories(),
            locationDao.observeAll(),
        ) { rows, categories, locations -> buildSearchDocs(rows, categories, locations) }

    override fun observeRecentQueries(): Flow<List<String>> =
        recentSearchDao.observeRecent(RecentSearchDao.RECENT_SEARCH_LIMIT)
            .map { rows -> rows.map { it.query } }

    override suspend fun recordRecentQuery(query: String) {
        val trimmed = query.trim()
        val normalized = TextNormalizer.normalize(trimmed)
        // 归一化后为空 = 没有检索价值（纯空白 / 纯标点），不记。
        if (normalized.isEmpty()) return
        recentSearchDao.upsert(
            RecentSearchEntity(
                query = trimmed,
                normalizedQuery = normalized,
                searchedAt = timeUtil.nowMillis(),
            ),
        )
        // 上限维护：读侧本身带 LIMIT，这里只是把表压回 20 条。两条语句之间若被打断，
        // 最多短暂留下 21 条，下次写入即自愈 —— 不值得为一个「非跨表、非破坏性」的
        // 收尾动作把事务依赖引入本类（§2 的事务守卫针对的是删子树 / 迁移这类复合写）。
        recentSearchDao.trim(RecentSearchDao.RECENT_SEARCH_LIMIT)
    }

    override suspend fun getItemDetail(itemId: String): ItemDetail? {
        val item = itemDao.findById(itemId)?.toDomain() ?: return null
        // 分类为只读流，取首个快照即可（本页无分类写入路径）。
        val categories: List<Category> = categoryRepository.observeCategories().first()
        return ItemDetail(
            item = item,
            categoryName = item.categoryId?.let { id -> categories.firstOrNull { it.id == id }?.name },
            // FR-02：面包屑是派生值，由父链实时拼装（改名即时跟随）。
            locationPath = buildLocationPath(item.locationId),
        )
    }

    /** 由父链拼名称路径。取**全量**位置（含内置哨兵），使极端的「收容」物品也能显示一个名字。 */
    private suspend fun buildLocationPath(locationId: String): String {
        val locations = locationDao.observeAll().first().map { it.toDomain() }
        return LocationPath.textOf(locationId, locations.associateBy { it.id })
    }

    override suspend fun confirmItem(itemId: String): StoredItem? {
        // 只动 lastConfirmedAt，**不动** lastModifiedAt（§3.5）。
        val affected = itemDao.updateLastConfirmedAt(itemId, timeUtil.nowMillis())
        if (affected == 0) return null
        return itemDao.findById(itemId)?.toDomain()
    }

    override suspend fun setStatus(itemId: String, status: ItemStatus): StoredItem? {
        // 「不在了」/ 恢复：写 status 并刷新 lastModifiedAt（§3.5 矩阵的两行）。
        val affected = itemDao.updateStatus(itemId, status, timeUtil.nowMillis())
        if (affected == 0) return null
        return itemDao.findById(itemId)?.toDomain()
    }

    override suspend fun restore(itemId: String): StoredItem? = setStatus(itemId, ItemStatus.IN_STORAGE)

    // --- P1-03 / 04 / 06：编辑、批量确认、查重（骨架，桩体待实现） -----------------------

    override suspend fun updateFields(itemId: String, patch: ItemFieldPatch): StoredItem? =
        TODO("P1-04 ①: 只写被给出的字段；别名变更时同事务重算 pinyin_full / pinyin_initial")

    override suspend fun confirmByLocation(nodeId: String): Int =
        TODO("P1-02 ⑥ / FR-28: 含子层批量确认（写 last_confirmed_at，不动 last_modified_at）")

    override suspend fun findSimilar(name: String): List<StoredItem> =
        TODO("P1-06 ② / FR-14: 归一化同名 + 互为子串且长度差 ≤ 2 的查重（非阻塞提示）")

    /**
     * 三表 → 检索文档的纯映射（无挂起、无 IO），便于按批一次性算出派生字段。
     *
     * 位置拼音在**本批内按路径文本缓存**：一个位置只拼音化一次，代价与物品数（及重复物品数）
     * 脱钩（§8.1-10 的「构建时实时拼音化 + 同名缓存，不落库」）。
     */
    private fun buildSearchDocs(
        rows: List<ItemEntity>,
        categories: List<Category>,
        locations: List<LocationEntity>,
    ): List<SearchDoc> {
        val categoryNameById = categories.associate { it.id to it.name }
        val locationPathById = LocationPath.textsOf(locations.map { it.toDomain() })
        val locationPinyinCache = HashMap<String, String>()
        return rows.filter { it.status.isActive }.map { row ->
            val path = locationPathById[row.locationId].orEmpty()
            row.toSearchDoc(
                categoryNameById = categoryNameById,
                locationPath = path,
                pinyinLocationPath = locationPinyinCache.getOrPut(path) { pinyinUtil.full(path) },
            )
        }
    }
}

/** 检索键派生：归一化名与 `TextNormalizer.normalize` 同源（§3.2）。 */
internal fun deriveNormalizedName(name: String): String = TextNormalizer.normalize(name)

/** 失效过滤：默认只查 active（§3.5「谓词在仓库层过滤函数」）。 */
internal fun filterActive(items: List<StoredItem>): List<StoredItem> =
    items.filter { it.status.isActive }

/** 领域模型 → 持久化行。拼音两列由调用方在写入前派生（§8.1-10）。 */
internal fun StoredItem.toEntity(pinyinFull: String, pinyinInitial: String): ItemEntity =
    ItemEntity(
        id = id,
        name = name,
        normalizedName = normalizedName,
        pinyinFull = pinyinFull,
        pinyinInitial = pinyinInitial,
        aliasBlob = aliasBlob,
        locationId = locationId,
        categoryId = categoryId,
        status = status,
        quantity = quantity,
        note = note,
        createdAt = createdAt,
        lastModifiedAt = lastModifiedAt,
        lastConfirmedAt = lastConfirmedAt,
    )

/**
 * 持久化行 → 领域模型。
 *
 * TODO(P1-04): `aliases` 目前取默认空列表 —— `alias_blob` 的解码（`\u001F` 切分）随
 * 物品编辑页一并落地；在那之前 `aliasBlob` 也是恒空串，两者一致，不会出现「一个说有一个说没有」。
 */
internal fun ItemEntity.toDomain(): StoredItem = StoredItem(
    id = id,
    name = name,
    normalizedName = normalizedName,
    aliasBlob = aliasBlob,
    locationId = locationId,
    categoryId = categoryId,
    status = status,
    quantity = quantity,
    note = note,
    createdAt = createdAt,
    lastModifiedAt = lastModifiedAt,
    lastConfirmedAt = lastConfirmedAt,
)

/**
 * `item` 行 → 检索文档（P0-04 ②）。**直接读实体**而不是 `StoredItem`：拼音两列是持久化列，
 * 而 `StoredItem`（领域模型）刻意不带持久化细节。
 *
 * 分工：分类名由 [categoryNameById] 映射、位置路径与路径拼音由调用方按位置算好后传入
 * —— 本函数保持纯映射，不做逐物品的重复计算。
 * `aliasBlob` 仍恒空串（§7.1 别名置空），故不参与检索维度。
 */
internal fun ItemEntity.toSearchDoc(
    categoryNameById: Map<String, String>,
    locationPath: String,
    pinyinLocationPath: String,
): SearchDoc {
    val resolvedCategoryName = categoryId?.let { categoryNameById[it] }
    return SearchDoc(
        itemId = id,
        name = name,
        normalizedName = normalizedName,
        pinyinFull = pinyinFull,
        pinyinInitial = pinyinInitial,
        categoryId = categoryId,
        categoryName = resolvedCategoryName,
        normalizedCategoryName = resolvedCategoryName?.let { TextNormalizer.normalize(it) },
        locationPath = locationPath,
        normalizedLocationPath = TextNormalizer.normalize(locationPath),
        pinyinLocationPath = pinyinLocationPath,
        status = status,
        lastModifiedAt = lastModifiedAt,
        lastConfirmedAt = lastConfirmedAt,
    )
}
