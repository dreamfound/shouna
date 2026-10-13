package com.dream.shouna.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.AiSettingsRepository
import com.dream.shouna.data.repository.ItemRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页（docs/实现约束.md）。
 *
 * 【状态来源有两个，且**都不可省**】
 * - `isEmpty` 来自数据库流（有没有记录）；
 * - AI 总开关是**页内态**（`aiEnabled`）—— 它不落库、也不该落库。
 *
 * 两者用 `combine` 合成一个 UiState：分开暴露两个 StateFlow 会让页面去拼「什么时候显示空态
 * 文案」这件事，那是展示派生，`实现约束.md` §2-2 要求留在 VM。
 *
 * 【V2 起首页只负责**两个入口的导航**，不再承载任何 AI 过程】语音句录入（`V2-FR-10`）与
 * 拍照识别录入（`V2-FR-11`）都各自独立成页 —— 本类因此**不持有 `AiIntake`、不碰位置、不碰照片、
 * 不认识 `AiState`**。原来的语音通道（页内弹窗 + 页内确认卡 + 落库汇总）整块搬去了
 * `ui/screen/voiceai/`，理由是用户 2026-10-09 的口径：两条通道用**同一种记录方式**
 * （独立页面 + 逐条确认弹窗），首页只留入口。
 *
 * 【AI 关着时本类不做任何事】总开关出厂默认关；关着时两个入口都不出现
 * （[HomeUiState.aiEnabled]），也不产生任何网络请求（`V2-AI-PRD` §5 硬约束）。
 *
 * 被调用方：HomeRoute（hiltViewModel + 方法引用下传）
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    itemRepository: ItemRepository,
    aiSettings: AiSettingsRepository,
) : ViewModel() {

    /** 页内态：AI 总开关（两个入口可见性的唯一真源）。`isEmpty` 不在其中（见类注释）。 */
    private val aiEnabled = MutableStateFlow(false)

    val uiState: StateFlow<HomeUiState> = combine(
        itemRepository.observeItemSummaries(),
        aiEnabled,
    ) { items, enabled ->
        HomeUiState(isEmpty = items.isEmpty(), aiEnabled = enabled)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), HomeUiState())

    init {
        // AI 总开关的订阅：与录入页、语音页、拍照页读到的是同一条流，四页必须一致。
        //
        // 【为什么首页不再管「关掉时收掉卡片」】首页已经没有 AI 卡片了 —— 关开关时正在
        // 语音页 / 拍照页里的那一轮，由那两页自己收掉（各自订阅同一条流）。
        viewModelScope.launch {
            aiSettings.observeEnabled().collect { enabled -> aiEnabled.value = enabled }
        }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * 首页状态。
 *
 * 本类**不含**任何 AI 过程态（`AiState` / 队列 / 汇总），也**不含**照片与位置 —— 那些都属于
 * 各自的独立页面（`ui/screen/voiceai/`、`ui/screen/photoai/`）。首页只回答两件事：
 * 有没有记录（空态文案）、要不要画那两个 AI 入口。
 */
data class HomeUiState(
    /** 一件记录都没有 —— 驱动空态文案。 */
    val isEmpty: Boolean = true,
    /**
     * `V2-FR-06`：AI 总开关（**出厂默认关**）。首页两个 AI 入口**可见性的唯一真源** ——
     * 关着时按钮不出现、零网络请求，行为与 v1.5 完全一致（`V2-AI-PRD` §5 硬约束）。
     */
    val aiEnabled: Boolean = false,
)
