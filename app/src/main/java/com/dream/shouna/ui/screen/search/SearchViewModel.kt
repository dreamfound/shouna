package com.dream.shouna.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ConfigRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.search.SearchConfig
import com.dream.shouna.domain.search.SearchDoc
import com.dream.shouna.domain.search.SearchHit
import com.dream.shouna.domain.search.SearchIndex
import com.dream.shouna.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * FR-19 输入即搜 + FR-20 拼音 + FR-23 最近搜索词（ARCHITECTURE §4 F1-04 ③ / P0-04 ⑤⑥）：
 * `debounce(200ms)` + `collectLatest` + `Dispatchers.Default`；索引懒构建，冷启动不阻塞。
 *
 * 结果行的**展示信息**（位置路径 · 最后确认 + FR-27 超期标记）在本层组装：Screen 层不持有
 * [TimeUtil] / [ConfigRepository]（ARCHITECTURE §2）。
 *
 * 被调用方：SearchRoute（hiltViewModel + setInitialQuery / onQueryChange）
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val configRepository: ConfigRepository,
    private val timeUtil: TimeUtil,
    private val searchConfig: SearchConfig,
) : ViewModel() {

    private val state = MutableStateFlow(SearchUiState())

    val uiState: StateFlow<SearchUiState> = state.asStateFlow()

    /** 输入通道：UI 每次输入写入，经 [DEBOUNCE_MILLIS] 去抖后再检索。 */
    private val queryInput = MutableStateFlow("")

    /** 懒构建的内存索引；指纹未变时复用，不随每次输入重建。 */
    @Volatile
    private var index: SearchIndex? = null

    /** 最近一次**已记录**的检索词（原始串）。防同一次输入被反复写库（FR-23）。 */
    private var recordedQuery: String? = null

    init {
        // 索引构建与检索都放在 Default 调度器：输入即搜不占主线程。
        viewModelScope.launch(Dispatchers.Default) {
            combine(
                queryInput.debounce(DEBOUNCE_MILLIS),
                itemRepository.observeSearchDocs(),
                // 超期阈值放进 combine（而不是事后补一次读）：保证「第一帧结果就是用真实阈值
                // 判定的」，不靠一个猜的默认值顶着（P0 无改值入口，读一次即够）。
                flow { emit(configRepository.thresholdMonths()) },
            ) { query, docs, thresholdMonths -> SearchInput(query, docs, thresholdMonths) }
                .collectLatest { input ->
                    // 索引重建触发 = 文档列表指纹变化（§4 F1-04 ③）。
                    val searchIndex = resolveIndex(input.docs)
                    val hits: List<SearchHit> = if (input.query.isBlank()) {
                        emptyList()
                    } else {
                        searchIndex.search(input.query, searchConfig.limit)
                    }
                    val now = timeUtil.nowMillis()
                    state.update {
                        it.copy(
                            results = hits.map { hit -> hit.toResultUi(input.thresholdMonths, now) },
                            isIndexing = false,
                        )
                    }
                }
        }

        // FR-23：最近搜索词（空态展示）。
        viewModelScope.launch {
            itemRepository.observeRecentQueries().collect { queries ->
                state.update { it.copy(recentQueries = queries) }
            }
        }

        // FR-23 记录通道：复用同一条输入，但**去抖更长** —— 只有「停下来了」才记。
        // 否则 `d` / `ds` / `dfs` 这类逐字中间态会一条条写进最近搜索，把真正有用的词挤掉。
        // 判据仍然是 §4 P0-04 ⑤ 的「执行搜索且结果非空」：结果为空 = 这次检索没价值，不记。
        viewModelScope.launch(Dispatchers.Default) {
            queryInput.debounce(RECORD_DEBOUNCE_MILLIS).collectLatest { query ->
                if (query.isBlank()) {
                    // 清空输入 = 一次检索会话结束 → 允许同一个词再次被记录（刷新最近时间）。
                    recordedQuery = null
                    return@collectLatest
                }
                recordIfUseful(query)
            }
        }
    }

    /** 从首页搜索框进入时带的初始词。 */
    fun setInitialQuery(initialQuery: String?) {
        // 复用同一输入通道，不另开一条流。
        onQueryChange(initialQuery.orEmpty())
    }

    fun onQueryChange(value: String) {
        // 输入框回显立即更新；结果由去抖后的检索流补上。
        state.update { it.copy(query = value) }
        queryInput.value = value
    }

    // --- P1-06 ①（FR-22）：筛选 chips（骨架，未接入 Screen） -----------------------------
    // 与「输入即搜」正交：筛选只**收窄**已命中的结果集，不参与检索判定，因此不必回到
    // `SearchIndex` 里改打分（守 `实现约束.md` §4-5：筛选控件在 P1 才进搜索页）。
    // 三个入口此刻只立签名，Screen 尚未挂 `FilterChips`，避免出现可点即崩的中间态。

    /** FR-22：分类维度。 */
    fun onCategoryFilterSelected(categoryId: String?) {
        TODO("P1-06 ①: 写入 filters.categoryId 并重算结果（可叠加、可清空）")
    }

    /** FR-22：位置维度。**含子层** —— 选中一个位置即含其全部子孙（P1 §8.1-7）。 */
    fun onLocationFilterSelected(locationId: String?) {
        TODO("P1-06 ①: 写入 filters.locationId；匹配口径为「含子层」")
    }

    /** FR-22：状态维度。 */
    fun onStatusFilterSelected(status: ItemStatus?) {
        TODO("P1-06 ①: 写入 filters.status")
    }

    /** FR-22：一键清空（三个维度一起）。纯状态复位，无副作用。 */
    fun onClearFilters() {
        state.update { it.copy(filters = SearchFilters()) }
    }

    private fun resolveIndex(docs: List<SearchDoc>): SearchIndex {
        val fingerprint = SearchIndex.fingerprintOf(docs)
        val current = index
        if (current != null && current.fingerprint == fingerprint) return current

        state.update { it.copy(isIndexing = true) }
        return SearchIndex.build(docs, searchConfig).also { index = it }
    }

    /**
     * 把一次「有效检索」写进最近搜索。三个条件缺一不可：
     * ① 输入已停止变化（去抖后）且与当前 UiState 一致 —— 否则这次 DOM 结果不是它产生的；
     * ② 结果非空（FR-23：只留真正搜出东西的词）；
     * ③ 与上次记录的**原始串**不同 —— 归一化去重交给 `recent_search` 的唯一索引。
     */
    private suspend fun recordIfUseful(query: String) {
        if (query == recordedQuery) return
        val snapshot = state.value
        if (snapshot.query != query || snapshot.results.isEmpty()) return
        recordedQuery = query
        itemRepository.recordRecentQuery(query)
    }

    /**
     * 结果行组装（P0-04 ⑥）：副标题 = 「位置路径 · 最后确认 N 个月前」，并给出 FR-27 的超期判定。
     *
     * 列表行只放**「最后确认」**一个时间（详情页才并列「最后变动」）：⚠ 的判定依据就是
     * `lastConfirmedAt`，两者同源才自洽；一行塞两个时间反而看不清哪个在报警。
     */
    private fun SearchHit.toResultUi(thresholdMonths: Int, now: Long): SearchResultUi = SearchResultUi(
        itemId = doc.itemId,
        name = doc.name,
        highlightRange = highlightRange,
        // FR-02 / US-04：路径单独带出，供结果行长按复制（副标题里还混着时间，不能整体复制）。
        locationPath = doc.locationPath,
        // 两段都可能缺（位置数据异常 / 时间缺失）→ 用 filter 兜住，不留以分隔符开头的副标题。
        subtitle = buildList {
            if (doc.locationPath.isNotEmpty()) add(doc.locationPath)
            add(
                if (doc.lastConfirmedAt == null) {
                    TimeUtil.NEVER_CONFIRMED
                } else {
                    "最后确认 ${timeUtil.relativeText(doc.lastConfirmedAt, now)}"
                },
            )
        }.joinToString(SUBTITLE_SEPARATOR),
        isOverdue = timeUtil.isOverdue(doc.lastConfirmedAt, thresholdMonths, now),
    )

    /** 检索流的三元输入（`combine` 的类型载体）。 */
    private data class SearchInput(
        val query: String,
        val docs: List<SearchDoc>,
        val thresholdMonths: Int,
    )

    private companion object {
        /** F1-04 ③：输入即搜 debounce 时长（2026-09-29 由 80ms 调至 200ms）。 */
        const val DEBOUNCE_MILLIS = 200L

        /** FR-23：记录最近搜索词的去抖时长；明显长于检索去抖，用于滤掉逐字中间态。 */
        const val RECORD_DEBOUNCE_MILLIS = 700L

        /** 结果行副标题的分隔符。 */
        const val SUBTITLE_SEPARATOR = " · "
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<SearchResultUi> = emptyList(),
    /** FR-23：空态展示的最近搜索词（按最近倒序，上限 20 条）。 */
    val recentQueries: List<String> = emptyList(),
    val isIndexing: Boolean = false,
    /** P1-06 ①（FR-22）：筛选态。骨架期恒为「未筛选」。 */
    val filters: SearchFilters = SearchFilters(),
)

/**
 * FR-22 的筛选三维（P1-06）。`null` = 该维度未筛选；三个维度**可叠加**。
 *
 * 位置维度选中的是**一个位置 id**，但匹配口径是「含子层」——即选中「家」也包含「家 › 储物间」
 * 下的物品（P1 §8.1-7）。子层展开在 ViewModel 做（要拿 `location.path` 前缀），不在组件里。
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

/**
 * 结果行（P0-04 ⑥）：VM 已把「路径 · 时间」与超期判定算好，Screen 只负责渲染
 * —— 与 `ItemRow` 的 `subtitle` / `showOverdue` 两个可选参数一一对应。
 */
data class SearchResultUi(
    val itemId: String,
    val name: String,
    /** 仅名称命中时非空（拼音 / 位置 / 分类命中没有可高亮的名称片段）。 */
    val highlightRange: IntRange?,
    /** FR-02 / US-04：完整位置路径（长按可复制）；位置数据异常时为空串。 */
    val locationPath: String,
    /** 如「家 › 储物间 · 最后确认 3 个月前」。 */
    val subtitle: String,
    /** FR-27：超过阈值未确认（含「从未确认」）→ 行尾显示 ⚠。 */
    val isOverdue: Boolean,
)
