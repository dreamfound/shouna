package com.dream.shouna.ui.screen.add

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.util.LocationPath
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * FR-09 / FR-10 / FR-12 + **位置必填**（ARCHITECTURE-P0 §0）的状态机。
 *
 * 位置必填的三条规则在此落地：
 * ① 位置条默认**预选最近使用**的位置（`last_used_at` 最近者）；首次安装无使用史 → 未选 → 不可保存。
 * ② 未选位置时 [QuickAddUiState.canSave] 恒为 false → 「保存并继续」不可点，用户必须先选或新建。
 * ③ 保存成功后**保留**位置（只清名称 / 分类 / 备注）→ 连续录入沿用同一位置，不打断（PRD US-02）。
 *
 * 被调用方：QuickAddRoute（hiltViewModel + 方法引用下传）
 */
@HiltViewModel
class QuickAddViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val locationRepository: LocationRepository,
    categoryRepository: CategoryRepository,
) : ViewModel() {

    private val state = MutableStateFlow(QuickAddUiState())

    val uiState: StateFlow<QuickAddUiState> = state.asStateFlow()

    /** FR-12：内置分类 chips 数据源。 */
    val categories: StateFlow<List<Category>> = categoryRepository.observeCategories()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /** 位置选择弹层的树数据（已展平、已过滤内置哨兵）。 */
    val locationTree: StateFlow<List<LocationTreeRow>> = locationRepository.observeTree()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS), emptyList())

    /** FR-11：最近使用的位置（弹层内的「最近」chips）。 */
    private val recent = MutableStateFlow<List<Location>>(emptyList())
    val recentLocations: StateFlow<List<Location>> = recent.asStateFlow()

    private val picker = MutableStateFlow(false)
    val isLocationPickerVisible: StateFlow<Boolean> = picker.asStateFlow()

    /**
     * FR-18（简化形态）：语音弹窗当前要填的字段；null = 弹窗关闭。
     *
     * 弹窗开合与「填哪个字段」是同一件事，合并成一个状态 —— 分两个状态会出现
     * 「弹窗开着但不知道往哪写」的中间态。
     *
     * 与 [isLocationPickerVisible] 同一取向：本 VM 只持有它，`.value` 的读取落在页面的
     * 下沉宿主里（见 `QuickAddScreen` 的 `QuickAddVoiceDialog`），避免弹窗开合把录入页
     * 整页拖进重组。
     */
    private val voice = MutableStateFlow<VoiceField?>(null)
    val voiceField: StateFlow<VoiceField?> = voice.asStateFlow()

    /** FR-14：「查看已有」要跳转的 itemId —— Route 收到后导航（本 VM 不持有 NavController）。 */
    private val viewSimilar = MutableSharedFlow<String>()

    val viewSimilarEvents: SharedFlow<String> = viewSimilar.asSharedFlow()

    init {
        // FR-03 / FR-02：位置条文本 = 已选位置在树里的**完整面包屑路径**（`家 › 储物间 › 纸箱-07`）。
        //
        // 【为什么必须同时订阅两个来源】Compose 侧只认 VM 给的这一个字符串，而它有两个因：
        // ① **树**变了（改名 / 新建 / 祖先被移走）→ 路径文本要跟着变；
        // ② **选中项**变了（用户在弹层里点了另一个位置）→ 路径文本要立刻换。
        // 只订阅树是不够的 —— 点选位置**不产生任何 DB 变更**，Room 的 Flow 不会重发，
        // 文本就会停在上一个值上（首帧没赶上树时更是永远空着，退化成「已选」两个字）。
        viewModelScope.launch {
            combine(
                locationRepository.observeTree(),
                state.map { it.selectedLocationId }.distinctUntilChanged(),
            ) { tree, selectedId -> selectedId to pathOf(tree, selectedId) }
                .collect { (selectedId, pathText) ->
                    // 只在这条结果仍对应「当前选中」时回写：combine 的一次发射可能晚于
                    // onLocationSelected 的原子写，别用旧 id 算出的文本盖掉新的。
                    state.update { current ->
                        if (current.selectedLocationId == selectedId) {
                            current.copy(selectedLocationPath = pathText)
                        } else {
                            current
                        }
                    }
                }
        }
    }

    /**
     * 初始位置是否已定 —— **[initialize] 的防重入守卫**。
     *
     * 用普通 `var` 而非 State：它不参与任何渲染，只是一个「这件事做过没有」的开关。
     */
    private var locationInitialized = false

    /**
     * 进入录入页时**只做一次**的位置初始化（FR-11）。
     *
     * 【为什么不在 `init` 里做】位置来源有两个：首页「＋ 记一件」进来 = **最近使用**；
     * P-BROWSE 的「＋ 往这里记一件」进来 = **当前浏览的那一层**。后者是**路由参数**，
     * `@Inject` 构造器带不了运行时参数，只有 Route 层知道 —— 故由 Route 显式调用本方法，
     * 与 `ItemEditViewModel.load(itemId)` 同一手法（参数是路由的，VM 不读路由）。
     *
     * 【为什么必须有守卫】本方法会**写**位置。配置变更（旋转 / 深色切换）会重建组合、
     * 让 Route 的 `LaunchedEffect` 重跑，而 VM 挂在返回栈上**存活不变** —— 没有守卫就会把
     * 用户已经改好的位置打回初始值。`init` 里那条订阅是纯读，不受影响。
     *
     * 优先级：**路由指定 > 最近使用**。指定位置未必在「最近」列表里，故路径走一次树快照现算；
     * 指定位置若已不在树里（参数失效）则回落「最近使用」，不让位置条停在「位置：」空串上。
     */
    fun initialize(initialLocationId: String?) {
        if (locationInitialized) return
        locationInitialized = true
        viewModelScope.launch {
            val recentList = locationRepository.recentUsed()
            recent.value = recentList
            // 一次树快照同时拿到 id 与路径（两者读的是同一张表）。只写 id 的话，位置条要等
            // init 里那条 combine 首次收到树才有文本，中间会露出一帧「位置：」。
            val tree = locationRepository.observeTree().first()
            val preferredId = listOfNotNull(initialLocationId, recentList.firstOrNull()?.id)
                .firstOrNull { id -> tree.any { it.location.id == id } }
            if (preferredId != null) {
                selectLocation(locationId = preferredId, pathText = pathOf(tree, preferredId))
            }
        }
    }

    fun onNameChange(value: String) {
        // 一动手输入，上一件刚保存时的 FR-14 提示就失效了（它说的是**刚存进去的那件**，
        // 不是正在输入的名字）→ 一并清掉，避免用户对着过期的「已有 2 件相似」发愣。
        state.update { it.copy(inputName = value, similarCount = 0, similarItemId = null) }
        recomputeCanSave()
    }

    fun onCategorySelected(categoryId: String?) {
        // categoryId = null 即「再次点击取消选中」（FR-12 可跳过）。
        state.update { it.copy(selectedCategoryId = categoryId) }
    }

    fun onNoteChange(value: String) {
        // 原样保存输入，空白归一延迟到落库时（见 onSaveAndContinue）。
        state.update { it.copy(note = value) }
    }

    fun onOpenLocationPicker() {
        picker.value = true
    }

    fun onDismissLocationPicker() {
        picker.value = false
    }

    // --- FR-18（简化形态）：语音输入 --------------------------------------------------

    /** 点某个输入框尾部的 🎤 → 打开语音弹窗，并记住结果该写进哪个字段。 */
    fun onRequestVoice(field: VoiceField) {
        voice.value = field
    }

    fun onDismissVoice() {
        voice.value = null
    }

    /**
     * 识别结果落地。**追加**而不是替换：语音在这里的定位是「另一种打字方式」，
     * 与系统输入法的听写行为一致（用户先手打一半、再补一句是常态）。
     *
     * 刻意**复用 [onNameChange] / [onNoteChange]** 而不是直接改 state：那两条路径上挂着
     * 「一动手输入就清掉上一件的 FR-14 相似提示」「重算 canSave」等副作用，绕过它们会让
     * 语音填进去的文字与手打出来的文字行为不一致。
     */
    fun onVoiceResult(field: VoiceField, text: String) {
        val spoken = text.trim()
        if (spoken.isNotEmpty()) {
            when (field) {
                VoiceField.NAME -> onNameChange(state.value.inputName + spoken)
                VoiceField.NOTE -> onNoteChange(state.value.note.orEmpty() + spoken)
            }
        }
        voice.value = null
    }

    // --- FR-16：照片 ----------------------------------------------------------------

    /**
     * 照片文件名变化（拍照 / 相册 / 移除的统一入口）。文件本体的建删由 UI 层的
     * `PhotoInputRow` 完成，本 VM 只持有名字，保存时落库。
     */
    fun onPhotoChanged(photoName: String?) {
        state.update { it.copy(photoName = photoName) }
    }

    /** 在树里选中一个已有位置（树行与「最近」chips 共用这一条）。 */
    fun onLocationSelected(locationId: String) {
        // 路径与 id **同一次写入**。不能只写 id 然后指望树 Flow 补文本：点选不改 DB，
        // Flow 不会重发，位置条就会空在那儿（进而退化成「已选」）。
        selectLocation(locationId)
        picker.value = false
    }

    /**
     * 「或输入位置」：新建一个位置节点并立即选中。
     * 父级 = **当前已选位置**（未选则建在根级）—— 这样「输入位置」总是落在用户正在看的层级下。
     */
    fun onCreateLocation(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            // 父级此刻就是选中项，它的路径已经就绪 ⇒ 直接拼出完整路径，不必等树 Flow 刷出
            // 这个新节点（新建后树确实会重发，但那是异步的，中间会闪一帧没有前缀的名字）。
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

    /** 「保存并继续」：清空名称 / 分类 / 备注，**保留位置**，计数器 +1，零弹窗。 */
    fun onSaveAndContinue() {
        val current = state.value
        if (!current.canSave) return
        val locationId = current.selectedLocationId ?: return
        val name = current.inputName.trim()

        viewModelScope.launch {
            // FR-14：**先查重、再落库**。顺序不能反 —— 落库之后再查，刚写进去的这一条会被自己
            // 命中（归一化名完全相同），提示变成恒真，这个功能就废了。
            val similar = try {
                itemRepository.findSimilar(name)
            } catch (_: Throwable) {
                emptyList()
            }

            try {
                itemRepository.createItemQuick(
                    name = name,
                    categoryId = current.selectedCategoryId,
                    // 空白备注归一为 null（StoredItem.note 可空）。
                    note = current.note?.trim()?.ifBlank { null },
                    locationId = locationId,
                    // FR-16：照片文件已由 UI 层写好，这里只落文件名。
                    photoPath = current.photoName,
                )
            } catch (_: Throwable) {
                // FR-10「零弹窗」：不做任何错误弹窗，保持输入原样、计数器不动，由用户重试。
                return@launch
            }
            // 清空输入（**保留位置**）；计数器 +1；焦点由 QuickAddScreen 依 sessionCount 变化重新请求。
            // FR-14 的提示**非阻塞**：只带出条数与跳转目标，保存已经成功，不拦任何人（P1 §8.1-12）。
            // FR-16：`photoName` 一并清空 —— 照片已经属于上一条记录，不能跟到下一条身上。
            state.update {
                it.copy(
                    inputName = "",
                    selectedCategoryId = null,
                    note = null,
                    photoName = null,
                    sessionCount = it.sessionCount + 1,
                    similarCount = similar.size,
                    // 取最近创建的一条（查询按 created_at 倒序）作为「查看」的落点。
                    similarItemId = similar.firstOrNull()?.id,
                )
            }
            recomputeCanSave()
            // 这次录入刷新了 last_used_at → 同步一次「最近」，使弹层顺序立刻正确。
            recent.value = locationRepository.recentUsed()
        }
    }

    /**
     * FR-14：「查看已有」——跳到查重命中的那条记录（导航由 Route 层下发）。
     *
     * 之所以是「跳转」而不是「阻止保存」：FR-14 在 `prd/05` 标 F2，触发点却在 F1 的 P-ADD，
     * 本页按「**提示轻量、不引入新名词、无门控**」处置（P1 §8.1-12）——保存永远可继续。
     */
    fun onViewSimilar() {
        val itemId = state.value.similarItemId ?: return
        viewModelScope.launch { viewSimilar.emit(itemId) }
    }

    /**
     * 选中一个位置：**id 与显示路径在同一次 `state.update` 里落定**。
     *
     * 「同一次」是硬要求 —— 中间态（有 id、没路径）在位置条上就是一句「已选」，
     * 而用户要看的是「家 › 储物间 › 纸箱-07」。路径默认现查树；调用方手上有更准的文本时
     * （如刚新建的节点，树还没刷出来）经 [pathText] 直传。
     */
    private fun selectLocation(locationId: String, pathText: String? = null) {
        state.update {
            it.copy(
                selectedLocationId = locationId,
                selectedLocationPath = pathText ?: pathOf(locationTree.value, locationId),
            )
        }
        recomputeCanSave()
    }

    /** 在展平树里取某位置的完整路径文本；未选（null）或树里找不到 = 空串。 */
    private fun pathOf(tree: List<LocationTreeRow>, locationId: String?): String =
        if (locationId == null) {
            ""
        } else {
            tree.firstOrNull { it.location.id == locationId }?.pathText.orEmpty()
        }

    /** canSave 口径（位置必填）：**名称非空 ∧ 位置已选**。 */
    private fun recomputeCanSave() {
        state.update {
            it.copy(canSave = it.inputName.isNotBlank() && it.selectedLocationId != null)
        }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

data class QuickAddUiState(
    val inputName: String = "",
    val selectedCategoryId: String? = null,
    val note: String? = null,
    /**
     * FR-16：本条记录要落的照片文件名（`null` = 没拍照）。
     *
     * 只存文件名，文件本体在 `filesDir/photos/`（由 `PhotoInputRow` 维护）。保存成功后清空 ——
     * 照片跟着已存的那一条走，不跟到下一条身上。
     */
    val photoName: String? = null,
    val sessionCount: Int = 0,
    /** **名称非空 ∧ 位置已选** 才为 true（位置必填，ARCHITECTURE-P0 §0）。 */
    val canSave: Boolean = false,
    /** 已选位置 id；null = 未选（不可保存）。 */
    val selectedLocationId: String? = null,
    /**
     * 已选位置的**完整面包屑路径**（`家 › 储物间 › 纸箱-07`，由 `LocationPath` 派生）；未选为空串。
     *
     * 与 [selectedLocationId] 恒同步：两者一律由 `selectLocation` 同一次写入，页面据此可以直接
     * 把路径铺在位置条上，不需要「找不到路径时显示什么」的兜底文案。
     */
    val selectedLocationPath: String = "",
    /**
     * FR-14：保存后查重命中的「同名 / 高度相似」件数；0 = 不提示。
     *
     * **非阻塞**：只驱动一行提示 + 一个「查看」入口，不阻断保存、不弹对话框
     * （守 `实现约束.md` §4-1 零弹窗与 §4-4 连续录入期间不弹位置/分类对话框）。
     * 在**落库前**算好（落库后再查会把刚写的这条自己查出来），随后随保存成功一起进 UiState。
     */
    val similarCount: Int = 0,
    /** FR-14：「查看」要跳转到的已有物品 id。null = 无提示。 */
    val similarItemId: String? = null,
)

/**
 * FR-18（简化形态）：语音结果该写进哪个输入框。
 *
 * 只在录入页内部使用（名称框与备注框各一个 🎤），所以不做成通用组件参数 ——
 * `VoiceInputDialog` 本身与字段无关，由本页决定结果的去向。
 */
enum class VoiceField {
    /** 物品名称框。 */
    NAME,

    /** 备注框（折叠区内）。 */
    NOTE,
}
