package com.dream.shouna.ui.component.ai
import com.dream.shouna.ui.component.search.CategoryChips

import com.dream.shouna.data.repository.AiCommitResult
import com.dream.shouna.data.repository.AiIntake
import com.dream.shouna.domain.ai.AiDraftRow
import com.dream.shouna.domain.ai.AiItemEdit
import com.dream.shouna.domain.model.Category
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * **AI 识别结果的「结果段」**：队列 + 逐条确认 + 后台串行落库 + 汇总。
 *
 * 【怎么用】页面级状态机，**每个页面 ViewModel 各持一份实例**（同一页面同时只有一轮识别，
 * 跨页面实例互不干扰）。三个动作由页面转译成方法调用：
 * [beginRound] 开新一轮 → [enqueue] 装结果 → [confirm] / [discard] / [close] 逐条处理。
 *
 * 【为什么抽出来】语音 AI 录入页（P-VOICE-AI）与拍照 AI 录入页（P-PHOTO-AI）的**输入段**
 * 完全不同（话筒 vs 位置条 + 照片行），但**结果段逐字相同**。这两页的结果段留在两处各写一遍，
 * 以后只会有一处被修好 —— 与 `AiIntake`、`AiItemDialog` 被抽出来是同一个理由。
 *
 * 【确认即落库，且先前进后落库】[confirm] 先在**同步段**里 `advance()`（弹窗那一帧就切到下一件，
 * 不为写库停一帧），再把这一件的写库投给 [scope] 上的**串行队列**（[commitMutex] 保证 FIFO）。
 * 两个直接后果：① 用户点得再快，看到的都是「立刻切到下一件」；② **重复点确认不可能发生**
 * —— 上一件在点击那一刻就离开了弹窗。代价是「确认」与「已入库」不再同步，单件写库失败只能等
 * 队列走完后由 [AiQueueState.unsavedCount] 说明。
 *
 * 【为什么用应用级作用域】用户点完确认可能立刻返回上一页 —— 用 `viewModelScope` 会让在途写库
 * 随页面销毁被取消，而「确认了却没记下」是本工程最不能接受的失败方式。传入的 [scope] 应是
 * `AppModule` 的 `@ApplicationScope`（dispatcher = IO），它在 VM 销毁后仍可能写 [state]
 * —— 无订阅者时写入无害，计数仍被正确维护。
 *
 * 【轮次号：[AiItemEdit] 的战果属于点确认那一刻的那一轮】用户可以在写库没跑完时「关闭 → 重新
 * 识别」。上一轮那几件的战果（`savedCount` / `unsavedCount`）属于**上一轮**，不该改写新一轮的
 * 汇总（否则新一轮会莫名显示「已记下 1 件」）。故落库任务捕获自己那一轮的号，结算时对不上就
 * 只还「在途计数」。用普通 `@Volatile var` 而非 State：它不参与任何渲染。
 *
 * 【照片与语音的差别全部由三个回调吃掉】本类不认识「照片」「位置先定」这些概念，页面各自的
 * 特殊动作走：
 * - [photoPathProvider]：落库时该挂哪个照片文件名（语音页恒 `null`，拍照页给出那一轮的快照）；
 * - [onRoundStarted] / [onQueueEnded]：一轮开始 / 结束时的页面级清理（拍照页用它清照片快照，
 *   语音页不需要）；
 * - [onCommitted]：一次落库结算后的页面级副作用（拍照页用它清页面上的照片名、刷「最近」）。
 *
 * 【本类不持 `Context`、不碰文件系统】照片 base64 由页面编码、文件名由页面管理（同
 * `PhotoAiIntakeViewModel` 的既有分层）。
 *
 * 被调用方：`ui/screen/voiceai/VoiceAiIntakeViewModel`、`ui/screen/photoai/PhotoAiIntakeViewModel`
 */
class AiItemQueue(
    private val intake: AiIntake,
    private val scope: CoroutineScope,
    /** 分类名单（`categoryId → name` 的反查源）。与页面读的是同一条流。 */
    private val categories: () -> List<Category>,
    /** 本次落库该挂的照片文件名 —— 由页面给出**它记账的那一张**（语音页恒 `null`）。 */
    private val photoPathProvider: () -> String? = { null },
    /** 开新一轮时页面要做的清理（在 `roundId` 已自增之后同步调用）。 */
    private val onRoundStarted: () -> Unit = {},
    /** 一轮结束（队列走完 / 用户关闭）时页面要做的清理。 */
    private val onQueueEnded: () -> Unit = {},
    /** 一次落库结算后的页面级副作用（**只在轮次仍匹配时**调用）。 */
    private val onCommitted: suspend (AiCommitResult, String?) -> Unit = { _, _ -> },
) {

    private val _state = MutableStateFlow(AiQueueState())

    val state: StateFlow<AiQueueState> = _state.asStateFlow()

    /**
     * 识别**轮次号**：每次 [beginRound] 就 +1。
     *
     * `@Volatile`：它被主线程写（开新一轮）、被 IO 线程读（结算），必须保证可见性。
     */
    @Volatile
    private var roundId = 0

    /**
     * 后台落库队列的**串行闸门**：每次确认都往 [scope] 投一个任务，任务体先取这把锁 ——
     * 于是即使队列积压了 10 件，写库也是**一件一件**（FIFO）执行，不会并发写。
     *
     * 【为什么在队列实例内而不是全局单例】需要串行的只有「同一轮识别结果」这一批，而同一轮属于
     * 同一个队列实例。跨实例（用户返回后又进来）本就是两批，不该互相等待。
     */
    private val commitMutex = Mutex()

    // --- 轮次与队列 ------------------------------------------------------------

    /**
     * 开新一轮：轮次号 +1，并清掉上一轮的队列与汇总（用户重新识别一次就是重新来过）。
     *
     * 必须由页面在**发起识别请求之前同步调用** —— 在途写库的结算靠轮次号判定归属，
     * 等到结果回来才自增就已经晚了。
     */
    fun beginRound() {
        roundId++
        onRoundStarted()
        _state.update {
            it.copy(items = emptyList(), index = 0, savedCount = null, unsavedCount = 0)
        }
    }

    /** 装一轮识别结果。`rows` 至少一件（空结果不会走到这里，由页面给「没识别出」提示）。 */
    fun enqueue(rows: List<AiDraftRow>) {
        _state.update { it.copy(items = rows.map { row -> AiItemEdit.from(row) }, index = 0) }
    }

    /**
     * 收掉队列但**保留汇总**：AI 总开关在别处被关掉时用。
     *
     * 汇总不清是有意的：已确认的那些件早已在库里，用户仍该看到「已记下几件」。
     */
    fun clearQueue() {
        _state.update { it.copy(items = emptyList(), index = 0) }
    }

    // --- 弹窗内的编辑 ----------------------------------------------------------

    /** 弹窗内改名称（队列为空时无操作）。 */
    fun nameChanged(name: String) = updateEditing { it.copy(name = name) }

    /** 弹窗内改备注。 */
    fun noteChanged(note: String) = updateEditing { it.copy(note = note) }

    /** 弹窗内改分类（`CategoryChips`，再点一次已选中的 → `null` = 未分类）。 */
    fun categorySelected(categoryId: String?) = updateEditing { it.copy(categoryId = categoryId) }

    /**
     * 弹窗内改位置（**语音页**的弹窗才提供入口，拍照页的位置只读 —— `V2-FR-13`「位置先定后用」）。
     *
     * [locationId] 与 [locationText] 必须来自**同一棵树快照**（页面下传的是同一次
     * `observeTree()` 的结果），否则会写出「id 是 A、显示是 B」的位置行。
     */
    fun locationSelected(locationId: String, locationText: String) =
        updateEditing { it.copy(locationId = locationId, locationText = locationText) }

    // --- 三个出口 --------------------------------------------------------------

    /**
     * 点「确认」：**立刻弹下一件**，这一件的写库投给后台串行队列。
     *
     * 【顺序是硬口径：先前进，后落库】见类注释。注意 [AiItemEdit.source] 保留只读因子
     * （待新建层 / 被丢弃层），这里用 `source.copy(...)` 合成最终行，不改写 `source`。
     */
    fun confirm() {
        val editing = _state.value.editing ?: return
        // 名称与位置都是必填：页面上「确认」已置灰（§4-3），这里是兜底，不静默丢掉这一件。
        if (!editing.canConfirm) return

        // 分类名从名单反查。落库只用 `categoryId`，名称只为让「改名后仍指向同一分类」这件事
        // 在数据里自洽（`AiDraftRow.categoryName` 与 `categoryId` 本是一对）。
        val categoryName = editing.categoryId
            ?.let { id -> categories().firstOrNull { it.id == id }?.name }
        val row = editing.source.copy(
            name = editing.name.trim(),
            locationId = editing.locationId,
            locationText = editing.locationText,
            categoryId = editing.categoryId,
            categoryName = categoryName,
            note = editing.note,
        )

        // 照片在起协程前取定：**中途的写不该改变去向**（`advance()` 走完最后一件时会触发
        // `onQueueEnded` 清掉页面上的快照，而这一件的写库仍该带上那张图）。
        val photoPath = photoPathProvider()
        // 这一件属于哪一轮（见 [roundId]）：结算时轮次对不上就只还「在途计数」，不改写汇总。
        val round = roundId

        // ① 先前进：弹窗立刻切到下一件。与下面那行累加之间**没有挂起点** ⇒ UI 只看到
        //    「已经是下一件 + 在途计数已加一」这一个终态，不会闪出任何中间帧。
        advance()
        _state.update { it.copy(pendingCommits = it.pendingCommits + 1) }

        // ② 再落库：投给应用级作用域，锁内串行。
        scope.launch {
            try {
                val result = commitMutex.withLock {
                    intake.commitRows(
                        rows = listOf(row),
                        // 两页都不需要兜底位置：语音行的位置由那句话（或用户在弹窗里补的）决定，
                        // 拍照行的位置在识别前就写进了每一行。
                        fallbackLocationId = null,
                        photoPath = photoPath,
                    )
                }
                // 跨轮保护：这一件属于**点确认那一刻的那一轮**。用户若已「关闭 → 重新识别」
                // （轮次号变了），它的战果不该改写新一轮的汇总 —— 只还「在途计数」（在 finally）。
                if (round == roundId) {
                    _state.update {
                        it.copy(
                            // `savedCount` 的 null 语义 = 「本轮还没有落库动作」，首次确认才从 null 起算。
                            savedCount = (it.savedCount ?: 0) + result.saved,
                            unsavedCount = it.unsavedCount + result.notSaved,
                        )
                    }
                    onCommitted(result, photoPath)
                }
            } catch (cancellation: CancellationException) {
                // 应用级作用域只在进程退出时被取消；放行，不能吞。
                throw cancellation
            } catch (_: Throwable) {
                // `commitRows` 已把「单条失败」收敛成计数；走到这里说明它自己抛了（罕见）——
                // 宁可多报一件「没记下」，也不静默丢掉。
                if (round == roundId) {
                    _state.update { it.copy(unsavedCount = it.unsavedCount + 1) }
                }
            } finally {
                _state.update { it.copy(pendingCommits = it.pendingCommits - 1) }
            }
        }
    }

    /**
     * 点「放弃」：跳过这一件（**不落库**），弹下一件。
     *
     * 【为什么不计入「没记下」】[AiQueueState.unsavedCount] 是**写库失败**的信号。
     * 放弃是用户的主动选择，算成失败会让汇总说谎。
     */
    fun discard() {
        if (_state.value.editing == null) return
        advance()
    }

    /**
     * 点「关闭」= **放弃本次录入**：收掉弹窗、丢掉队列里剩下的件。
     *
     * 【已确认的件不回收】这是逐条落库的直接后果（见 [confirm]）——「关闭」只终止剩下的流程。
     */
    fun close() {
        endQueue()
    }

    // --- 内部 -----------------------------------------------------------------

    /** 前进到下一件；队列走完则收掉弹窗（汇总留在页面上）。 */
    private fun advance() {
        val current = _state.value
        if (current.index + 1 < current.items.size) {
            _state.update { it.copy(index = it.index + 1) }
        } else {
            endQueue()
        }
    }

    /**
     * 结束整轮：清队列并通知页面收掉本轮快照。
     *
     * 页面持有的本轮快照（如拍照页的照片名）归它自己管 —— 本类只发一个「这一轮结束了」的信号。
     */
    private fun endQueue() {
        onQueueEnded()
        _state.update { it.copy(items = emptyList(), index = 0) }
    }

    /** 就地改写「正在弹窗的那一件」。队列为空时**原样返回**（不造出一个新条目）。 */
    private fun updateEditing(transform: (AiItemEdit) -> AiItemEdit) {
        _state.update { current ->
            val index = current.index
            val item = current.items.getOrNull(index) ?: return@update current
            current.copy(
                items = current.items.toMutableList().also { it[index] = transform(item) },
            )
        }
    }
}

/**
 * 「结果段」的全部状态。
 *
 * 队列在途 ⇔ [editing] 非空 ⇔ 弹窗显示。只放**页面要渲染的东西**：位置匹配等派生由
 * `AiIntake` 在入队前算好。
 */
data class AiQueueState(
    /**
     * 本轮识别结果的**待确认队列**（逐条弹窗的数据源）。
     *
     * 队列里只放**还没处理完的件** —— 确认 / 放弃一件即前进一格，已确认的那些早已在库里，
     * 不在队列里。
     */
    val items: List<AiItemEdit> = emptyList(),
    /** [items] 里当前正在弹窗的那一格的**下标**；队列为空时无意义。 */
    val index: Int = 0,
    /** 本轮**累计确认落库成功**的件数；`null` = 本轮还没有任何落库动作。 */
    val savedCount: Int? = null,
    /**
     * 本轮**没记下**的件数（`commitRows` 的写库失败 + 无位置被跳过）。
     *
     * 必须让用户看到：确认了 5 件却只进库 4 件时，弹窗直接走完会让人以为全都记下了。
     * **用户主动「放弃」的件不计入这里** —— 那不是失败。
     */
    val unsavedCount: Int = 0,
    /**
     * **在途的后台落库任务数**（已点确认、尚未结算完的件）。
     *
     * 【与 [items] 的区别】`items` 是**待确认**的件（弹窗在消费它），它是**已确认、待落库**的件 ——
     * 前者在点击那一刻就少一个，后者在写库完成那一刻才少一个。
     *
     * 【为什么需要它】它是「删照片文件」那道闸的一半：在途任务引用的图，其 `photo_path` 还没
     * 写进库 ⇒ 引用计数恒为 0 ⇒ 只看计数会误删（见
     * `PhotoAiIntakeViewModel.canDeletePhotoFile`）。语音页没有照片，用不到它。
     * 它不参与任何渲染。
     */
    val pendingCommits: Int = 0,
) {
    /** 弹窗正在确认的那一件；`null` = 没有弹窗（队列为空或已走完）。 */
    val editing: AiItemEdit? get() = items.getOrNull(index)

    /** 本轮识别出的总件数（弹窗标题的「/ N」）。 */
    val total: Int get() = items.size

    /** 弹窗标题里的「第几件」（1 起数）。 */
    val number: Int get() = index + 1
}
