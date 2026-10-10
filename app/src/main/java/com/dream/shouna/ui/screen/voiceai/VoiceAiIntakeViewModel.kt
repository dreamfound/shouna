package com.dream.shouna.ui.screen.voiceai

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.AiIntake
import com.dream.shouna.data.repository.AiSettingsRepository
import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.di.ApplicationScope
import com.dream.shouna.domain.ai.AiDraftRow
import com.dream.shouna.domain.ai.AiFailReason
import com.dream.shouna.domain.ai.AiState
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.ai.AiItemQueue
import com.dream.shouna.ui.component.ai.AiQueueState
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 语音 AI 录入页（P-VOICE-AI，`V2-FR-10`）的状态机。
 *
 * 【为什么从首页页内弹窗改为独立页面】原落点是首页（P-HOME）：「说一句」→ 系统转文字 →
 * 直接进 AI → **首页页内确认卡** → 一次确认、一次落库。用户 2026-10-09 口径：**语音通道与拍照
 * 通道同一种记录方式** —— 各自独立成页、结果都走**逐条确认弹窗**。页面化的收益与拍照页一样：
 * AI 失败提示与结果都有了固定落点，首页也不再被这一整段撑开。
 *
 * 【与拍照页（P-PHOTO-AI）的差别只在输入段】本页没有位置条、也没有「识别」按钮（松手即识别），
 * 因为**位置由那段话决定**而不是用户先定 —— 这是两条通道最本质的分野。结果段则逐字相同：
 * 两页共用 `AiItemQueue`（队列 / 逐条落库 / 汇总）与 `AiItemDialog`（弹窗）。
 * **输入是「说话变字」**（`ui/component/VoiceCapturePad`）：音频在本机识别成文字，只有**文字**上行。
 * ⚠️ 2026-10-10 试过的「录音直送」已停用 —— 平台不受理音频输入（`content` part 只认
 * `text` / `image_url` / `file`），那次改动的代码注释保留、未删（见 `VoiceAiIntakeScreen` 的 KDoc）。
 *
 * 【位置怎么来：模型给路径 → 本地匹配 → **降级到最近已存在祖先**】模型返回 `location_path`
 * 的分段，本地 `LocationPathMatcher` 逐层匹配。用户 2026-10-09 口径：**不新建节点**，
 * 命中的最深层就是落点（「客厅 › 抽屉」而「抽屉」不存在 ⇒ 记到「客厅」），被丢弃的层名经
 * [com.dream.shouna.domain.ai.AiDraftRow.droppedLocationNames] 带到弹窗上**明说**「已就近」
 * —— 否则用户会以为模型认错了位置。一个祖先都没对上时该行没有位置，弹窗里由用户**就地补**
 * （补上「确认」即解禁）。
 * 这条口径由 `AiIntake.parseSpokenText`（场景 `AiScene.VOICE_INPUT` + `createMissingLayers =
 * false`）兑现。语音页的功能段也是独立的，不再与录入页的文字通道共用（用户 2026-10-09 口径）。
 *
 * 【为什么弹窗里位置可改而拍照页只读】拍照通道的位置是**用户在识别前亲手选的**（`V2-FR-13`），
 * 模型不参与，没有猜错的可能；语音通道的位置是**模型从那句话里猜的**，猜错、降级、或压根没提，
 * 三种情况都必须能就地纠（用户 2026-10-09 口径：「语音时，可以对位置进行修改」）。
 *
 * 【「用户不接触原始文本」仍然成立】识别结果**不落到任何输入框**，用户只在弹窗上看到解析出的行。
 *
 * 【AI 关着时本页不做任何事】总开关出厂默认关；关着时首页连入口都不画，也就不该进得来。
 * 极端情况下（进来之后开关在别处被关掉）队列与弹窗一并收掉（回 `Hidden`），
 * 话筒同时失能（[VoiceAiIntakeUiState.aiEnabled] 传进页内话筒的 `enabled`）——
 * **未开启开关时不产生任何网络请求**（`V2-AI-PRD` §5 硬约束）。
 *
 * 被调用方：VoiceAiIntakeRoute（hiltViewModel + 方法引用下传）
 */
@HiltViewModel
class VoiceAiIntakeViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    private val categoryRepository: CategoryRepository,
    private val aiIntake: AiIntake,
    private val aiSettings: AiSettingsRepository,
    // `@param:` 显式指定注解目标（同 `UrlConnectionAiHttp`）：要的只是「限定符作用在构造参数上」
    // （Dagger 读参数注解），避免 Kotlin 将来把它同时作用到属性上造成语义漂移。
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val state = MutableStateFlow(VoiceAiIntakeUiState())

    val uiState: StateFlow<VoiceAiIntakeUiState> = state.asStateFlow()

    /**
     * 弹窗内「位置选择态」的数据源 —— 与拍照页、录入页读的是同一条流（已展平、已过滤内置哨兵）。
     *
     * 【为什么本页要位置树】这句话猜出来的位置必须能就地改（见类注释）。树只被弹窗消费，
     * 页面上不铺位置条。
     */
    val locationTree: StateFlow<List<LocationTreeRow>> = locationRepository.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /**
     * 弹窗内「分类」chips 的数据源（FR-12：只能从既有分类里选，AI 与本页都**不新建分类**）。
     * 与录入页、拍照页同一个仓库入口；行内 chips 而非第二个对话框，理由同拍照页。
     */
    val categories: StateFlow<List<Category>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /**
     * **结果段**：队列 + 逐条确认 + 后台串行落库 + 汇总（`ui/ai/AiItemQueue`）。
     *
     * 【三个与拍照页不同的地方全在默认值里】本页 **不挂照片**（`photoPathProvider` 恒 `null`）、
     * **没有本轮快照要清**（两个生命周期回调留空）。于是这里连参数都不用传 —— 「两页共用一份
     * 结果段」这件事本身就说明它没有为某一页写死过什么。
     */
    private val queue = AiItemQueue(
        intake = aiIntake,
        scope = appScope,
        categories = { categories.value },
    )

    /** 结果段状态（队列 / 进度 / 汇总）——页面读它渲染弹窗与汇总行。 */
    val queueState: StateFlow<AiQueueState> = queue.state

    init {
        // AI 总开关的订阅：与录入页、拍照页读到的是同一条流，三页必须一致。
        //
        // 关掉开关时**连队列与弹窗一起收掉**（回 `Hidden`）：开关的语义是「立刻停止一切请求」，
        // 让一个已经失效的弹窗继续挂着，等于给用户一个已经取消的入口。
        // 已确认的那些件早已在库里，`clearQueue` 也**不清汇总**（用户仍该看到「已记下几件」）。
        viewModelScope.launch {
            aiSettings.observeEnabled().collect { enabled ->
                if (!enabled) queue.clearQueue()
                state.update { current ->
                    when {
                        !enabled -> current.copy(aiEnabled = false, aiState = AiState.Hidden)
                        current.aiState is AiState.Hidden -> current.copy(aiEnabled = true, aiState = AiState.Idle)
                        else -> current.copy(aiEnabled = true)
                    }
                }
            }
        }
    }

    // --- `V2-FR-10`：语音录入（说话变字 → AI） -----------------------------------

    /**
     * 语音转文字的识别结果（**用户不接触原始文本**）。页内话筒与那个临时测试输入框都走它 ——
     * 两者共用同一条提交路径（门控 / 轮次号 / 密钥前置 / 位置降级全部照跑）。
     */
    fun onSpokenText(text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        submitForParsing { aiIntake.parseSpokenText(spoken) }
    }

    // 【2026-10-10 停用 · 注释保留】录音通道的入口（抬手交出的一段录音，**裸 base64** 的 WAV）：
    //     fun onSpokenAudio(audioBase64: String) {
    //         if (audioBase64.isEmpty()) return
    //         submitForParsing { aiIntake.parseSpokenAudio(audioBase64) }
    //     }
    // 停用原因：平台不受理音频输入（`content` part 只认 `text` / `image_url` / `file`），
    // 恢复时把这四行放回、并把 `VoiceAiIntakeScreen` 里对应两处注释一并放开。
    // 录音那一套（`data/local/audio/`、`AudioModule`、`AudioCapturePad`）也一并注释保留。

    /**
     * 语音入口的**提交路径**。
     *
     * 【为什么先 [AiItemQueue.beginRound] 再起协程】轮次号是「在途写库归属哪一轮」的判据，
     * 必须在**发起请求之前**同步自增。页面在解析期间会把话筒置灰（见
     * `VoiceAiIntakeScreen` 的 `enabled`），所以两句重叠的解析进不来；这个自增是第二道保险。
     *
     * 顺序与拍照页一致：先判密钥（此时给「去设置页填」比给「解析失败」有用），再解析。
     *
     * @param parse 真正发起解析的那一跳 —— 场景（`VOICE_INPUT`）与位置口径（**不新建**、
     *   降级到最近已存在祖先）都收在 `AiIntake` 里，本层只把识别到的字交出去，
     *   不自己决定场景与位置策略。
     */
    private fun submitForParsing(parse: suspend () -> List<AiDraftRow>?) {
        queue.beginRound()
        viewModelScope.launch {
            state.update { it.copy(aiState = AiState.Parsing) }

            if (!aiSettings.hasApiKey()) {
                fail(AiFailReason.NO_KEY)
                return@launch
            }

            val rows = parse()
            when {
                // 失败只有一条路径（闸门返 null → 行内提示），不弹窗、不阻塞、不计数
                // （`V2-FR-07` / `V2-COST-04`）。
                rows == null -> fail(AiFailReason.PARSE_FAILED)
                rows.isEmpty() -> fail(AiFailReason.NOTHING_RECOGNIZED)
                else -> {
                    // 队列在途时回到 `Idle`：页面上没有可点的主表单，也不需要额外状态
                    // —— 弹窗由队列驱动（`editing` 非空即显示）。
                    state.update { it.copy(aiState = AiState.Idle) }
                    queue.enqueue(rows)
                }
            }
        }
    }

    // --- 逐条弹窗：编辑、确认、放弃、关闭（全部转译给结果段） ---------------------

    /** 弹窗内改名称（队列为空时无操作）。 */
    fun onItemNameChanged(name: String) = queue.nameChanged(name)

    /** 弹窗内改备注。 */
    fun onItemNoteChanged(note: String) = queue.noteChanged(note)

    /** 弹窗内改分类（`CategoryChips`，再点一次已选中的 → null = 未分类）。 */
    fun onItemCategorySelected(categoryId: String?) = queue.categorySelected(categoryId)

    /**
     * 弹窗内改位置（**本页专有**，拍照页的位置只读）。
     *
     * `locationId` 与 `locationText` 由弹窗从**同一次**位置树快照里成对取出后回传 ——
     * 两者同源，不会写出「id 是 A、显示是 B」的位置行。
     *
     * 它同时是「这句话没提位置」那条数据路径的出口：用户补一个位置，弹窗里置灰的「确认」随即解禁
     * （判据是 `AiItemEdit.canConfirm`，与识别结果的初值无关）。
     */
    fun onItemLocationSelected(locationId: String, locationText: String) =
        queue.locationSelected(locationId, locationText)

    /**
     * 点「确认」：**立刻弹下一件**，这一件的写库投给后台串行队列。
     *
     * 全部语义在 [AiItemQueue.confirm]（先前进后落库、逐条落库而非攒批、跨轮保护、
     * 失败只计数不弹窗）—— 本类只做转译。
     */
    fun onConfirmItem() = queue.confirm()

    /**
     * 点「放弃」：跳过这一件（**不落库**），弹下一件。
     *
     * 【为什么不计入「没记下」】汇总里那个数是**写库失败**的信号。放弃是用户的主动选择，
     * 算成失败会让汇总说谎。
     */
    fun onDiscardItem() = queue.discard()

    /**
     * 点「关闭」= **放弃本次录入**：收掉弹窗、丢掉队列里剩下的件。
     *
     * 【已确认的件不回收】（逐条落库的直接后果）——「关闭」只终止剩下的流程。
     */
    fun onCloseIntake() = queue.close()

    // --- 内部 -----------------------------------------------------------------

    /** 落一行行内提示（**不弹窗**，`实现约束.md` §4-1）。 */
    private fun fail(reason: AiFailReason) {
        state.update { it.copy(aiState = AiState.Failed(reason = reason)) }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * 语音 AI 录入页状态。
 *
 * [aiEnabled] / [aiState] 只在**开着 AI 时**才有意义；关着时保持初值
 * （`aiEnabled = false`、`aiState = Hidden`）。
 *
 * 【结果段不在这里】队列、逐条进度与「已记下 / 没记下」汇总属 `AiQueueState`
 * （`ui/ai/AiItemQueue`），与拍照 AI 录入页共用 —— 见 [VoiceAiIntakeViewModel.queueState]。
 * 位置树与分类名单同样在独立 `StateFlow` 上（不进本状态，避免无关变更触发整页重组）。
 *
 * **本页没有「位置」这一项输入态**：位置是识别结果的一部分（由那句话 + 降级口径决定），
 * 不是页面上的一个选择。
 */
data class VoiceAiIntakeUiState(
    /**
     * `V2-FR-06`：AI 总开关（**出厂默认关**）。入口的可见性由首页按它判定；本页据它决定
     * 是否收掉队列与弹窗（关掉即回 `Hidden`），并把它传给页内话筒当 `enabled`
     * —— 关着时**一个字都不该录**（录了也没处送，还会误导用户）。
     */
    val aiEnabled: Boolean = false,
    /**
     * 识别状态机（`domain/ai/AiState`）。开关未开时为 [AiState.Hidden]。
     *
     * 本页**只用到三种**：`Hidden` / `Idle`（含「结果在弹窗里逐条确认」这一段） / `Parsing` /
     * `Failed`。`AiState.Draft` 在本页**不再产生** —— 那是页内确认卡的形态，录入页（P-ADD）仍在用。
     */
    val aiState: AiState = AiState.Hidden,
)
