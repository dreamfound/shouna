package com.dream.shouna.ui.screen.itemdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ConfigRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.domain.model.ItemDetail
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * FR-26 / FR-25 / FR-27 / FR-02：详情页状态机。
 *
 * 三个动作的语义差别（对应 §3.5 时间戳矩阵）：
 * - 「✓ 还在」→ `confirmItem`：**只**写 `lastConfirmedAt`（不是变动）。
 * - 「✕ 不在了」→ `setStatus(GONE)`：写 `status` + `lastModifiedAt`（是变动，不是确认）。
 * - 「恢复」→ `restore`：同上，回到 `IN_STORAGE`。
 *
 * 文案组装全部在本层完成（Screen 不持有 TimeUtil / ConfigRepository，ARCHITECTURE §2）。
 *
 * 被调用方：ItemDetailRoute（hiltViewModel + load / 三个动作）
 */
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val configRepository: ConfigRepository,
    private val timeUtil: TimeUtil,
) : ViewModel() {

    private val state = MutableStateFlow(ItemDetailUiState())

    val uiState: StateFlow<ItemDetailUiState> = state.asStateFlow()

    /** 当前展示的物品 id，由 [load] 记录，供三个动作复用。 */
    private var loadedItemId: String? = null

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

    /** 三个动作共用的执行壳：串行化（`isBusy` 挡重复点击）→ 执行 → 重取详情刷新文案。 */
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

    /** 取详情并把两行时间文案、位置路径、超期判定组装进 UiState。 */
    private suspend fun refresh(itemId: String) {
        val detail: ItemDetail? = itemRepository.getItemDetail(itemId)
        val item: StoredItem? = detail?.item
        val thresholdMonths = configRepository.thresholdMonths()

        state.update {
            it.copy(
                item = item,
                locationPath = detail?.locationPath.orEmpty(),
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
) {
    /** FR-25：已标记「不在了」→ 按钮换成「恢复」。 */
    val isGone: Boolean get() = item?.status == ItemStatus.GONE
}
