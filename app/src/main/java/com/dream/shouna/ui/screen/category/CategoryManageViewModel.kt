package com.dream.shouna.ui.screen.category

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.domain.model.Category
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 分类管理页（FR-44）的状态机。
 *
 * 没有 `load()`：列表由 [CategoryRepository.observeCategories] 的流直接驱动，
 * 不需要一次性的装载动作（这也让「新增 / 改名 / 删除」后列表自动刷新，不必手工重取）。
 *
 * 两条硬口径（P1 §4 P1-05）：
 * - **内置分类可改名、不可删**（判据 [Category.isBuiltIn]）——删除入口对内置项不出现；
 * - 删除走**二次确认**（`实现约束.md` §4-2），确认后其下物品经 FK `ON DELETE SET NULL`
 *   回落「未分类」，**物品本身不删**。二次确认的态在 [pendingDeleteId] 里，由页面渲染对话框。
 *
 * 名称校验（非空）只在本层做：「空白名称」是输入约束不是错误，静默丢弃，不弹提示
 * （与 `ItemEditViewModel.onAddAlias` 同一取向，守 FR-10 的零弹窗）。
 *
 * 被调用方：`CategoryManageRoute`
 */
@HiltViewModel
class CategoryManageViewModel @Inject constructor(
    private val categoryRepository: CategoryRepository,
) : ViewModel() {

    /** FR-44：内置 + 自定义的全量列表（按 `sort_order`）。 */
    val categories: StateFlow<List<Category>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /** 待删除确认的分类 id；null = 无对话框。 */
    private val pendingDelete = MutableStateFlow<String?>(null)

    val pendingDeleteId: StateFlow<String?> = pendingDelete.asStateFlow()

    /** P1-05 ①：新建自定义分类（名称非空校验；`sort_order` 由仓库排在同级末尾）。 */
    fun onCreate(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { categoryRepository.create(trimmed) }
    }

    /** P1-05 ②：改名（内置分类也允许；改名不影响任何物品记录）。 */
    fun onRename(id: String, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch { categoryRepository.rename(id, trimmed) }
    }

    /** 删除入口：先记下待确认项，由页面弹**二次确认**（`实现约束.md` §4-2）。 */
    fun onRequestDelete(id: String) {
        pendingDelete.value = id
    }

    fun onDismissDelete() {
        pendingDelete.value = null
    }

    /**
     * P1-05 ③：二次确认之后的真实删除。
     *
     * 内置分类由仓库层挡下（SQL 带 `is_built_in = 0`，返回 false）——本层**不**再判一次
     * `isBuiltIn`：页面本就不给内置项删除入口，仓库才是唯一权威闸门，两处各判一次迟早会分叉。
     */
    fun onConfirmDelete() {
        val id = pendingDelete.value ?: return
        pendingDelete.value = null
        viewModelScope.launch { categoryRepository.delete(id) }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}
