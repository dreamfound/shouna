package com.dream.shouna.data.repository

import com.dream.shouna.data.memory.BuiltInData
import com.dream.shouna.data.memory.InMemoryStore
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.ItemDetail
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.domain.search.SearchDoc
import com.dream.shouna.util.IdGenerator
import com.dream.shouna.util.TextNormalizer
import com.dream.shouna.util.TimeUtil
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * F1-03 ①：在 [InMemoryStore] 的 Mutex 临界区内生成归一化名与检索键并**原子追加**（无事务）。
 *
 * 分类名（`SearchDoc.categoryName` / `ItemDetail.categoryName`）按 PRD `08 §7.3` 的 A-2 约束
 * **不冗余写入物品记录**，一律在构建检索／详情数据时经 [CategoryRepository] 实时关联拼入。
 *
 * 被调用方：QuickAddViewModel（[createItemQuick]）、HomeViewModel（[observeItemSummaries]）、
 *           SearchViewModel（[observeSearchDocs]）、ItemDetailViewModel（[getItemDetail] / [confirmItem]）
 */
@Singleton
class ItemRepositoryImpl @Inject constructor(
    private val store: InMemoryStore,
    private val categoryRepository: CategoryRepository,
    private val idGenerator: IdGenerator,
    private val timeUtil: TimeUtil,
) : ItemRepository {

    override suspend fun createItemQuick(
        name: String,
        categoryId: String?,
        note: String?,
    ): StoredItem {
        // 时间戳矩阵（§3.5）：新建物品 createdAt = lastModifiedAt = 同一次 now；
        // lastConfirmedAt 保持 null（默认参数）。
        val now = timeUtil.nowMillis()
        val item = StoredItem(
            id = idGenerator.newId(),
            name = name,
            normalizedName = deriveNormalizedName(name),
            // 兜底字段（§3.2）：aliasBlob = ""、status = IN_STORAGE、quantity = 1 均由默认参数给出。
            locationId = BuiltInData.UNSPECIFIED_LOCATION_ID,
            categoryId = categoryId,
            note = note,
            createdAt = now,
            lastModifiedAt = now,
        )
        return store.append(item)
    }

    override fun observeItemSummaries(): Flow<List<StoredItem>> =
        store.items.map { items -> filterActive(items) }

    override fun observeSearchDocs(): Flow<List<SearchDoc>> =
        combine(store.items, categoryRepository.observeCategories()) { items, categories ->
            val nameById = categories.associate { it.id to it.name }
            filterActive(items).map { item -> item.toSearchDoc(nameById) }
        }

    override suspend fun getItemDetail(itemId: String): ItemDetail? {
        val item = store.findById(itemId) ?: return null
        // 分类为只读常量流，取首个快照即可（§3.3：F1 无分类写入路径）。
        val categories: List<Category> = categoryRepository.observeCategories().first()
        return ItemDetail(
            item = item,
            categoryName = item.categoryId?.let { id -> categories.firstOrNull { it.id == id }?.name },
        )
    }

    override suspend fun confirmItem(itemId: String): StoredItem? =
        // 只动 lastConfirmedAt，**不动** lastModifiedAt（§3.5）。
        store.update(itemId) { current -> current.copy(lastConfirmedAt = timeUtil.nowMillis()) }
}

/** 检索键派生：归一化名与 `TextNormalizer.normalize` 同源（§3.2）。 */
internal fun deriveNormalizedName(name: String): String = TextNormalizer.normalize(name)

/** 失效过滤：默认只查 active（§3.5「谓词在 F1 就落进仓库层过滤函数」）。 */
internal fun filterActive(items: List<StoredItem>): List<StoredItem> =
    items.filter { it.status.isActive }

/**
 * `StoredItem` → 检索文档（§4 F1-04 ①）。
 * `aliasBlob` 在 F1 恒空串（§7.1 别名置空），故不参与检索维度；检索维度 = 归一化名 + 分类名。
 */
private fun StoredItem.toSearchDoc(categoryNameById: Map<String, String>): SearchDoc {
    val resolvedCategoryName = categoryId?.let { categoryNameById[it] }
    return SearchDoc(
        itemId = id,
        name = name,
        normalizedName = normalizedName,
        categoryId = categoryId,
        categoryName = resolvedCategoryName,
        normalizedCategoryName = resolvedCategoryName?.let { TextNormalizer.normalize(it) },
        status = status,
        lastModifiedAt = lastModifiedAt,
    )
}
