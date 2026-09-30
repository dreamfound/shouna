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
        // 时间戳矩阵（§3.5，2026-09-30 修订）：三个时间列取**同一次** now —— 录入本身就是一次
        // 「我知道它在这儿」的确认。留 NULL 会让刚存进去的物品立刻被判超期
        // （`TimeUtil.isOverdue(null, …)` 恒真）→ 列表带 ⚠，并直接进 C-4「超期未确认」清单。
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
            lastConfirmedAt = now,
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

    // --- P1-02 / 03 / 04 / 06：编辑、批量确认、查重 ---------------------------------------

    override suspend fun updateFields(itemId: String, patch: ItemFieldPatch): StoredItem? {
        val applyCategory = patch.clearCategory || patch.categoryId != null
        val applyAliases = patch.aliases != null
        val applyQuantity = patch.quantity != null
        val applyNote = patch.note != null

        // 四项都没给 = 空补丁：不改任何列，但仍按「命中即返回当前记录」的既有语义回值。
        if (!applyCategory && !applyAliases && !applyQuantity && !applyNote) {
            return itemDao.findById(itemId)?.toDomain()
        }

        // 数量口径（`prd/11` Q3）：整数 ≥ 1；非法值不落库（调用方已在 UI 拦，这里兜第二道）。
        val quantity = patch.quantity
        if (quantity != null && quantity < 1) return null

        // 别名编码 + 拼音重算。拼音列取「名称 + 别名」的全拼/首字母：别名与名称同档参与检索，
        // 若只拿名称算拼音，「用拼音搜别名」就会漏（P1 §4 P1-04 ①）。
        val aliasBlob = patch.aliases?.let { encodeAliases(it) } ?: ""
        val (pinyinFull, pinyinInitial) = if (applyAliases) {
            val name = itemDao.findById(itemId)?.name ?: return null
            pinyinUtil.keys(joinForPinyin(name, patch.aliases.orEmpty()))
        } else {
            "" to ""
        }

        val affected = itemDao.updateFields(
            id = itemId,
            applyCategory = applyCategory,
            categoryId = if (patch.clearCategory) null else patch.categoryId,
            applyAliases = applyAliases,
            aliasBlob = aliasBlob,
            applyQuantity = applyQuantity,
            quantity = quantity ?: 0,
            applyNote = applyNote,
            // 空串 / 纯空白 → 清空备注（`note` 可空）。
            note = patch.note?.trim()?.ifBlank { null },
            pinyinFull = pinyinFull,
            pinyinInitial = pinyinInitial,
        )
        if (affected == 0) return null
        // 时间戳口径（§3.4-10 / §3.5）：本方法**不写任何时间列** —— 改分类 / 别名 / 数量 / 备注
        // 都不是「东西在哪、还在不在」的变化，`created_at` / `last_modified_at` / `last_confirmed_at` 全不动。
        return itemDao.findById(itemId)?.toDomain()
    }

    override suspend fun confirmByLocation(nodeId: String): Int {
        val node = locationDao.findById(nodeId) ?: return 0
        // 路径为空 = 脏数据（迁移应已回填）。此处**必须早退**：空前缀的区间查询会命中全表，
        // 变成「把整个库都确认了一遍」，这比不确认危险得多。
        if (node.path.isEmpty()) return 0
        return itemDao.confirmActiveInSubtree(prefix = node.path, confirmedAt = timeUtil.nowMillis())
    }

    override suspend fun findSimilar(name: String): List<StoredItem> {
        val normalized = deriveNormalizedName(name)
        if (normalized.isEmpty()) return emptyList()
        return itemDao.observeAll().first()
            .map { it.toDomain() }
            .filter { it.status.isActive }
            .filter { isSimilarName(candidate = it.normalizedName, target = normalized) }
    }

    override suspend fun putBack(itemId: String, locationId: String): StoredItem? {
        // 单表写：位置 + 状态 + last_modified_at 一条语句，命中与否看受影响行数（§2-6 不先查再写）。
        val affected = itemDao.putBack(
            id = itemId,
            locationId = locationId,
            status = ItemStatus.IN_STORAGE,
            modifiedAt = timeUtil.nowMillis(),
        )
        if (affected == 0) return null
        return itemDao.findById(itemId)?.toDomain()
    }

    override suspend fun moveItem(itemId: String, locationId: String): StoredItem? {
        // 位置变化属「变动」→ 刷新 last_modified_at；状态与确认时间不动（§3.5 矩阵）。
        val affected = itemDao.moveItem(id = itemId, locationId = locationId, modifiedAt = timeUtil.nowMillis())
        if (affected == 0) return null
        return itemDao.findById(itemId)?.toDomain()
    }

    override fun observeActiveItemCount(): Flow<Int> = itemDao.observeActiveCount()

    override fun observeOverdueItems(thresholdMonths: Int): Flow<List<StoredItem>> {
        // 阈值与 `TimeUtil.isOverdue` 用**同一套换算**（`MILLIS_PER_MONTH`），否则会出现
        // 「详情页显示 5 个月前、清单却把它算成超期」这种自相矛盾。
        val thresholdAt = timeUtil.nowMillis() - thresholdMonths.toLong() * TimeUtil.MILLIS_PER_MONTH
        return itemDao.observeOverdue(thresholdAt).map { rows -> rows.map { it.toDomain() } }
    }

    override fun observeToBePutBackItems(): Flow<List<StoredItem>> =
        itemDao.observeToBePutBack().map { rows -> rows.map { it.toDomain() } }

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
 * P1-04 起 `alias_blob` **解码**进 `aliases`：`\u001F` 切分、丢空段。
 * 解码放在转换函数里（而不是让每个调用方自己切）是刻意的 —— `toDomain` 是 `item` 行的唯一出口，
 * 放在这里就不可能出现「某条读路径忘了带别名」。
 */
internal fun ItemEntity.toDomain(): StoredItem = StoredItem(
    id = id,
    name = name,
    normalizedName = normalizedName,
    aliasBlob = aliasBlob,
    aliases = decodeAliases(aliasBlob),
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
 * `item` 行 → 检索文档（P0-04 ② + P1-04 / P1-06）。**直接读实体**而不是 `StoredItem`：
 * 拼音两列是持久化列，而 `StoredItem`（领域模型）刻意不带持久化细节。
 *
 * 分工：分类名由 [categoryNameById] 映射、位置路径与路径拼音由调用方按位置算好后传入
 * —— 本函数保持纯映射，不做逐物品的重复计算；别名就地解码（同一行的 `alias_blob`）。
 *
 * P1-06 起带出 `locationId`：FR-22 的位置筛选要判「含子层」，而判定依据是位置**树**
 * 的前缀关系，光有展示用的 `locationPath`（名称路径，会因改名而变）无从下手。
 */
internal fun ItemEntity.toSearchDoc(
    categoryNameById: Map<String, String>,
    locationPath: String,
    pinyinLocationPath: String,
): SearchDoc {
    val resolvedCategoryName = categoryId?.let { categoryNameById[it] }
    val aliases = decodeAliases(aliasBlob)
    return SearchDoc(
        itemId = id,
        name = name,
        normalizedName = normalizedName,
        aliases = aliases,
        normalizedAliases = aliases.map { TextNormalizer.normalize(it) },
        pinyinFull = pinyinFull,
        pinyinInitial = pinyinInitial,
        categoryId = categoryId,
        categoryName = resolvedCategoryName,
        normalizedCategoryName = resolvedCategoryName?.let { TextNormalizer.normalize(it) },
        locationId = locationId,
        locationPath = locationPath,
        normalizedLocationPath = TextNormalizer.normalize(locationPath),
        pinyinLocationPath = pinyinLocationPath,
        status = status,
        lastModifiedAt = lastModifiedAt,
        lastConfirmedAt = lastConfirmedAt,
    )
}

/** 别名的分隔符（`item.alias_blob` 的编码口径，P1 §3.1「`\u001F` 分隔拼接」）。 */
internal const val ALIAS_SEPARATOR: Char = '\u001F'

/** 别名列表 → `alias_blob`。顺带丢掉空段，免得一个手滑的空别名在 chips 上占一格。 */
internal fun encodeAliases(aliases: List<String>): String =
    aliases.map { it.trim() }.filter { it.isNotEmpty() }.joinToString(ALIAS_SEPARATOR.toString())

/** `alias_blob` → 别名列表。空串 = 无别名（P0 的兜底值，不必额外判空）。 */
internal fun decodeAliases(blob: String): List<String> =
    if (blob.isEmpty()) emptyList() else blob.split(ALIAS_SEPARATOR).filter { it.isNotEmpty() }

/** 拼音派生输入 = 名称 + 全部别名（别名与名称同档参与检索，故同一份拼音键覆盖两者）。 */
internal fun joinForPinyin(name: String, aliases: List<String>): String =
    (listOf(name) + aliases.map { it.trim() }.filter { it.isNotEmpty() })
        .joinToString(PINYIN_INPUT_SEPARATOR)

/**
 * FR-14 的相似度判定（P1 §8.1-11）：
 * 「同名」= 归一化名**完全相同**；「高度相似」= 互为子串且**长度差 ≤ 2**。不做编辑距离。
 *
 * 纯函数，输入须是**已归一化**的串（调用方已过 `TextNormalizer.normalize`）。
 */
internal fun isSimilarName(candidate: String, target: String): Boolean {
    if (candidate.isEmpty() || target.isEmpty()) return false
    if (candidate == target) return true
    if (kotlin.math.abs(candidate.length - target.length) > SIMILAR_NAME_LENGTH_DELTA) return false
    return candidate.contains(target) || target.contains(candidate)
}

/** 拼音派生时名称与别名的连接符（空格：让拼音工具按词边界处理，不把两段黏成一个词）。 */
private const val PINYIN_INPUT_SEPARATOR = " "

/** 「高度相似」允许的长度差（FR-14，P1 §8.1-11）。 */
private const val SIMILAR_NAME_LENGTH_DELTA = 2
