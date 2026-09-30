package com.dream.shouna.domain.search

import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.model.LocationTreeRow

/**
 * FR-22 三维筛选（分类 / 位置 / 状态）的**共享实现**。
 *
 * 【为什么抽到 `domain` 而不是留在搜索页】P1-06 只有搜索页用，P1-07 起 P-STATS 的「全部物品」
 * 明细也用同一套。若各写一份，「位置含子层」的判定与状态 chip 的中文文案必然分裂成两套，
 * 用户会看到两个页面对同一个筛选维度给出不同结果。
 *
 * 纯 Kotlin、无 Android / 无协程依赖 → 可直接 JVM 单测；`ui.screen.*` 里不再保留副本。
 *
 * 被调用方：`SearchViewModel`（检索前收窄文档集）、`StatsViewModel`（全部物品明细）。
 */
fun applyDocFilters(
    docs: List<SearchDoc>,
    filters: SearchFilters,
    tree: List<LocationTreeRow>,
): List<SearchDoc> {
    if (filters.isEmpty) return docs
    val subtreeIds = filters.locationId?.let { rootId -> subtreeIdsOf(rootId, tree) }
    return docs.filter { doc ->
        (filters.categoryId == null || doc.categoryId == filters.categoryId) &&
            (filters.status == null || doc.status == filters.status) &&
            (subtreeIds == null || doc.locationId in subtreeIds)
    }
}

/**
 * FR-22 筛选项的候选项。
 *
 * 分类 / 状态取自**当前文档集**而不是分类表：结果只含该集合内的物品，若按分类表罗列，
 * 会出现「筛一个当前根本不可能命中的分类」这种只能得到空结果的选项。
 * 位置取整棵树：位置维度是「含子层」的，父级本身就是合法目标（其下可能只有子孙里有东西），
 * 因此不能只列「直接放着物品」的那几个位置。
 */
fun buildDocFilterChoices(
    docs: List<SearchDoc>,
    tree: List<LocationTreeRow>,
): SearchFilterChoices = SearchFilterChoices(
    categories = docs
        .mapNotNull { doc -> doc.categoryId?.let { id -> SearchFilterChoice(id, doc.categoryName.orEmpty()) } }
        .distinctBy { it.id }
        .sortedBy { it.label },
    locations = tree.map { row -> SearchFilterChoice(row.location.id, row.pathText) },
    statuses = docs
        .map { it.status }
        .distinct()
        .sortedBy { it.ordinal }
        .map { status -> SearchFilterChoice(status.code, status.filterLabel()) },
)

/**
 * FR-22 的位置维度判定：`rootId` 自己 + 其**全部子孙**的 id 集（P1 §8.1-7 的递归口径）。
 *
 * 按**父子链** BFS，不比对 `location.path`：物化列是派生值，而父子关系才是它的真源
 * （P1 §3.4-7），按父链走不依赖「物化列是否已回填」。
 * `rootId` 不在树中（并发删除）时结果只含它自己 → 该筛选命中零条，符合直觉。
 */
fun subtreeIdsOf(rootId: String, tree: List<LocationTreeRow>): Set<String> {
    val childrenByParent = tree.groupBy { it.location.parentId }
    val result = HashSet<String>()
    val queue = ArrayDeque<String>()
    queue.add(rootId)
    while (queue.isNotEmpty()) {
        val id = queue.removeFirst()
        if (!result.add(id)) continue
        childrenByParent[id]?.forEach { child -> queue.add(child.location.id) }
    }
    return result
}

/** FR-22：状态 chip 的中文文案（搜索页与统计页的筛选行共用一份）。 */
fun ItemStatus.filterLabel(): String = when (this) {
    ItemStatus.IN_STORAGE -> "在存放中"
    ItemStatus.TO_BE_PUT_BACK -> "待归位"
    ItemStatus.GONE -> "不在了"
}

/**
 * FR-22 的筛选三维。`null` = 该维度未筛选；三个维度**可叠加**（AND）。
 *
 * 位置维度选中的是**一个位置 id**，但匹配口径是「含子层」——即选中「家」也包含「家 › 储物间」
 * 下的物品（P1 §8.1-7）。子层展开在 [applyDocFilters] 里做（按父子链算自身+子孙集）。
 */
data class SearchFilters(
    val categoryId: String? = null,
    val locationId: String? = null,
    val status: ItemStatus? = null,
) {
    /** 三维皆空 = 未筛选（UI 据此决定要不要显示「清空」）。 */
    val isEmpty: Boolean
        get() = categoryId == null && locationId == null && status == null
}

/** 三维筛选项的候选项集合。 */
data class SearchFilterChoices(
    val categories: List<SearchFilterChoice> = emptyList(),
    val locations: List<SearchFilterChoice> = emptyList(),
    val statuses: List<SearchFilterChoice> = emptyList(),
)

/**
 * 一个候选项：[id] 与 [label] 分离（分类与位置都是 UUID，不能拿名字当键）。
 *
 * 刻意**不复用** `ui.component.FilterOption`：这是纯数据，组件参数是渲染细节，
 * 让领域层依赖 `ui.component` 会把依赖方向倒过来（Screen 层做一次映射即可）。
 */
data class SearchFilterChoice(
    val id: String,
    val label: String,
)
