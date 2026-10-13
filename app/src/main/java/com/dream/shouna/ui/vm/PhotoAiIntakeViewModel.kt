package com.dream.shouna.ui.vm

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.AiIntake
import com.dream.shouna.data.repository.AiSettingsRepository
import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.di.ApplicationScope
import com.dream.shouna.domain.ai.AiFailReason
import com.dream.shouna.domain.ai.AiState
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.ai.AiItemQueue
import com.dream.shouna.ui.component.ai.AiQueueState
import com.dream.shouna.util.LocationPath
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * 拍照 AI 录入页（`V2-FR-11` + `V2-FR-13`）的状态机。
 *
 * 【为什么从首页页内面板改为独立页面】原落点是首页（P-HOME）的页内展开面板，理由是
 * 「面板里要开位置选择、而面板本身是弹层 —— 弹窗套弹层会叠两个窗口」。独立页面把这条理由
 * 自然消解：页面里开 `LocationPickerSheet` 只有一个窗口。页面化的收益是位置、照片、识别
 * 不再把首页撑开，AI 失败提示也有了固定的落点。
 *
 * 【三件事的先后是硬口径】**位置 → 照片 → 识别**（`V2-FR-13`「位置先定后用」）。模型这一路
 * **永远不返回位置**，识别出的每一件都落到用户先定的那个位置 ⇒ 没有位置就没有落点，
 * [onRecognizePhoto] 在该状态下不可点（页面禁用按钮并给红字原因，不弹窗）。
 *
 * 【结果形态：逐条弹窗队列】一张照片可能识别出**多件**。识别成功后把结果交给 [queue]
 * （`ui/ai/AiItemQueue`，与语音 AI 录入页 P-VOICE-AI **共用同一份实现**），页面按
 * [AiQueueState.editing] 逐条弹窗。
 *
 * 【与语音页的差别只在**输入段**，结果段逐字相同】本页要位置与照片、模型不返回位置；
 * 语音页只有一句话筒、位置由那句话决定（且猜错可在弹窗里就地改）。两者共用
 * `AiItemQueue`（队列 / 落库 / 汇总）与 `AiItemDialog`（弹窗）—— 这也是把它们抽出来的原因。
 *
 * 【位置在弹窗里**只读】**它在识别前就定好了（`V2-FR-13`），逐条改位置会与这条口径冲突，
 * 也会在弹窗里再开一个位置选择窗口。改位置 = 关闭整轮、回页面重选再识别。
 *
 * 【照片文件的生命周期】建与删都在 `PhotoInputRow` 里（它是系统选择器的适配层），**唯一例外**
 * 是「用户离开这一页」那一下 —— 页面整块离开组合时，未被落库的那张图必须有人收走，
 * 由页面在 [PhotoAiIntakeScreen] 的 `onBack`（返回按钮 + 系统返回键）里删。
 * 已落库的照片不会走到那里：第一件落库成功时 [queue] 会回调 [AiItemQueue] 的 `onCommitted`，
 * 本类据此刻把 `photoName` 清空，那时删的是一个 `null`。
 * **删之前必须先过 [canDeletePhotoFile] 那道闸** —— 用户 2026-10-09 口径：只删绑定，库中
 * 零引用才删文件；且还有在途落库时**一律不删**（那些任务引用的正是这张图，计数此刻不可信）。
 *
 * 【本类不持有 `Context`】照片 base64 由**页面**编码好传进来 —— 守本工程「文件 IO 全留在
 * Compose 层」的分层（与录入页 `QuickAddViewModel.onAiParse` 同一约定）。
 *
 * 【AI 关着时本页不做任何事】总开关出厂默认关；关着时首页连入口都不画，也就不该进得来。
 * 极端情况下（进来之后开关在别处被关掉）队列与弹窗一并收掉（回 `Hidden`），与录入页同口径。
 *
 * 被调用方：PhotoAiIntakeRoute（hiltViewModel + 方法引用下传）
 */
@HiltViewModel
class PhotoAiIntakeViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    private val categoryRepository: CategoryRepository,
    private val itemRepository: ItemRepository,
    private val aiIntake: AiIntake,
    private val aiSettings: AiSettingsRepository,
    // `@param:` 显式指定注解目标（同 `UrlConnectionAiHttp`）：要的只是「限定符作用在构造参数上」
    // （Dagger 读参数注解），避免 Kotlin 将来把它同时作用到属性上造成语义漂移。
    @param:ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val state = MutableStateFlow(PhotoAiIntakeUiState())

    val uiState: StateFlow<PhotoAiIntakeUiState> = state.asStateFlow()

    /** 位置选择弹层的树数据（已展平、已过滤内置哨兵）—— 与录入页读的是同一条流。 */
    val locationTree: StateFlow<List<LocationTreeRow>> = locationRepository.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /**
     * 弹窗内「分类」chips 的数据源 —— 与录入页同一个仓库入口（FR-12：只能从既有分类里选，
     * AI 与本页都**不新建分类**）。
     *
     * 【为什么是行内 chips 而不是第二个对话框】`实现约束.md` §4-4 要求录入期间不弹位置 / 分类
     * 对话框；本页已经把结果做成了弹窗，分类再开一层就成了弹窗套弹窗。`CategoryChips` 是页面里
     * 的一排 chip，零新增窗口。
     */
    val categories: StateFlow<List<Category>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /** FR-11：最近使用的位置（弹层内的「最近」chips）。 */
    private val recent = MutableStateFlow<List<Location>>(emptyList())
    val recentLocations: StateFlow<List<Location>> = recent.asStateFlow()

    /**
     * 位置选择弹层的开合。与录入页同一手法：本 VM 只持有它，`.value` 的读取落在页面的
     * 下沉宿主里，避免翻开关把整页 body 拖进重组。
     */
    private val picker = MutableStateFlow(false)
    val isLocationPickerVisible: StateFlow<Boolean> = picker.asStateFlow()

    /**
     * 当前这一轮识别结果所对应的照片文件名（`AiItemQueue` 落库时该挂的那张图）。
     *
     * 【为什么单独记账，而不直接用 [PhotoAiIntakeUiState.photoName]】队列里每一件落库时该带的
     * 都是**出结果那一刻那张图**。用户在弹窗逐条确认的过程中若又换了一张图（先落库成功了第一件、
     * `photoName` 已被清空，恰恰是常态），落库该带的仍是原来那张。故在识别成功那一刻快照，
     * 一轮结束（队列走完 / 关闭）时由队列的两个生命周期回调清空 —— 与 [AiItemQueue.confirm]
     * 里「先取定 `photoPath` 再前进」同一个理由：**中途的写不该改变去向**。
     * 用普通 `var` 而非 State：它不参与任何渲染。
     */
    private var draftPhotoName: String? = null

    /**
     * **结果段**：队列 + 逐条确认 + 后台串行落库 + 汇总（`ui/ai/AiItemQueue`）。
     *
     * 【为什么状态不在本类】它与语音 AI 录入页逐字相同，写两遍迟早只修一处。本类只把**页面
     * 特有的那几件事**通过回调接进去：照片快照的取与清、落库成功后清页面上的照片名并刷新「最近」。
     */
    private val queue = AiItemQueue(
        intake = aiIntake,
        scope = appScope,
        categories = { categories.value },
        // 落库该挂的图 = **这一轮出结果那一刻**的那张（不是「现在页面上那张」，两者在一轮内会分叉）。
        photoPathProvider = { draftPhotoName },
        onRoundStarted = { draftPhotoName = null },
        onQueueEnded = { draftPhotoName = null },
        onCommitted = { result, photoPath ->
            // 【第一件落库成功即清页面态文件名】那张图已属于库里那批记录（`photo_path` 指着它），
            // 页面不该再持有 —— 否则「照片行的替换即删」或「离开本页即删」会删掉库里正在用的文件。
            // **只清「这一件带的那张图」**：写库期间用户换过图时，页面上那个新名字不能被清掉
            // —— 否则刚拍的图失去归属，连「离开本页即删」都轮不到它。
            if (result.saved > 0) {
                state.update { current ->
                    if (current.photoName == photoPath) current.copy(photoName = null) else current
                }
                // 这次落库刷新了位置的 `last_used_at` → 同步一次「最近」，使弹层顺序立刻正确。
                recent.value = locationRepository.recentUsed()
            }
        },
    )

    /** 结果段状态（队列 / 进度 / 汇总）——页面读它渲染弹窗与汇总行。 */
    val queueState: StateFlow<AiQueueState> = queue.state

    init {
        // AI 总开关的订阅：与录入页读到的是同一条流，两页必须一致。
        //
        // 关掉开关时**连队列与弹窗一起收掉**（回 `Hidden`）：开关的语义是「立刻停止一切请求」，
        // 让一个已经失效的弹窗继续挂着，等于给用户一个已经取消的入口。
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

        // 位置条文本的派生（与录入页逐字同口径）：路径有**两个因** —— ① 树变了（改名 / 新建 /
        // 祖先被移走）；② 选中项变了。只订阅树是不够的：点选位置**不产生任何 DB 变更**，
        // Room 的 Flow 不会重发，位置条会停在上一个值上。两者必须 combine，且回写前要确认
        // 这条结果仍对应当前选中项（combine 的一次发射可能晚于 `selectLocation` 的原子写）。
        viewModelScope.launch {
            combine(
                locationRepository.observeTree(),
                state.map { it.selectedLocationId }.distinctUntilChanged(),
            ) { tree, selectedId -> selectedId to pathOf(tree, selectedId) }
                .collect { (selectedId, pathText) ->
                    state.update { current ->
                        if (current.selectedLocationId == selectedId) {
                            current.copy(selectedLocationPath = pathText)
                        } else {
                            current
                        }
                    }
                }
        }

        // 位置默认值 = **最近使用**（FR-11，与录入页同口径）。
        //
        // 【为什么不需要录入页那套 `initialize(initialLocationId)` 守卫】那里的守卫是为了挡住
        // **路由参数**在配置变更后重放；本页没有路由参数（位置一律在页内先定），
        // 本 VM 在返回栈上只建一次，`init` 天然只跑一遍。预选值不写库，用户改过之后也没有
        // 任何东西会把它打回去。
        viewModelScope.launch {
            val recentList = locationRepository.recentUsed()
            recent.value = recentList
            // 一次树快照同时拿到 id 与路径（两者读的是同一张表）。只写 id 的话，位置条要等上面
            // 那条 combine 首次收到树才有文本，中间会露出一帧空路径。
            val tree = locationRepository.observeTree().first()
            val preferredId = recentList.firstOrNull()?.id
                ?.takeIf { id -> tree.any { it.location.id == id } }
            if (preferredId != null) selectLocation(preferredId, pathOf(tree, preferredId))
        }
    }

    // --- 位置（必填，`V2-FR-13` 位置先定后用） --------------------------------

    fun onOpenLocationPicker() {
        picker.value = true
    }

    fun onDismissLocationPicker() {
        picker.value = false
    }

    /** 在树里选中一个已有位置（树行与「最近」chips 共用这一条）。 */
    fun onLocationSelected(locationId: String) {
        selectLocation(locationId)
        picker.value = false
    }

    /**
     * 「或输入位置」：新建一个位置节点并立即选中。父级 = **当前已选位置**（未选则建在根级）
     * —— 与录入页同一口径：位置的创建在任何页面都只发生在**用户的显式动作**下。
     */
    fun onCreateLocation(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // 父级此刻就是选中项，它的路径已经就绪 ⇒ 直接拼出完整路径，不必等树 Flow 刷出新节点
            // （新建后树确实会重发，但那是异步的，中间会闪一帧没有前缀的名字）。
            val parentPath = state.value.selectedLocationPath
            val created = locationRepository.create(
                name = trimmed,
                parentId = state.value.selectedLocationId,
            )
            recent.value = locationRepository.recentUsed()
            selectLocation(
                locationId = created.id,
                pathText = if (parentPath.isEmpty()) {
                    // 建在根级：自身名字就是完整路径。
                    created.name
                } else {
                    parentPath + LocationPath.SEPARATOR + created.name
                },
            )
            picker.value = false
        }
    }

    // --- 照片与识别（`V2-FR-11`） -------------------------------------------

    /** 照片文件名变化（拍照 / 相册 / 移除的统一入口）。文件本体的建删由 `PhotoInputRow` 负责。 */
    fun onPhotoChanged(photoName: String?) {
        state.update { it.copy(photoName = photoName) }
    }

    /**
     * 「识别」：照片 → 草稿行（`V2-FR-11`）。**base64 由页面编码好传进来** —— 守本工程
     * 「文件 IO 全留在 Compose 层」的分层（本 VM 不持 `Context`）。
     *
     * 【位置是这条通道的前置（`V2-FR-13`）】没选位置就**不调模型**：模型这一路永远不返回位置
     * （`AiPrompt` 的契约段强制 `location_path = null`），没有目标位置这一步就无从落地。
     * 选定值随照片一起交给 `parsePhoto`，由它**写进每一行**。
     *
     * 【为什么先 [AiItemQueue.beginRound] 再起协程】轮次号是「在途写库归属哪一轮」的判据，
     * 必须在**发起请求之前**同步自增 —— 等结果回来才自增，上一轮那些还没结算的件就会被误判成
     * 本轮的战果，把新一轮的汇总写花。
     *
     * @param imageBase64 由页面编码；`null` / 空串 = 读图失败，与「没拍照」是**两件**事（各给一句提示）。
     */
    fun onRecognizePhoto(imageBase64: String?) {
        queue.beginRound()
        val current = state.value
        val photoName = current.photoName
        val locationId = current.selectedLocationId
        val locationPath = current.selectedLocationPath
        viewModelScope.launch {
            state.update { it.copy(aiState = AiState.Parsing) }

            // 先判密钥：此时给「去设置页填」比给「解析失败」有用得多（与录入页同一顺序）。
            if (!aiSettings.hasApiKey()) {
                fail(AiFailReason.NO_KEY)
                return@launch
            }

            // 三道前置在页面上都已门控（按钮此刻是灰的），这里是**兜底** —— 逻辑完整性不靠 UI 保证。
            if (photoName == null) {
                fail(AiFailReason.NEED_INPUT)
                return@launch
            }
            if (imageBase64.isNullOrEmpty()) {
                fail(AiFailReason.PHOTO_READ_FAILED)
                return@launch
            }
            if (locationId == null) {
                fail(AiFailReason.NEED_LOCATION_FOR_PHOTO)
                return@launch
            }

            val rows = aiIntake.parsePhoto(
                imageBase64 = imageBase64,
                locationId = locationId,
                locationPath = locationPath,
            )
            when {
                rows == null -> fail(AiFailReason.PARSE_FAILED)
                rows.isEmpty() -> fail(AiFailReason.NOTHING_RECOGNIZED)
                else -> {
                    // 出结果那一刻把这张图记下来：之后用户若换了图，逐条落库该带的仍是**这张**。
                    draftPhotoName = photoName
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
     * 点「确认」：**立刻弹下一件**，这一件的写库投给后台串行队列（`V2-FR-11`）。
     *
     * 全部语义在 [AiItemQueue.confirm]（先前进后落库、逐条落库而非攒批、跨轮保护、
     * 失败只计数不弹窗）—— 本类只做转译。
     */
    fun onConfirmItem() = queue.confirm()

    /**
     * 点「放弃」：跳过这一件（**不落库**），弹下一件。
     *
     * 【为什么不计入「没记下」】那一行汇总（`ai_home_unsaved`）是**写库失败**的信号。
     * 放弃是用户的主动选择，算成失败会让汇总说谎。
     */
    fun onDiscardItem() = queue.discard()

    /**
     * 点「关闭」= **放弃本次录入**：收掉弹窗、丢掉队列里剩下的件。
     *
     * 【已确认的件不回收】这是逐条落库的直接后果——「关闭」只终止剩下的流程。
     *
     * 【照片按需处理】一件都没落库时 `photoName` 仍留在页面上（用户可以再识别一次），
     * 离开本页时由页面删掉；若有件落库成功，它早已被清成 `null`。
     */
    fun onCloseIntake() = queue.close()

    // --- 照片文件的删除闸门 ------------------------------------------------------

    /**
     * 「删掉这个照片文件」是否安全（用户 2026-10-09 口径：**只删绑定，库中零引用才删文件**）。
     *
     * 两道闸缺一不可：
     * 1. **本轮没有在途落库** —— 在途任务引用的正是这张图，但它的 `photo_path` 还没写进库，
     *    此刻引用计数**恒为 0**，只看计数会误删后台正在用的文件；
     * 2. **库中引用计数为 0** —— 已经落库的那些件还指着它时不许删（多条记录共用一个文件名，
     *    见 `docs/实现约束.md` §2-12）。
     *
     * 调用方两处：页面离开（`onBack`）与照片行的替换 / 移除（`PhotoInputRow.canDeleteFile`）。
     * 两者都可能在「刚点完确认、写库还在后台跑」的时刻发生 —— 那时本方法返回 `false`，
     * 文件留给后台落的那些记录用。
     */
    suspend fun canDeletePhotoFile(photoName: String): Boolean =
        queue.state.value.pendingCommits == 0 && itemRepository.countByPhotoName(photoName) == 0

    // --- 内部 -----------------------------------------------------------------

    /** 落一行行内提示（**不弹窗**，`实现约束.md` §4-1）。 */
    private fun fail(reason: AiFailReason) {
        state.update { it.copy(aiState = AiState.Failed(reason = reason)) }
    }

    /**
     * 选中一个位置：**id 与显示路径在同一次 `state.update` 里落定**（与录入页同一硬要求）。
     *
     * 中间态（有 id、没路径）会让位置条空着，而用户要看的正是「家 › 客厅 › 抽屉」。路径默认现查树；
     * 调用方手上有更准的文本时（如刚新建的节点，树还没刷出来）经 [pathText] 直传。
     */
    private fun selectLocation(locationId: String, pathText: String? = null) {
        state.update {
            it.copy(
                selectedLocationId = locationId,
                selectedLocationPath = pathText ?: pathOf(locationTree.value, locationId),
            )
        }
    }

    /** 在展平树里取某位置的完整路径文本；未选（null）或树里找不到 = 空串。 */
    private fun pathOf(tree: List<LocationTreeRow>, locationId: String?): String =
        if (locationId == null) {
            ""
        } else {
            tree.firstOrNull { it.location.id == locationId }?.pathText.orEmpty()
        }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * 拍照 AI 录入页状态 —— **只剩这一页的输入态**。
 *
 * [aiEnabled] / [aiState] 只在**开着 AI 时**才有意义；关着时保持初值
 * （`aiEnabled = false`、`aiState = Hidden`）。
 *
 * 【结果段不在这里】队列、逐条进度与「已记下 / 没记下」汇总属 `AiQueueState`
 * （`ui/ai/AiItemQueue`），与语音 AI 录入页共用 —— 见 `PhotoAiIntakeViewModel.queueState`。
 * 位置选择弹层的**开合**与分类名单同样在独立 `StateFlow` 上（不进本状态，避免无关变更触发整页重组）。
 */
data class PhotoAiIntakeUiState(
    /**
     * `V2-FR-06`：AI 总开关（**出厂默认关**）。入口的可见性由首页按它判定；本页据它决定
     * 是否收掉队列与弹窗（关掉即回 `Hidden`）。
     */
    val aiEnabled: Boolean = false,
    /**
     * 识别状态机（`domain/ai/AiState`）。开关未开时为 [AiState.Hidden]。
     *
     * 本页**只用到三种**：`Hidden` / `Idle`（含「结果在弹窗里逐条确认」这一段） / `Parsing` /
     * `Failed`。`AiState.Draft` 在本页**不再产生** —— 那是页内确认卡的形态，录入页（P-ADD）仍在用。
     */
    val aiState: AiState = AiState.Hidden,
    /**
     * 当前这张照片的**文件名**（`null` = 还没拍）。
     *
     * 只存文件名，文件本体在 `filesDir/photos/`（由 `PhotoInputRow` 建、由页面在离开时删
     * 未落库的那张）。**第一件落库成功即清空** —— 照片已属于刚落库的那批记录，页面不能再持有
     * （否则「替换即删 / 离开即删」会删掉库里正在用的文件）。
     */
    val photoName: String? = null,
    /**
     * 选定的落点（`V2-FR-13`「位置先定后用」）。`null` = 还没选 ⇒ 识别按钮不可点。
     *
     * 进页时预选**最近使用**的位置（FR-11，与录入页同口径）；用户随时可在弹层里改。
     */
    val selectedLocationId: String? = null,
    /**
     * 已选位置的**完整面包屑路径**（`家 › 客厅 › 抽屉`）。与 [selectedLocationId] 恒同步 ——
     * 两者一律由 `selectLocation` 同一次写入，页面据此可直接把路径铺在位置条上。
     */
    val selectedLocationPath: String = "",
)
