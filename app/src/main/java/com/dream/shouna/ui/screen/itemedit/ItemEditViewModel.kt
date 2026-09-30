package com.dream.shouna.ui.screen.itemedit

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.local.TransactionRunner
import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.data.repository.ItemFieldPatch
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.ItemDetail
import com.dream.shouna.domain.model.LocationTreeRow
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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * P-ITEM-EDIT 物品编辑页（名称 / 照片 / 位置 / 分类 / 别名 / 数量 / 备注）的状态机。
 *
 * 本页同时是详情页「⋯更多」折叠区的落点（P1 §8.1-17：「✏」与「⋯更多」都进本页）。
 *
 * 【P1-07 补齐三项】此前本页只改「分类 / 别名 / 数量 / 备注」，名称只读、照片与位置要跳出本页
 * 去录入页 / 详情页改。P1-07 把这三项并入本页 —— 它们的**写入能力早已存在**
 * （`setPhoto` / `moveItem`），补齐主要是复用编排，无表结构变更。
 *
 * 【状态类动作仍在详情页】「待归位 / 不在了 / 恢复」不搬进来：它们按 §3.5 矩阵要**刷新
 * `last_modified_at`**，而本页七项编辑项一律**不刷新**任何时间列。两套时间戳语义混在一页，
 * 日后必然有人把「编辑保存」当成一次「变动」。
 *
 * 时间戳口径（P1 §3.5 / §3.4-10）——**这是本页最容易写错的地方**：
 * 改名称 / 分类 / 别名 / 备注 / 数量 / 照片一律不刷新 `last_modified_at`；只有位置变化才刷新。
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
    private val locationRepository: LocationRepository,
    private val transactionRunner: TransactionRunner,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val state = MutableStateFlow(ItemEditUiState())

    val uiState: StateFlow<ItemEditUiState> = state.asStateFlow()

    /**
     * 保存成功的信号：载荷带出**需要删除的旧照片文件名**（null = 不必删）。
     *
     * 【为什么由 Route 删文件】图片的建 / 删一律在 UI 层完成（`PhotoStore` 要 `Context`），
     * VM 保持不依赖 `Context`（与 `QuickAddViewModel` / `ItemDetailViewModel` 同一取向）。
     * Route 收到信号后先删文件再关页，顺序不可颠倒。
     */
    private val saved = MutableSharedFlow<ItemEditSaved>()

    val savedEvents: SharedFlow<ItemEditSaved> = saved.asSharedFlow()

    /** FR-12 / FR-44：分类可选列表 = 分类管理页维护的动态列表（不再读常量）。 */
    val categories: StateFlow<List<Category>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /** 「更换位置」弹层的数据源（与浏览页 / 详情页 / 统计页同一棵派生树）。 */
    val locationTree: StateFlow<List<LocationTreeRow>> = locationRepository.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /**
     * 载入时的原始快照 —— 补丁 diff 的基准。**不放进 UiState**：
     * 它是「上次落库的状态」，不是「界面上正在编辑的值」，混在一起会让输入项分不清哪份是本地的。
     */
    private var loaded: StoredItem? = null

    init {
        // 位置条文本 = 已选位置在树里的**完整面包屑路径**，与 `QuickAddViewModel` 同一手法。
        //
        // 【为什么必须订阅两个来源】Compose 侧只认 UiState 里的一个字符串，而它有两个因：
        // ① **树**变了（改名 / 新建 / 祖先被移走）→ 路径文本要跟着变；
        // ② **选中项**变了（用户在弹层里点了另一个位置）→ 文本要立刻换。
        // 只订阅树是不够的：点选位置**不产生任何 DB 变更**，Room 的 Flow 不会重发，
        // 文本就会停在上一个值上（这正是位置条文案滞留 bug 的成因）。
        viewModelScope.launch {
            combine(
                locationRepository.observeTree(),
                state.map { it.locationId }.distinctUntilChanged(),
            ) { tree, selectedId -> selectedId to pathOf(tree, selectedId) }
                .collect { (selectedId, pathText) ->
                    // 只在这条结果仍对应「当前选中」时回写：combine 的一次发射可能晚于
                    // onLocationSelected 的原子写，别用旧 id 算出的文本盖掉新的。
                    state.update { current ->
                        if (current.locationId == selectedId) {
                            current.copy(locationPath = pathText)
                        } else {
                            current
                        }
                    }
                }
        }
    }

    /** P1-04 ① + P1-07：取详情填进各输入项（名称、照片、位置三项 P1-07 起可改）。 */
    fun load(itemId: String) {
        // 只记 id；`isLoaded` 由「拿到详情」置位 —— 载入失败（并发删除）时页面停在未载入态，
        // 不会出现「空表单 + 可保存」这种按下去毫无反应的中间态。
        state.update { it.copy(itemId = itemId) }
        viewModelScope.launch {
            val detail = itemRepository.getItemDetail(itemId) ?: return@launch
            loaded = detail.item
            state.update { detail.toEditDraft() }
        }
    }

    fun onNameChange(value: String) {
        state.update { it.copy(nameInput = value) }
    }

    fun onCategorySelected(categoryId: String?) {
        state.update { it.copy(categoryId = categoryId) }
    }

    /**
     * 别名上限 5、按**归一化值**去重、不允许与名称相同（P1 §8.1-4）——判定全在本层。
     *
     * 全部不满足时**静默丢弃**（与 FR-10 的「零弹窗」一致）：重复 / 同名 / 超限是输入约束，
     * 不是错误；弹一个对话框打断连续录入不划算。
     *
     * P1-07 起比较的是**名称输入框的当前值**（名称已可编辑），不是载入时的旧名。
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
                TextNormalizer.normalize(current.nameInput) == normalized -> current
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

    /** FR-16：照片文件名变化（拍照 / 相册 / 移除）。文件本体的建删由 UI 层组件负责。 */
    fun onPhotoChanged(photoName: String?) {
        state.update { it.copy(photoName = photoName) }
    }

    /**
     * P1-07：在位置树里选中一个新位置（弹层里的树行）。
     *
     * 【id 与路径必须同一次落定】点选位置**不产生任何 DB 变更**，指望树 Flow 补文本会滞后
     * 甚至永远补不上（首帧没赶上树时退化成空串）。见 `QuickAddViewModel.selectLocation` 的同类说明。
     */
    fun onLocationSelected(locationId: String) {
        state.update {
            it.copy(locationId = locationId, locationPath = pathOf(locationTree.value, locationId))
        }
    }

    /**
     * 保存：**补丁式**写入 → 照片 → 位置，**同事务**；成功后发 [savedEvents] 让 Route 关闭本页。
     *
     * 【为什么要包事务】名称/分类/别名/数量/备注一条 UPDATE，照片一条，位置一条，最多 3 条语句。
     * 不加事务会半生效（名字改了、位置没改），而用户在界面上完全看不出来是哪一步没成功。
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

        val newPhoto = current.photoName
        val photoChanged = newPhoto != original.photoPath
        val locationChanged = current.locationId != original.locationId

        viewModelScope.launch {
            state.update { it.copy(isSaving = true) }
            val updated = try {
                transactionRunner.run {
                    var result = itemRepository.updateFields(current.itemId, patch)
                    if (result != null && photoChanged) {
                        result = itemRepository.setPhoto(current.itemId, newPhoto)
                    }
                    if (result != null && locationChanged) {
                        result = itemRepository.moveItem(current.itemId, current.locationId)
                    }
                    result
                }
            } catch (_: Throwable) {
                null
            }
            state.update { it.copy(isSaving = false) }
            // 未命中（并发删除等）也不给弹窗；留在本页，输入不动。
            if (updated != null) {
                loaded = updated
                // 只有真的换了 / 移除了照片才有旧文件要删；保存成功之前一律不删（见 PhotoInputRow 注释）。
                saved.emit(ItemEditSaved(obsoletePhotoName = original.photoPath.takeIf { photoChanged }))
            }
        }
    }

    /** 在展平树里取某位置的完整路径文本；树里找不到 = 空串。 */
    private fun pathOf(tree: List<LocationTreeRow>, locationId: String): String =
        tree.firstOrNull { it.location.id == locationId }?.pathText.orEmpty()

    /**
     * 差异 → [ItemFieldPatch]。七项各自判断「是否真的变了」：
     * 没变则留 `null`（= 不改这一列），避免把并发期间别人改过的值又写回去。
     */
    private fun buildPatch(
        original: StoredItem,
        current: ItemEditUiState,
        quantity: Int,
    ): ItemFieldPatch {
        val newName = current.nameInput.trim()
        val nameChanged = newName != original.name

        // 改名后若某别名与新名称归一化后相同，**静默剔除**（与 onAddAlias 拒绝同名同一口径）：
        // 同名别名毫无信息量，还会让「改名」这件事在 chips 上留下一个看起来没消掉的旧名。
        val normalizedNewName = TextNormalizer.normalize(newName)
        val aliasesAfterRename = current.aliases
            .filterNot { TextNormalizer.normalize(it) == normalizedNewName }
        val aliasesChanged = aliasesAfterRename != original.aliases

        val categoryChanged = current.categoryId != original.categoryId
        val quantityChanged = quantity != original.quantity

        // 备注比较用**归一化后的落库形态**（空串 / 纯空白都算「清空」），与仓库归一口径一致。
        val noteValue = current.note.trim().ifBlank { null }
        val noteChanged = noteValue != original.note

        return ItemFieldPatch(
            // 名称必填：空白串不进补丁（仓库层也会忽略空白名，这里只是不白跑一次 UPDATE）。
            name = newName.takeIf { nameChanged && it.isNotEmpty() },
            // 置空与不改互斥：非空的新分类走 categoryId，置「未分类」走 clearCategory。
            categoryId = current.categoryId.takeIf { categoryChanged && it != null },
            clearCategory = categoryChanged && current.categoryId == null,
            aliases = aliasesAfterRename.takeIf { aliasesChanged },
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

/** 保存成功的信号载荷：[obsoletePhotoName] = 需要删除的旧照片文件（null = 无）。 */
data class ItemEditSaved(val obsoletePhotoName: String?)

data class ItemEditUiState(
    /** **详情已载入**（不是「load 被调过」）：载入失败时保持 false，页面据此停在未载入态。 */
    val isLoaded: Boolean = false,
    val itemId: String = "",
    /** 名称（P1-07 起可编辑）。空白串不可保存 —— 名称是必填项。 */
    val nameInput: String = "",
    val categoryId: String? = null,
    /** 别名的草稿列表（保存时才编码回 `alias_blob`）。 */
    val aliases: List<String> = emptyList(),
    /** 数量输入的**原样串**：非法值要能留在框里让用户改，不能先转 Int 丢掉。 */
    val quantityInput: String = DEFAULT_QUANTITY_INPUT,
    val note: String = "",
    /** FR-16：本条记录的照片文件名（null = 没拍照）。文件本体的建删在 UI 层。 */
    val photoName: String? = null,
    /** 位置 id。位置是**必填**（ARCHITECTURE-P0 §0）→ 载入成功后恒非空。 */
    val locationId: String = "",
    /**
     * 已选位置的**完整面包屑路径**（`家 › 储物间 › 纸箱-07`）；与 [locationId] 恒同步。
     *
     * 页面直接把这个路径铺在位置条上，因此**不需要**「找不到路径时显示什么」的兜底文案
     * （P1 的口径：「已选」这类占位词不许出现在位置条上）。
     */
    val locationPath: String = "",
    /** 保存进行中：用于禁用按钮，防止重复提交（并非错误态）。 */
    val isSaving: Boolean = false,
) {
    /** 数量是否合法（整数且 ≥ 1，`prd/11` Q3）—— 与「能否保存」分开：保存中不改变数量合法性。 */
    val isQuantityValid: Boolean
        get() = quantityInput.toIntOrNull()?.let { it >= 1 } == true

    /** 名称是否合法：非空白。 */
    val isNameValid: Boolean
        get() = nameInput.isNotBlank()

    /** 是否可保存：已载入 ∧ 未在保存中 ∧ 名称非空 ∧ 数量合法 ∧ 位置已选。 */
    val canSave: Boolean
        get() = isLoaded && !isSaving && isNameValid && isQuantityValid && locationId.isNotEmpty()

    companion object {
        /** 数量默认值（`item.quantity` 默认 1）。 */
        const val DEFAULT_QUANTITY_INPUT: String = "1"
    }
}

/**
 * 详情 → 编辑草稿。位置路径取 `ItemDetail.locationPath`（详情层已按父链拼好），
 * 后续由树 Flow 持续校正（改名 / 祖先移动即时跟随）。
 */
internal fun ItemDetail.toEditDraft(): ItemEditUiState = ItemEditUiState(
    isLoaded = true,
    itemId = item.id,
    nameInput = item.name,
    categoryId = item.categoryId,
    aliases = item.aliases,
    quantityInput = item.quantity.toString(),
    note = item.note.orEmpty(),
    photoName = item.photoPath,
    locationId = item.locationId,
    locationPath = locationPath,
)
