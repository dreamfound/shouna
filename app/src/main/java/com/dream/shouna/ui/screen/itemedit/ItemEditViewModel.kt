package com.dream.shouna.ui.screen.itemedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.data.repository.ItemFieldPatch
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.util.TextNormalizer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * P-ITEM-EDIT 物品编辑页（别名 / 数量 / FR-14 跳转落点）的状态机。
 *
 * 本页同时是详情页「⋯更多」折叠区的落点（P1 §8.1-17：「✏」与「⋯更多」都进本页）。
 *
 * 时间戳口径（P1 §3.5 / §3.4-10）——**这是本页最容易写错的地方**：
 * 改分类 / 别名 / 备注 / 数量**一律不刷新** `last_modified_at`；只有「待归位 / 归位」
 * 这类状态变化才刷新。本页四处编辑项都不属于状态变化，因此 `updateFields` 不写任何时间列。
 *
 * 【保存是补丁式的】只提交**相对载入快照发生变化**的字段（`ItemFieldPatch` 里 `null` = 不改）。
 * 这样「用户没碰分类」就不会顺手把分类写一遍，也就不会把并发期间别人改的分类覆盖掉。
 *
 * 【置空与不改的不对称】备注 `null` = 不改（清空须传空串，仓库归一为 `null`）；
 * 分类 `null` = 不改（置为「未分类」须显式 `clearCategory = true`）。两者在本层已分开表达。
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

    /** 保存成功的信号：Route 收到后关闭本页（本 VM 不持有 NavController）。 */
    private val saved = MutableSharedFlow<Unit>()

    val savedEvents: SharedFlow<Unit> = saved.asSharedFlow()

    /** FR-12 / FR-44：分类可选列表 = 分类管理页维护的动态列表（不再读常量）。 */
    val categories: StateFlow<List<Category>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /**
     * 载入时的原始快照 —— 补丁 diff 的基准。**不放进 UiState**：
     * 它是「上次落库的状态」，不是「界面上正在编辑的值」，混在一起会让输入项分不清哪份是本地的。
     */
    private var loaded: StoredItem? = null

    /** P1-04 ①：取详情填进各输入项（名称不可改，改的是分类 / 别名 / 数量 / 备注）。 */
    fun load(itemId: String) {
        // 只记 id；`isLoaded` 由「拿到详情」置位 —— 载入失败（并发删除）时页面停在未载入态，
        // 不会出现「空表单 + 可保存」这种按下去毫无反应的中间态。
        state.update { it.copy(itemId = itemId) }
        viewModelScope.launch {
            val detail = itemRepository.getItemDetail(itemId) ?: return@launch
            loaded = detail.item
            state.update { detail.item.toEditDraft() }
        }
    }

    fun onCategorySelected(categoryId: String?) {
        state.update { it.copy(categoryId = categoryId) }
    }

    /**
     * 别名上限 5、按**归一化值**去重、不允许与名称相同（P1 §8.1-4）——判定全在本层。
     *
     * 全部不满足时**静默丢弃**（与 FR-10 的「零弹窗」一致）：重复 / 同名 / 超限是输入约束，
     * 不是错误；弹一个对话框打断连续录入不划算。
     */
    fun onAddAlias(alias: String) {
        val trimmed = alias.trim()
        if (trimmed.isEmpty()) return
        val normalized = TextNormalizer.normalize(trimmed)
        if (normalized.isEmpty()) return

        state.update { current ->
            when {
                current.aliases.size >= MAX_ALIAS_COUNT -> current
                // 别名与名称同档参与检索（SearchScorer 的 NAME 档）→ 同名别名毫无信息量，直接不要。
                TextNormalizer.normalize(current.name) == normalized -> current
                current.aliases.any { TextNormalizer.normalize(it) == normalized } -> current
                else -> current.copy(aliases = current.aliases + trimmed)
            }
        }
    }

    fun onRemoveAlias(alias: String) {
        state.update { it.copy(aliases = it.aliases.filterNot { a -> a == alias }) }
    }

    /** 数量：仅整数、≥ 1、默认 1、无单位（`prd/11` Q3）；原样留在框里，非法值只是**不可保存**。 */
    fun onQuantityChange(value: String) {
        state.update { it.copy(quantityInput = value) }
    }

    fun onNoteChange(value: String) {
        state.update { it.copy(note = value) }
    }

    /**
     * 保存：**补丁式**写入（只提交被改的字段）→ 成功后发 [savedEvents] 让 Route 关闭本页。
     *
     * 失败（未命中 / 抛异常）沿用 FR-10 的「零弹窗」取向：不弹错误框，**保持输入原样**留在本页，
     * 由用户重试（`实现约束.md` §4-1）。
     */
    fun onSave() {
        val current = state.value
        val original = loaded ?: return
        if (!current.canSave) return

        val quantity = current.quantityInput.toIntOrNull()?.takeIf { it >= 1 } ?: return
        val patch = buildPatch(original = original, current = current, quantity = quantity)

        viewModelScope.launch {
            state.update { it.copy(isSaving = true) }
            val updated = try {
                itemRepository.updateFields(current.itemId, patch)
            } catch (_: Throwable) {
                null
            }
            state.update { it.copy(isSaving = false) }
            // 未命中（并发删除等）也不给弹窗；留在本页，输入不动。
            if (updated != null) {
                loaded = updated
                saved.emit(Unit)
            }
        }
    }

    /**
     * 差异 → [ItemFieldPatch]。四项各自判断「是否真的变了」：
     * 没变则留 `null`（= 不改这一列），避免把并发期间别人改过的值又写回去。
     */
    private fun buildPatch(
        original: StoredItem,
        current: ItemEditUiState,
        quantity: Int,
    ): ItemFieldPatch {
        val categoryChanged = current.categoryId != original.categoryId
        val aliasesChanged = current.aliases != original.aliases
        val quantityChanged = quantity != original.quantity

        // 备注比较用**归一化后的落库形态**（空串 / 纯空白都算「清空」），与仓库归一口径一致。
        val noteValue = current.note.trim().ifBlank { null }
        val noteChanged = noteValue != original.note

        return ItemFieldPatch(
            // 置空与不改互斥：非空的新分类走 categoryId，置「未分类」走 clearCategory。
            categoryId = current.categoryId.takeIf { categoryChanged && it != null },
            clearCategory = categoryChanged && current.categoryId == null,
            aliases = current.aliases.takeIf { aliasesChanged },
            quantity = quantity.takeIf { quantityChanged },
            // 清空备注必须传「空串」而不是 null —— null 在这个载荷里表示「不改」。
            note = current.note.takeIf { noteChanged },
        )
    }

    companion object {
        /** 单物品别名上限（P1 §8.1-4）；UI 层软校验。 */
        const val MAX_ALIAS_COUNT: Int = 5

        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

data class ItemEditUiState(
    /** **详情已载入**（不是「load 被调过」）：载入失败时保持 false，页面据此停在未载入态。 */
    val isLoaded: Boolean = false,
    val itemId: String = "",
    /** 名称**只读**（本页不可改；改名的入口不在 P1 范围）。 */
    val name: String = "",
    val categoryId: String? = null,
    /** 别名的草稿列表（保存时才编码回 `alias_blob`）。 */
    val aliases: List<String> = emptyList(),
    /** 数量输入的**原样串**：非法值要能留在框里让用户改，不能先转 Int 丢掉。 */
    val quantityInput: String = DEFAULT_QUANTITY_INPUT,
    val note: String = "",
    /** 保存进行中：用于禁用按钮，防止重复提交（并非错误态）。 */
    val isSaving: Boolean = false,
) {
    /** 数量是否合法（整数且 ≥ 1，`prd/11` Q3）—— 与「能否保存」分开：保存中不改变数量合法性。 */
    val isQuantityValid: Boolean
        get() = quantityInput.toIntOrNull()?.let { it >= 1 } == true

    /** 是否可保存：已载入 ∧ 未在保存中 ∧ 数量合法。 */
    val canSave: Boolean
        get() = isLoaded && !isSaving && isQuantityValid

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
