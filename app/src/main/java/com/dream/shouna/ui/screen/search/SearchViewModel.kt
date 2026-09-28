package com.dream.shouna.ui.screen.search

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.domain.search.SearchConfig
import com.dream.shouna.domain.search.SearchDoc
import com.dream.shouna.domain.search.SearchHit
import com.dream.shouna.domain.search.SearchIndex
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * FR-19 输入即搜（ARCHITECTURE §2 / §4 F1-04 ③）：
 * `debounce(80ms)` + `collectLatest` + `Dispatchers.Default`；索引懒构建，冷启动不阻塞。
 *
 * 被调用方：SearchRoute（hiltViewModel + setInitialQuery / onQueryChange）
 */
@OptIn(FlowPreview::class)
@HiltViewModel
class SearchViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val searchConfig: SearchConfig,
) : ViewModel() {

    private val state = MutableStateFlow(SearchUiState())

    val uiState: StateFlow<SearchUiState> = state.asStateFlow()

    /** 输入通道：UI 每次输入写入，经 [DEBOUNCE_MILLIS] 去抖后再检索。 */
    private val queryInput = MutableStateFlow("")

    /** 懒构建的内存索引；指纹未变时复用，不随每次输入重建。 */
    @Volatile
    private var index: SearchIndex? = null

    init {
        // 索引构建与检索都放在 Default 调度器：输入即搜不占主线程。
        viewModelScope.launch(Dispatchers.Default) {
            combine(
                queryInput.debounce(DEBOUNCE_MILLIS),
                itemRepository.observeSearchDocs(),
            ) { query, docs -> query to docs }
                .collectLatest { (query, docs) ->
                    // 索引重建触发 = 文档列表指纹变化（§4 F1-04 ③）。
                    val searchIndex = resolveIndex(docs)
                    val results: List<SearchHit> = if (query.isBlank()) {
                        emptyList()
                    } else {
                        searchIndex.search(query, searchConfig.limit)
                    }
                    state.update { it.copy(results = results, isIndexing = false) }
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

    private fun resolveIndex(docs: List<SearchDoc>): SearchIndex {
        val fingerprint = SearchIndex.fingerprintOf(docs)
        val current = index
        if (current != null && current.fingerprint == fingerprint) return current

        state.update { it.copy(isIndexing = true) }
        return SearchIndex.build(docs, searchConfig).also { index = it }
    }

    private companion object {
        /** F1-04 ③：输入即搜 debounce 时长。 */
        const val DEBOUNCE_MILLIS = 80L
    }
}

data class SearchUiState(
    val query: String = "",
    val results: List<SearchHit> = emptyList(),
    val isIndexing: Boolean = false,
)
