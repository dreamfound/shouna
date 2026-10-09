package com.dream.shouna.ui.screen.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.AiIntake
import com.dream.shouna.data.repository.AiSettingsRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.domain.ai.AiFailReason
import com.dream.shouna.domain.ai.AiState
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.util.LocationPath
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
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
 * 首页（docs/实现约束.md）。
 *
 * 【状态来源有两个，且**都不可省**】
 * - `isEmpty` 来自数据库流（有没有记录）；
 * - AI 入口的开关与状态机是**页内态**（`local`）—— 它不落库、也不该落库。
 *
 * 两者用 `combine` 合成一个 UiState：分开暴露两个 StateFlow 会让页面去拼「什么时候显示空态
 * 文案」这件事，那是展示派生，`实现约束.md` §2-2 要求留在 VM。
 *
 * 【V2 起在首页挂两条 AI 旁路】`docs/code-map/02-数据与领域.md` AI-02 + `V2-FR-10`（语音）/ `V2-FR-11`（照片）——
 * 两条都是「页内确认卡 → 用户点确认才落库」（`V2-FR-04`）。与录入页的差别都落在这一层：
 * ① 没有主表单 ⇒ 1 件也直接落库，不回填；
 * ② **语音通道**没有「已选位置」⇒ 位置解析不出时**没有兜底**，只能跳过并把件数明说给用户。
 *
 * 【两条通道的位置来源不同，这是本类最需要看清的一处】
 * - **语音**：位置由模型从那句话里读出来（每行各带），本类只负责"读不出来就跳过"；
 * - **拍照**：位置由**用户在拍照前选定**（`V2-FR-13`「位置先定后用」）—— 模型一律不返回位置，
 *   选定值由 [onRecognizePhoto] 交给 `AiIntake.parsePhoto` **直接写进每一行**，于是落库路径
 *   与语音**完全共用**，[onAiApply] 里没有任何按通道分叉的判断。
 *
 * 【AI 关着时本类不做任何事】总开关出厂默认关；关着时两个入口都不出现（[HomeUiState.aiEnabled]），
 * 也不产生任何网络请求（`V2-AI-PRD` §5 硬约束）。
 *
 * 被调用方：HomeRoute（hiltViewModel + 方法引用下传）
 */
@HiltViewModel
class HomeViewModel @Inject constructor(
    itemRepository: ItemRepository,
    private val locationRepository: LocationRepository,
    private val aiIntake: AiIntake,
    private val aiSettings: AiSettingsRepository,
) : ViewModel() {

    /** 页内态：AI 入口的开关与状态机。`isEmpty` 不在其中（见类注释）。 */
    private val local = MutableStateFlow(HomeUiState())

    /** 位置选择弹层的树数据（已展平、已过滤内置哨兵）—— 与录入页读的是同一条流。 */
    val locationTree: StateFlow<List<LocationTreeRow>> = locationRepository.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /** FR-11：最近使用的位置（弹层内的「最近」chips）。 */
    private val recent = MutableStateFlow<List<Location>>(emptyList())
    val recentLocations: StateFlow<List<Location>> = recent.asStateFlow()

    /**
     * 位置选择弹层的开合。与录入页同一手法：本 VM 只持有它，`.value` 的读取落在页面的
     * 下沉宿主里，避免翻开关把首页 body 整页拖进重组。
     */
    private val picker = MutableStateFlow(false)
    val isLocationPickerVisible: StateFlow<Boolean> = picker.asStateFlow()

    /**
     * 「拍照AI记」页内面板的开合。同上，读取下沉到宿主。
     */
    private val photoPanel = MutableStateFlow(false)
    val isPhotoPanelOpen: StateFlow<Boolean> = photoPanel.asStateFlow()

    /**
     * 当前草稿卡所对应的照片文件名（`V2-FR-11` 首页通道）。
     *
     * 【为什么单独记账，而不直接用 [HomeUiState.photoName]】草稿是**某一张图**的产物。用户拿到卡之后
     * 若又去面板里换了一张图，落库该带的仍是**出卡时那张**。故在出卡那一刻快照，落库 / 忽略后清空
     * —— 与 [onAiApply] 里先取定 `fallbackLocationId` 同一个理由：**中途的写不该改变去向**。
     *
     * 用普通 `var` 而非 State：它不参与任何渲染，只是一个「这批草稿配哪张图」的记账。
     */
    private var draftPhotoName: String? = null

    val uiState: StateFlow<HomeUiState> = combine(
        itemRepository.observeItemSummaries(),
        local,
    ) { items, home ->
        home.copy(isEmpty = items.isEmpty())
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), HomeUiState())

    init {
        // AI 总开关的订阅：入口**可见性的唯一真源**（与录入页读到的是同一条流，两页必须一致）。
        //
        // 关掉开关时**连卡片一起收掉**（回 `Hidden`）：开关的语义是「立刻停止一切请求」，
        // 让一张已生成的草稿卡继续挂在页面上，等于给用户一个已经失效的入口。
        // 反过来，开关刚打开时从 `Hidden` 落到 `Idle`；已有草稿（非 Hidden）则原样保留。
        viewModelScope.launch {
            aiSettings.observeEnabled().collect { enabled ->
                local.update { current ->
                    current.copy(
                        aiEnabled = enabled,
                        aiState = when {
                            !enabled -> AiState.Hidden
                            current.aiState is AiState.Hidden -> AiState.Idle
                            else -> current.aiState
                        },
                    )
                }
            }
        }

        // 位置条文本的派生（与录入页逐字同口径，见 `QuickAddViewModel.init`）：路径有**两个因** ——
        // ① 树变了（改名 / 新建 / 祖先被移走）；② 选中项变了。
        // 只订阅树是不够的：点选位置**不产生任何 DB 变更**，Room 的 Flow 不会重发，位置条会停在
        // 上一个值上。两者必须 combine，且回写前要确认这条结果仍对应当前选中项（combine 的一次
        // 发射可能晚于 `selectLocation` 的原子写，别用旧 id 算出的文本盖掉新的）。
        viewModelScope.launch {
            combine(
                locationRepository.observeTree(),
                local.map { it.selectedLocationId }.distinctUntilChanged(),
            ) { tree, selectedId -> selectedId to pathOf(tree, selectedId) }
                .collect { (selectedId, pathText) ->
                    local.update { current ->
                        if (current.selectedLocationId == selectedId) {
                            current.copy(selectedLocationPath = pathText)
                        } else {
                            current
                        }
                    }
                }
        }

        // 「拍照AI记」的位置默认值 = **最近使用**（FR-11，与录入页同口径）。
        //
        // 【为什么不需要录入页那套 `initialize(initialLocationId)` 守卫】那里的守卫是为了挡住
        // **路由参数**在配置变更后重放；首页没有路由参数，本 VM 在返回栈上只建一次，`init` 天然
        // 只跑一遍。预选值不写库，用户改过之后也没有任何东西会把它打回去。
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

    // --- V2-FR-10：语音句录入 ------------------------------------------------

    /**
     * 语音识别结果（**用户不接触原始文本**，`V2-FR-10`）。
     *
     * 识别完直接进 AI，中间不落任何输入框：首页没有「当前字段」这个概念，文本也不该在这里被
     * 用户看到 —— 他看到的只有下面那张逐行的确认卡。
     *
     * 顺序与录入页一致：先判密钥（此时给「去设置页填」比给「解析失败」有用），再解析。
     */
    fun onSpokenText(text: String) {
        val spoken = text.trim()
        if (spoken.isEmpty()) return
        viewModelScope.launch {
            // 新一轮开始 ⇒ 上一轮的落库汇总同时失效，一并清掉；上一批草稿配的那张图也一并解绑
            // （语音这批本来就不带图，但上面那张卡可能是拍照通道留下的，不能让它的图跟过来）。
            draftPhotoName = null
            local.update { it.copy(aiState = AiState.Parsing, savedCount = null, unsavedCount = 0) }

            if (!aiSettings.hasApiKey()) {
                fail(AiFailReason.NO_KEY)
                return@launch
            }

            val rows = aiIntake.parseText(spoken)
            when {
                // 失败只有一条路径（闸门返 null → 行内提示），不弹窗、不阻塞、不计数
                // （`V2-FR-07` / `V2-COST-04`）。
                rows == null -> fail(AiFailReason.PARSE_FAILED)
                rows.isEmpty() -> fail(AiFailReason.NOTHING_RECOGNIZED)
                else -> local.update { it.copy(aiState = AiState.Draft(rows = rows)) }
            }
        }
    }

    // --- V2-FR-11：首页「拍照AI记」入口 --------------------------------------

    /**
     * 展开页内面板（位置条 + 照片行 + 识别按钮）。
     *
     * 【为什么是页内面板而不是弹窗】① 面板里要开位置选择，而它本身是个 `ModalBottomSheet` ——
     * 弹窗套弹层是两个独立窗口叠着，测量与返回键都要额外照顾；② 本工程实测按需创建的新弹层
     * 冷首帧约 700ms（`LocationPickerSheet` 冷 734.6 / 温 520.7ms），页内展开只承担重组成本；
     * ③ 与首页语音入口同形态 —— 首页只该有一种「AI 入口长什么样」。
     */
    fun onOpenPhotoPanel() {
        photoPanel.value = true
    }

    /**
     * 收起面板。
     *
     * 【只关面板，不动照片】照片文件的删除由**页面**在用户点「收起」时一并做掉（VM 不碰文件系统，
     * 见类注释）。已落库照片的 `photoName` 在 [onAiApply] 里已被清空 ⇒ 那一刻页面删的是一个 `null`，
     * **不会**误删刚写进库里那批记录正在用的文件。这个先后顺序是本页最容易改错的地方。
     */
    fun onClosePhotoPanel() {
        photoPanel.value = false
    }

    /** 照片文件名变化（拍照 / 相册 / 移除的统一入口）。文件本体的建删由 `PhotoInputRow` 负责。 */
    fun onPhotoChanged(photoName: String?) {
        local.update { it.copy(photoName = photoName) }
    }

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
            val parentPath = local.value.selectedLocationPath
            val created = locationRepository.create(
                name = trimmed,
                parentId = local.value.selectedLocationId,
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

    /**
     * 「识别」：照片 → 草稿行（`V2-FR-11`）。**base64 由页面编码好传进来** —— 守本工程「文件 IO
     * 全留在 Compose 层」的分层（本 VM 不持 `Context`），与录入页 `onAiParse` 同一约定。
     *
     * 【位置是这条通道的前置（`V2-FR-13`「位置先定后用」）】没选位置就**不调模型**：模型这一路
     * 永远不返回位置（`AiPrompt` 的契约段强制 `location_path = null`），没有目标位置这一步就无从
     * 落地。选定值随照片一起交给 `parsePhoto`，由它**写进每一行**。
     *
     * 【出卡即收起面板】卡片落在首页（与语音共用 [AiStatusSection]），面板再占着没有意义；收起
     * **不等于删图** —— 文件的清理只挂在用户主动点「收起」上（见 [onClosePhotoPanel]）。
     *
     * @param imageBase64 由页面编码；`null` / 空串 = 读图失败，与「没拍照」是**两件**事（各给一句提示）。
     */
    fun onRecognizePhoto(imageBase64: String?) {
        val current = local.value
        val photoName = current.photoName
        val locationId = current.selectedLocationId
        val locationPath = current.selectedLocationPath
        viewModelScope.launch {
            // 同上：新一轮开始 ⇒ 上一轮的汇总与照片记账一并清掉。
            draftPhotoName = null
            local.update { it.copy(aiState = AiState.Parsing, savedCount = null, unsavedCount = 0) }

            // 先判密钥：此时给「去设置页填」比给「解析失败」有用得多（与语音同一顺序）。
            if (!aiSettings.hasApiKey()) {
                fail(AiFailReason.NO_KEY)
                return@launch
            }

            // 三道前置在页面上都已门控（按钮此刻是灰的），这里是**兜底** —— 逻辑完整性不靠 UI 保证。
            if (photoName == null) {
                fail(AiFailReason.NEED_INPUT)
                return@launch
            }
            if (imageBase64 == null || imageBase64.isEmpty()) {
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
                    // 出卡那一刻把这张图记下来：之后用户若在面板里换了图，落库该带的仍是**这张**。
                    draftPhotoName = photoName
                    photoPanel.value = false
                    local.update { it.copy(aiState = AiState.Draft(rows = rows)) }
                }
            }
        }
    }

    // --- 两条通道共用：卡片交互与落库 ------------------------------------------

    /** 卡片上逐行勾选 / 取消（`V2-FR-11`「默认全选，可逐行取消」）。 */
    fun onAiToggle(rowIndex: Int) {
        local.update { current ->
            val draft = current.aiState as? AiState.Draft ?: return@update current
            if (rowIndex !in draft.rows.indices) return@update current
            current.copy(
                aiState = AiState.Draft(
                    rows = draft.rows.mapIndexed { index, row ->
                        if (index == rowIndex) row.copy(selected = !row.selected) else row
                    },
                ),
            )
        }
    }

    /**
     * 点「记下 / 全部记下」：**一次确认、一次落库**（`V2-FR-11`）。
     *
     * 【为什么首页没有「填入」】首页背后没有主表单，1 件也无处回填 —— 所以确认卡这一侧
     * `canFillBack = false`，1 件与多件是同一个动作。
     *
     * 【兜底位置为什么是 null】**语音通道**没有「已选位置」：一句话里完全没提位置的那件会被跳过
     * （位置必填是不变量，`docs/PRD.md`），跳过数经 [HomeUiState.unsavedCount] 明说给用户
     * —— 不声不响地少记是最坏的失败方式。
     * 拍照通道**不靠这里兜底**：它的位置已在 `parsePhoto` 里写进每一行（`V2-FR-13`），所以两条
     * 通道在这一处没有分叉。
     *
     * 【照片只挂在拍照通道的产物上】[draftPhotoName] 由 [onRecognizePhoto] 在出卡时快照，语音草稿
     * 走到这里它一定是 null。落库后清掉面板态的 `photoName`：那张图已经属于刚落库的那批记录，
     * 既不该被下一次识别复用，更不该被照片行的「替换即删」连带删掉（那会弄丢库里记录的图）。
     */
    fun onAiApply() {
        val current = local.value
        val draft = current.aiState as? AiState.Draft ?: return
        val rows = draft.rows.filter { it.selected }
        if (rows.isEmpty()) {
            fail(AiFailReason.NOTHING_SELECTED)
            return
        }
        // 照片在起协程前取定，避免落库途中被别的写改掉（与 `fallbackLocationId` 同一理由）。
        val photoPath = draftPhotoName
        viewModelScope.launch {
            val result = aiIntake.commitRows(
                rows = rows,
                fallbackLocationId = null,
                photoPath = photoPath,
            )
            draftPhotoName = null
            local.update {
                it.copy(
                    aiState = if (it.aiEnabled) AiState.Idle else AiState.Hidden,
                    savedCount = result.saved,
                    unsavedCount = result.notSaved,
                    photoName = null,
                )
            }
            // 这次落库刷新了位置的 `last_used_at` → 同步一次「最近」，使弹层顺序立刻正确。
            if (result.saved > 0) recent.value = locationRepository.recentUsed()
        }
    }

    /**
     * 点「忽略」：清卡片与汇总，**库与页面别处都不动**（`V2-FR-04` 的另一半）。
     *
     * 【为什么只解绑草稿的图、不清面板态的照片】用户忽略这一批草稿之后，那张照片仍在面板里
     * （面板只是收起了），他再点一次「拍照AI记」就能看到它并重新识别 —— 这是想要的行为。
     * 文件的清理只挂在用户主动收起面板上，见 [onClosePhotoPanel]。
     */
    fun onAiDismiss() {
        draftPhotoName = null
        local.update {
            it.copy(
                aiState = if (it.aiEnabled) AiState.Idle else AiState.Hidden,
                savedCount = null,
                unsavedCount = 0,
            )
        }
    }

    /** 落一行行内提示（**不弹窗**，`实现约束.md` §4-1）。 */
    private fun fail(reason: AiFailReason) {
        local.update { it.copy(aiState = AiState.Failed(reason = reason)) }
    }

    /**
     * 选中一个位置：**id 与显示路径在同一次 `state.update` 里落定**（与录入页同一硬要求）。
     *
     * 中间态（有 id、没路径）会让位置条空着，而用户要看的正是「家 › 客厅 › 抽屉」。路径默认现查树；
     * 调用方手上有更准的文本时（如刚新建的节点，树还没刷出来）经 [pathText] 直传。
     */
    private fun selectLocation(locationId: String, pathText: String? = null) {
        local.update {
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
 * 首页状态。
 *
 * [aiEnabled] / [aiState] / [savedCount] / [unsavedCount] 全部只在**开着 AI 时**才有意义；
 * 关着时它们保持初值（`aiEnabled = false`、`aiState = Hidden`），页面据此完全不画那一块。
 *
 * [photoName] / [selectedLocationId] / [selectedLocationPath] 属于「拍照AI记」那一块的面板态，
 * 同样只在开关开着、且用户展开过面板时才有值。面板与弹层的**开合**不在这里（它们是独立
 * `StateFlow`）—— 见 `HomeViewModel.isPhotoPanelOpen` 的注释：开合一旦进 UiState，翻一次就会
 * 连带重跑整页 body。
 */
data class HomeUiState(
    /** 一件记录都没有 —— 驱动空态文案。 */
    val isEmpty: Boolean = true,
    /**
     * `V2-FR-06`：AI 总开关（**出厂默认关**）。首页语音入口**可见性的唯一真源** ——
     * 关着时按钮不出现、零网络请求，行为与 v1.5 完全一致（`V2-AI-PRD` §5 硬约束）。
     */
    val aiEnabled: Boolean = false,
    /** 语音入口的状态机（`domain/ai/AiState`）。开关未开时为 [AiState.Hidden]。 */
    val aiState: AiState = AiState.Hidden,
    /** 最近一次批量落库**成功记下**的件数；null = 本轮还没有落库动作。 */
    val savedCount: Int? = null,
    /**
     * 最近一次批量落库**没记下**的件数（缺位置被跳过 / 写库失败）。
     *
     * 必须让用户看到：勾了 5 件却只进库 4 件时，卡片直接消失会让人以为全都记下了。
     */
    val unsavedCount: Int = 0,
    /**
     * 「拍照AI记」面板里当前这张照片的**文件名**（`null` = 还没拍）。
     *
     * 只存文件名，文件本体在 `filesDir/photos/`（由 `PhotoInputRow` 建、由页面在收起面板时删）。
     * **落库成功后清空** —— 照片已属于刚落库的那批记录，不能再被下一次识别复用或被替换删除。
     */
    val photoName: String? = null,
    /**
     * 「拍照AI记」选定的落点（`V2-FR-13`「位置先定后用」）。`null` = 还没选 ⇒ 识别按钮不可点。
     *
     * 打开面板时预选**最近使用**的位置（FR-11，与录入页同口径）；用户随时可在弹层里改。
     */
    val selectedLocationId: String? = null,
    /**
     * 已选位置的**完整面包屑路径**（`家 › 客厅 › 抽屉`）。与 [selectedLocationId] 恒同步 ——
     * 两者一律由 `selectLocation` 同一次写入，页面据此可直接把路径铺在位置条上。
     */
    val selectedLocationPath: String = "",
)
