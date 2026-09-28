package com.dream.shouna.ui.screen.itemdetail

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.domain.model.ItemDetail
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
 * FR-26：详情页 +「确认还在」（ARCHITECTURE §2）。
 *
 * 被调用方：ItemDetailRoute（hiltViewModel + load / onConfirmStillHere）
 */
@HiltViewModel
class ItemDetailViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val timeUtil: TimeUtil,
) : ViewModel() {

    private val state = MutableStateFlow(ItemDetailUiState())

    val uiState: StateFlow<ItemDetailUiState> = state.asStateFlow()

    /** 当前展示的物品 id，由 [load] 记录，供「确认还在」复用。 */
    private var loadedItemId: String? = null

    fun load(itemId: String) {
        loadedItemId = itemId
        viewModelScope.launch { refresh(itemId) }
    }

    /** 1 次点击写 `lastConfirmedAt`，UI 立即刷新可见反馈。 */
    fun onConfirmStillHere() {
        val itemId = loadedItemId ?: return
        if (state.value.isConfirming) return
        viewModelScope.launch {
            state.update { it.copy(isConfirming = true) }
            try {
                itemRepository.confirmItem(itemId)
            } catch (_: Throwable) {
                // FR-10 口径的零弹窗延伸：不弹窗，仅结束 loading 状态。
            }
            // 重新取详情 → relativeConfirmText 立即刷新（FR-26 可见反馈）。
            refresh(itemId)
            state.update { it.copy(isConfirming = false) }
        }
    }

    /** 取详情并把「最后确认」文案组装进 UiState（Screen 层不做文案分支）。 */
    private suspend fun refresh(itemId: String) {
        val detail: ItemDetail? = itemRepository.getItemDetail(itemId)
        val item: StoredItem? = detail?.item
        state.update {
            it.copy(
                item = item,
                relativeConfirmText = buildConfirmLine(item?.lastConfirmedAt),
            )
        }
    }

    /**
     * 详情页那一行（§1.2 有意保留的可见反馈）：
     * 未确认 → 「从未确认」；已确认 → 「最后确认：N 个月前」。
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
    val relativeConfirmText: String = "",
    val isConfirming: Boolean = false,
)
