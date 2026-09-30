package com.dream.shouna.ui.screen.itemedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.data.repository.ItemFieldPatch
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.StoredItem
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update

/**
 * P-ITEM-EDIT 物品编辑页（别名 / 数量 / FR-14 跳转落点）的状态机 —— **骨架**。
 *
 * 本页同时是详情页「⋯更多」折叠区的落点（P1 §8.1-17：「✏」与「⋯更多」都进本页）。
 *
 * 时间戳口径（P1 §3.5 / §3.4-10）——**这是本页最容易写错的地方**：
 * 改分类 / 别名 / 备注 / 数量**一律不刷新** `last_modified_at`；只有「待归位 / 归位」
 * 这类状态变化才刷新。校对输入（数量为整数 ≥ 1）在保存前拦截，非法值不落库。
 *
 * 被调用方：`ItemEditRoute`
 */
@HiltViewModel
class ItemEditViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val state = MutableStateFlow(ItemEditUiState())

    val uiState: StateFlow<ItemEditUiState> = state.asStateFlow()

    /** FR-12 / FR-44：分类可选列表 = 分类管理页维护的动态列表（不再读常量）。 */
    val categories: StateFlow<List<Category>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /**
     * 骨架期空实现：只记住 `itemId` 与置位 `isLoaded`。
     *
     * TODO(P1-04 ①): 取详情填进各输入项（名称不可改，改的是分类 / 别名 / 数量 / 备注）。
     */
    fun load(itemId: String) {
        state.update { it.copy(itemId = itemId, isLoaded = true) }
    }

    fun onNameChange(value: String) {
        state.update { it.copy(name = value) }
    }

    fun onCategorySelected(categoryId: String?) {
        state.update { it.copy(categoryId = categoryId) }
    }

    /** 别名上限 5、按**归一化值**去重、不允许与名称相同（P1 §8.1-4）——判定全在本层。 */
    fun onAddAlias(alias: String) {
        TODO("P1-04 ②: 归一化去重 + 不与名称相同 + 上限 $MAX_ALIAS_COUNT 的软校验后入草稿")
    }

    fun onRemoveAlias(alias: String) {
        state.update { it.copy(aliases = it.aliases.filterNot { a -> a == alias }) }
    }

    /** 数量：仅整数、≥ 1、默认 1、无单位（`prd/11` Q3）；非整数 / 0 不落库。 */
    fun onQuantityChange(value: String) {
        state.update { it.copy(quantityInput = value) }
    }

    fun onNoteChange(value: String) {
        state.update { it.copy(note = value) }
    }

    /**
     * 保存：**补丁式**写入（只提交被改的字段）。
     *
     * TODO(P1-04 ①): 组装 [ItemFieldPatch] → `itemRepository.updateFields`；
     *                 失败按 FR-10「零弹窗」静默返回，输入不动（`实现约束.md` §4-1）。
     */
    fun onSave() {
        TODO("P1-04 ①: 组装 ItemFieldPatch 并调用 updateFields（校验不过则不落库）")
    }

    companion object {
        /** 单物品别名上限（P1 §8.1-4）；UI 层软校验。 */
        const val MAX_ALIAS_COUNT: Int = 5

        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

data class ItemEditUiState(
    /** 骨架期标志；载入完成后仍由 [ItemEditViewModel.load] 维护。 */
    val isLoaded: Boolean = false,
    val itemId: String = "",
    val name: String = "",
    val categoryId: String? = null,
    /** 别名的草稿列表（保存时才编码回 `alias_blob`）。 */
    val aliases: List<String> = emptyList(),
    /** 数量输入的**原样串**：非法值要能留在框里让用户改，不能先转 Int 丢掉。 */
    val quantityInput: String = DEFAULT_QUANTITY_INPUT,
    val note: String = "",
) {
    /** 数量是否可保存（整数且 ≥ 1，`prd/11` Q3）。 */
    val canSave: Boolean
        get() = isLoaded && (quantityInput.toIntOrNull()?.let { it >= 1 } == true)

    companion object {
        /** 数量默认值（`item.quantity` 默认 1）。 */
        const val DEFAULT_QUANTITY_INPUT: String = "1"
    }
}

/** 详情页折叠区与编辑页共用的展示项；`StoredItem` 是唯一数据来源。 */
internal fun StoredItem.toEditDraft(): ItemEditUiState = ItemEditUiState(
    isLoaded = true,
    itemId = id,
    name = name,
    categoryId = categoryId,
    aliases = aliases,
    quantityInput = quantity.toString(),
    note = note.orEmpty(),
)
