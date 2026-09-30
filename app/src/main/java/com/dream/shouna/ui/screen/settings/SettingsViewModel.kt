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
import kotlinx.coroutines.launch

/**
 * P-SETTINGS 设置页（FR-47）的状态机。
 *
 * 页面职责（P1 §8.1-14）：超期阈值（3 / 6 / 12）、分类管理入口、隐私说明、关于。
 * **不含**「显示完整功能」开关 —— 用户 2026-09-29 裁示不做（P1 §8.1-13）：P0 已裁定不做阈值门控、
 * F2 入口恒显，那个开关是个空操作。
 * **不含**备份 / 恢复块 —— 导入导出已永久废弃（`prd/05` §4.11 第 6 项）。
 *
 * V2 的 AI 设置节（`prd/14` 的 `V2-SEAM-05`）只在本页**留出文档级位置**，本期不建任何 UI 元素。
 *
 * 【阈值为什么在这里只读一次、在统计页却是流】本页是阈值的**唯一写入方**（`onThresholdSelected`），
 * 写成功后本地立即回显，不需要再订阅自己写的那条流；需要「改后即时跟随」的是**消费方**
 * —— `StatsViewModel` 订阅 `observeThresholdMonths()`，因此本页改档后，返回栈上的统计页无需重建
 * 就能让 C-4 清单跟着变。
 *
 * 被调用方：`SettingsRoute`
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
) : ViewModel() {

    private val state = MutableStateFlow(SettingsUiState())

    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    /** 读当前阈值填入 UiState；载入完成前 `thresholdMonths` 保持 null（页面不预选任何一档）。 */
    fun load() {
        viewModelScope.launch {
            val months = configRepository.thresholdMonths()
            state.update { it.copy(isLoaded = true, thresholdMonths = months) }
        }
    }

    /**
     * FR-47：切换超期阈值。非法档位直接丢弃（仓库层另有一道拦截）——
     * 页面本就只给三档，走到这里是调用点错误，不值得弹提示（守 FR-10 零弹窗）。
     */
    fun onThresholdSelected(months: Int) {
        if (months !in THRESHOLD_OPTIONS) return
        viewModelScope.launch {
            if (configRepository.setThresholdMonths(months)) {
                state.update { it.copy(thresholdMonths = months) }
            }
        }
    }

    companion object {
        /** 设置页只给的三档（P1 §0「设置页可改 3 / 6 / 12」）。 */
        val THRESHOLD_OPTIONS: List<Int> = listOf(3, 6, 12)
    }
}

data class SettingsUiState(
    /** 阈值已读回（不是「load 被调过」）；未载入时页面不预选任何一档，避免显示一个猜的值。 */
    val isLoaded: Boolean = false,
    /** 当前超期阈值（月）；null = 未载入。 */
    val thresholdMonths: Int? = null,
)
