package com.dream.shouna.ui.screen.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.AiSettingsRepository
import com.dream.shouna.data.repository.ConfigRepository
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
 * P-SETTINGS 设置页（FR-47）+ **V2 AI 节**（`V2-SEAM-05`）的状态机。
 *
 * 页面职责（P1 §8.1-14）：超期阈值（3 / 6 / 12）、分类管理入口、**AI 录入**、隐私说明、关于。
 * **不含**「显示完整功能」开关 —— 用户 2026-09-29 裁示不做（P1 §8.1-13）：P0 已裁定不做阈值门控、
 * F2 入口恒显，那个开关是个空操作。
 * **不含**备份 / 恢复块 —— 导入导出已永久废弃（`prd/05` §4.11 第 6 项）。
 *
 * 【阈值为什么在这里只读一次、在统计页却是流】本页是阈值的**唯一写入方**（`onThresholdSelected`），
 * 写成功后本地立即回显，不需要再订阅自己写的那条流；需要「改后即时跟随」的是**消费方**
 * —— `StatsViewModel` 订阅 `observeThresholdMonths()`，因此本页改档后，返回栈上的统计页无需重建
 * 就能让 C-4 清单跟着变。
 *
 * 【AI 的开关与用量为什么是流、密钥为什么只读一次】开关与用量有**第二个消费方**：录入页的 AI 按钮
 * 可见性订阅同一条 `observeEnabled()`，两页必须读到同一真源。密钥则只有本页用（录入页拿到的是
 * 闸门内部的明文副本），故按需读一次即可。
 *
 * 被调用方：`SettingsRoute`
 */
@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val configRepository: ConfigRepository,
    private val aiSettings: AiSettingsRepository,
) : ViewModel() {

    private val state = MutableStateFlow(SettingsUiState())

    val uiState: StateFlow<SettingsUiState> = state.asStateFlow()

    /** AI 总开关（出厂默认关，`V2-FR-06`）。缺省 / 脏值一律按关（仓库层口径）。 */
    val aiEnabled: StateFlow<Boolean> = aiSettings.observeEnabled()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), false)

    /** 本周期用量（次数）。**调用成功才 +1**，失败不计（`V2-COST-04`）。 */
    val aiUsageCount: StateFlow<Int> = aiSettings.observeUsageCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), 0)

    /** 本周期累计消耗的 token **合计**；供应商没回报的那些次不计，失败不计（`V2-COST-04`）。 */
    val aiUsageTokens: StateFlow<Int> = aiSettings.observeUsageTokens()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), 0)

    /** 读当前阈值填入 UiState；载入完成前 `thresholdMonths` 保持 null（页面不预选任何一档）。 */
    fun load() {
        viewModelScope.launch {
            val months = configRepository.thresholdMonths()
            val hasKey = aiSettings.hasApiKey()
            state.update {
                it.copy(isLoaded = true, thresholdMonths = months, aiHasApiKey = hasKey)
            }
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

    /**
     * `V2-FR-06`：AI 总开关。
     *
     * **关闭不删密钥**（`docs/code-map/02-数据与领域.md` AI-03 子步骤②）：再开启时不必重填。
     */
    fun onAiEnabledChanged(enabled: Boolean) {
        viewModelScope.launch { aiSettings.setEnabled(enabled) }
    }

    /**
     * `V2-COST-05`：写入用户自备密钥。
     *
     * **只写入、不回显**（AI-03 子步骤①）：明文交给仓库加密落盘后，本 VM 不留存、UiState 不带它，
     * 页面上只显示「已配置 / 尚未配置」。空串直接丢弃 —— 「清空密钥」不是本轮提供的意图
     * （要换 Key 就再填一次，覆盖写入）。
     *
     * @return 无需返回值：调用方（页面）在提交后**立即清空输入框**，避免明文在界面上停留。
     */
    fun onApiKeySubmitted(plain: String) {
        val trimmed = plain.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            if (aiSettings.setApiKey(trimmed)) {
                state.update { it.copy(aiHasApiKey = true) }
            }
        }
    }

    companion object {
        /** 设置页只给的三档（P1 §0「设置页可改 3 / 6 / 12」）。 */
        val THRESHOLD_OPTIONS: List<Int> = listOf(3, 6, 12)

        /** 页面离开后仍保持订阅一小段时间，避免旋转 / 短暂切页时重建订阅导致按钮闪一下。 */
        private const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

data class SettingsUiState(
    /** 阈值已读回（不是「load 被调过」）；未载入时页面不预选任何一档，避免显示一个猜的值。 */
    val isLoaded: Boolean = false,
    /** 当前超期阈值（月）；null = 未载入。 */
    val thresholdMonths: Int? = null,
    /** AI 密钥是否已配置（仅表示「存过」，不携带明文）。 */
    val aiHasApiKey: Boolean = false,
)
