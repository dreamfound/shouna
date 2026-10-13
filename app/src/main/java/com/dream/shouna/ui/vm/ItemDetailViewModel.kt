package com.dream.shouna.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ConfigRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.domain.model.ItemDetail
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * FR-26 / FR-25 / FR-27 / FR-02：详情页状态机。
 *
 * 四个动作的语义差别（对应 §3.5 时间戳矩阵）：
 * - 「✓ 还在」→ `confirmItem`：**只**写 `lastConfirmedAt`（不是变动）。
 * - 「✕ 不在了」→ `setStatus(GONE)`：写 `status` + `lastModifiedAt`（是变动，不是确认）。
 * - 「恢复」→ `restore`：同上，回到 `IN_STORAGE`。
 * - P1-04 新增「待归位 / 归位」「移动到…」：状态变化与位置变化都**刷新 `lastModifiedAt`**，
 *   且都不是一次确认（`lastConfirmedAt` 不动）。
 *
 * 文案组装全部在本层完成（Screen 不持有 TimeUtil / ConfigRepository，docs/实现约束.md）；
 * 「⋯更多」折叠区要用位置树来做「移动到…」，树由本层提供，弹层的开合由页面持有。
 *
 * FR-02（2026-10-13）：位置面包屑由 `combine(位置树, 本条 locationId)` **实时派生**（见 `init`）——
 * 位置被**移动 / 改名 / 合并**（都发生在位置浏览页，本条物品一个字节都没变）后路径当场跟随，
 * 不必重进页面。与 `ItemEditViewModel` 同一手法，两页口径一致。
 *
 * 被调用方：ItemDetailRoute（hiltViewModel + load / 各动作）
 */
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val locationRepository: LocationRepository,
    private val configRepository: ConfigRepository,
    private val timeUtil: TimeUtil,
) : ViewModel() {

    private val state = MutableStateFlow(ItemDetailUiState())

    val uiState: StateFlow<ItemDetailUiState> = state.asStateFlow()

    /** P1-04：「移动到…」的目标选择数据源（与浏览页 / 统计页同一棵派生树）。 */
    val locationTree: StateFlow<List<LocationTreeRow>> = locationRepository.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /** 当前展示的物品 id，由 [load] 记录，供各动作复用。 */
    private var loadedItemId: String? = null

    init {
        // FR-02：面包屑文本 = **本条物品的位置 id** × **位置树** 两因派生，两个因**都**进 combine
        // （`docs/实现约束.md` §3-2：展示文本的因子中不落库的项必须显式进 combine）。
        //
        // 【为什么不能只靠 [refresh]】`refresh` 是一次性读：只在 `load()` 与各动作之后跑。
        // 位置被移动 / 改名 / 合并时**本条物品没有任何一列发生变化**，`refresh` 不会被触发，
        // 面包屑就会停在旧路径上（详情页与编辑页的观感不一致）。改成订阅后，位置一变文本即跟随。
        //
        // 回写仍只认「当前展示的那条」：combine 的一次发射可能晚于 `load()` / 动作后的
        // `state.update`，别用旧位置的文本盖掉新的。
        viewModelScope.launch {
            combine(
                locationRepository.observeTree(),
                state.map { it.item?.locationId.orEmpty() }.distinctUntilChanged(),
            ) { tree, locationId -> locationId to pathOf(tree, locationId) }
                .collect { (locationId, pathText) ->
                    state.update { current ->
                        if (current.item?.locationId.orEmpty() == locationId) {
                            current.copy(locationPath = pathText)
                        } else {
                            current
                        }
                    }
                }
        }
    }

    fun load(itemId: String) {
        loadedItemId = itemId
        viewModelScope.launch { refresh(itemId) }
    }

    /** FR-26：1 次点击写 `lastConfirmedAt`，UI 立即刷新可见反馈。 */
    fun onConfirmStillHere() {
        mutate { itemId -> itemRepository.confirmItem(itemId) }
    }

    /** FR-25：标记「不在了」——写 `status` 并刷新 `lastModifiedAt`。 */
    fun onMarkGone() {
        mutate { itemId -> itemRepository.setStatus(itemId, ItemStatus.GONE) }
    }

    /** FR-25：从「不在了」恢复为「在存放中」。 */
    fun onRestore() {
        mutate { itemId -> itemRepository.restore(itemId) }
    }

    // --- P1-04：折叠区的三个动作（P1 §4 P1-04 ⑥）-------------------------------------

    /**
     * 标记为「待归位」——它会进入归纳统计的 C-5 清单（`PRD.md` §8.2.1）。
     * 状态变化 ⇒ 刷新 `lastModifiedAt`（§3.5 矩阵「改为待归位」行）。
     */
    fun onMarkToBePutBack() {
        mutate { itemId -> itemRepository.setStatus(itemId, ItemStatus.TO_BE_PUT_BACK) }
    }

    /** 从「待归位」归位（**不换位置**，只改状态）；若还要换位置，用 C-5 的「归位到…」。 */
    fun onPutBackToStorage() {
        mutate { itemId -> itemRepository.setStatus(itemId, ItemStatus.IN_STORAGE) }
    }

    /**
     * 移动到另一个位置 —— 位置变化属「变动」：刷新 `lastModifiedAt`，
     * **不动** `status`（移动不是状态变化），**不动** `lastConfirmedAt`（换地方不算确认）。
     */
    fun onMoveTo(locationId: String) {
        mutate { itemId -> itemRepository.moveItem(itemId, locationId) }
    }

    /**
     * FR-16：移除照片（**只清库里的文件名**，图片文件由页面经 `PhotoStore` 删除）。
     *
     * 不写任何时间列：照片既不是「位置」也不是「状态」，按 §3.5 矩阵不改 `lastModifiedAt`，
     * 更不是一次确认 —— 因此列表排序与超期判定都不受影响。
     *
     * 【V2-FR-11（2026-10-09）：文件不是无条件删的】页面必须先问 [isLastPhotoReference]：
     * 多条记录可能共用同一个照片文件，只有本条是最后一个引用者时才真删文件。
     */
    fun onRemovePhoto() {
        mutate { itemId -> itemRepository.setPhoto(itemId, null) }
    }

    /**
     * V2-FR-11（2026-10-09）：**本条是不是这张照片的最后一个引用者** —— 决定页面要不要删文件。
     *
     * 【为什么不能无条件删】拍照 AI 录入一次可能识别出多件，这些记录**共用同一个照片文件**
     * （`item.photo_path` 存文件名）。删掉 A 的照片时若直接删文件，B 的照片立刻变成破图。
     *
     * 【为什么必须在清库之前问】计数含自己：`<= 1` 才等于「清掉本条之后没人再用」。
     * 调用方（`ItemDetailScreen`）的顺序固定为 **取定名字 → 问这里 → 清库 → 才删文件**。
     *
     * 没照片（`photoName == null`）返回 false：没有东西要删。
     */
    suspend fun isLastPhotoReference(): Boolean {
        val photoName = state.value.photoName ?: return false
        return itemRepository.countByPhotoName(photoName) <= 1
    }

    /** 各动作共用的执行壳：串行化（`isBusy` 挡重复点击）→ 执行 → 重取详情刷新文案。 */
    private fun mutate(action: suspend (String) -> StoredItem?) {
        val itemId = loadedItemId ?: return
        if (state.value.isBusy) return
        viewModelScope.launch {
            state.update { it.copy(isBusy = true) }
            try {
                action(itemId)
            } catch (_: Throwable) {
                // 沿用 FR-10 的「零弹窗」取向：不弹错误框，仅结束 loading 由用户重试。
            }
            refresh(itemId)
            state.update { it.copy(isBusy = false) }
        }
    }

    /**
     * 取详情并把两行时间文案、位置路径、折叠区字段与超期判定组装进 UiState。
     *
     * 【位置路径为什么这里也写一次】它在这里给的是**首帧值**（与详情同一次读出，页面不会先闪
     * 一个空面包屑）；此后由 `init` 里那条 `combine(位置树, locationId)` 保持最新 ——
     * 位置被移动 / 改名后本条物品无列变化、本函数不会被触发，靠的就是那条订阅。
     */
    private suspend fun refresh(itemId: String) {
        val detail: ItemDetail? = itemRepository.getItemDetail(itemId)
        val item: StoredItem? = detail?.item
        val thresholdMonths = configRepository.thresholdMonths()

        state.update {
            it.copy(
                item = item,
                locationPath = detail?.locationPath.orEmpty(),
                categoryName = detail?.categoryName,
                relativeConfirmText = buildConfirmLine(item?.lastConfirmedAt),
                relativeModifiedText = item?.lastModifiedAt
                    ?.let { at -> timeUtil.relativeText(at) }
                    .orEmpty(),
                isOverdue = item != null && timeUtil.isOverdue(item.lastConfirmedAt, thresholdMonths),
            )
        }
    }

    /**
     * 「最后确认」那一行：未确认 → 「从未确认」；已确认 → 「最后确认：N 个月前」。
     */
    private fun buildConfirmLine(lastConfirmedAt: Long?): String =
        if (lastConfirmedAt == null) {
            TimeUtil.NEVER_CONFIRMED
        } else {
            "最后确认：${timeUtil.relativeConfirmText(lastConfirmedAt)}"
        }

    /** 在展平树里取某位置的完整路径文本；树里找不到（如物品还未载入）= 空串。 */
    private fun pathOf(tree: List<LocationTreeRow>, locationId: String): String =
        tree.firstOrNull { it.location.id == locationId }?.pathText.orEmpty()

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

data class ItemDetailUiState(
    val item: StoredItem? = null,
    /** FR-02：位置面包屑文本；位置缺失时为空串。 */
    val locationPath: String = "",
    /** 「最后确认：…」或「从未确认」。 */
    val relativeConfirmText: String = "",
    /** 「最后变动：…」；物品加载完成前为空串。 */
    val relativeModifiedText: String = "",
    /** FR-27：超期（含「从未确认」）→ 显示醒目标记。 */
    val isOverdue: Boolean = false,
    /** 任一动作执行中；用于禁用按钮防重复点击。 */
    val isBusy: Boolean = false,
    /** P1-04 折叠区：分类名；未分类 = null（展示为「未分类」）。 */
    val categoryName: String? = null,
) {
    /** FR-25：已标记「不在了」→ 按钮换成「恢复」。 */
    val isGone: Boolean get() = item?.status == ItemStatus.GONE

    /** P1-04：已标记「待归位」→ 折叠区给的是「归位」而不是「标记待归位」。 */
    val isToBePutBack: Boolean get() = item?.status == ItemStatus.TO_BE_PUT_BACK

    /** P1-04：折叠区展示的别名（来自 `item.aliases`；空列表 = 无别名）。 */
    val aliases: List<String> get() = item?.aliases.orEmpty()

    /** P1-04：折叠区展示的数量。 */
    val quantity: Int get() = item?.quantity ?: 1

    /** FR-16：本条记录的照片文件名；null = 没拍照。 */
    val photoName: String? get() = item?.photoPath

    /** 物品信息区展示的备注（2026-10-13 起上移到常显区）；空 / null = 该行整块不渲染。 */
    val note: String? get() = item?.note
}
