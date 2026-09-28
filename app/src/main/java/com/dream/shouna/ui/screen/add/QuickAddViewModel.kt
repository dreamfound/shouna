package com.dream.shouna.ui.screen.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * FR-09 / FR-10 / FR-12 的状态机（ARCHITECTURE §2）。
 *
 * 被调用方：QuickAddRoute（hiltViewModel + 方法引用下传）
 */
@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val state = MutableStateFlow(QuickAddUiState())

    val uiState: StateFlow<QuickAddUiState> = state.asStateFlow()

    /** FR-12：内置分类 chips 数据源。 */
    val categories: StateFlow<List<Category>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun onNameChange(value: String) {
        // canSave 口径：去除首尾空白后非空才算可保存（纯空白 = 未填）。
        state.update { it.copy(inputName = value, canSave = value.isNotBlank()) }
    }

    fun onCategorySelected(categoryId: String?) {
        // categoryId = null 即「再次点击取消选中」（FR-12 可跳过）。
        state.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun onNoteChange(value: String) {
        // 原样保存输入，空白归一延迟到落库时（见 onSaveAndContinue）。
        state.update { it.copy(note = value) }
    }

    /** 「保存并继续」：清空输入、保持焦点、计数器 +1，零弹窗。 */
    fun onSaveAndContinue() {
        val current = state.value
        if (!current.canSave) return

        viewModelScope.launch {
            try {
                itemRepository.createItemQuick(
                    name = current.inputName.trim(),
                    categoryId = current.selectedCategoryId,
                    // 空白备注归一为 null（StoredItem.note 可空）。
                    note = current.note?.trim()?.ifBlank { null },
                )
            } catch (_: Throwable) {
                // FR-10「零弹窗」：不做任何错误弹窗，保持输入原样、计数器不动，由用户重试。
                return@launch
            }
            // 清空输入（含分类选中与备注，回到干净待录状态）；计数器 +1；焦点由 QuickAddScreen 依
            // sessionCount 变化重新请求，保证连续录入不必手动点回输入框。
            state.update {
                it.copy(
                    inputName = "",
                    selectedCategoryId = null,
                    note = null,
                    sessionCount = it.sessionCount + 1,
                    canSave = false,
                )
            }
        }
    }
}

data class QuickAddUiState(
    val inputName: String = "",
    val selectedCategoryId: String? = null,
    val note: String? = null,
    val sessionCount: Int = 0,
    val canSave: Boolean = false,
)
