package com.dream.shouna.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ConfigRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * P-SETTINGS 设置页（FR-47）的状态机 —— **骨架**。
 *
 * 页面职责（P1 §8.1-14）：超期阈值（3 / 6 / 12）、分类管理入口、隐私说明、关于。
 * **不含**「显示完整功能」开关 —— 用户 2026-09-29 裁示不做（P1 §8.1-13）：P0 已裁定不做阈值门控、
 * F2 入口恒显，那个开关是个空操作。
 * **不含**备份 / 恢复块 —— 导入导出已永久废弃（`prd/05` §4.11 第 6 项）。
 *
 * V2 的 AI 设置节（`prd/14` 的 `V2-SEAM-05`）只在本页**留出文档级位置**，本期不建任何 UI 元素。
 *
 * 被调用方：`SettingsRoute`
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val state = MutableStateFlow(SettingsUiState())

    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    /**
     * 骨架期空实现：只置位 `isLoaded`。
     *
     * TODO(P1-06 ③): 读 `configRepository.thresholdMonths()` 填入 `thresholdMonths`。
     */
    fun load() {
        state.update { it.copy(isLoaded = true) }
    }

    /** FR-47：切换超期阈值。改后 C-4 清单**即时变化**（阈值是一条流，不缓存）。 */
    fun onThresholdSelected(months: Int) {
        TODO("P1-06 ③: 写 threshold_months（仅接受 THRESHOLD_OPTIONS 内的档位）")
    }

    companion object {
        /** 设置页只给的三档（P1 §0「设置页可改 3 / 6 / 12」）。 */
        val THRESHOLD_OPTIONS: List<Int> = listOf(3, 6, 12)
    }
}

data class SettingsUiState(
    /** 骨架期标志；载入完成后仍由 [SettingsViewModel.load] 维护。 */
    val isLoaded: Boolean = false,
    /** 当前超期阈值（月）；未载入 = null，页面不预选任何一档，避免显示一个猜的值。 */
    val thresholdMonths: Int? = null,
)
