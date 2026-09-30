package com.dream.shouna.ui.screen.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ConfigRepository
import com.dream.shouna.data.repository.LocationDeleteMode
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * P-BROWSE（FR-01 / 02 / 03 / 05）的状态机。
 *
 * 层级遍历用**下钻**而非树形展开：`currentId` 决定当前层，页面只渲染「该层的子位置」+
 * 「该位置的直属物品」。理由：下钻天然带面包屑、不需要维护展开集，而且深树时不会一次渲染上百行。
 *
 * 位置选择弹层（`LocationPickerSheet`）走的是另一条路径 —— 它需要一次看见整棵树，故用展平列表。
 *
 * 物品行的「最后确认 + ⚠」在本层组装（P0-03 ③；Screen 不持有 `TimeUtil` / `ConfigRepository`）。
 *
 * 被调用方：`LocationBrowseRoute`（hiltViewModel + load / create / rename / delete）
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class LocationBrowseViewModel @Inject constructor(
    private val locationRepository: LocationRepository,
    private val configRepository: ConfigRepository,
    private val timeUtil: TimeUtil,
) : ViewModel() {

    /** 当前层；null = 根级（全部位置）。 */
    private val currentId = MutableStateFlow<String?>(null)

    private val itemsInCurrent: Flow<List<StoredItem>> = currentId.flatMapLatest { id ->
        // 根级不展示物品（根级本身不是一个位置），只展示顶层位置。
        if (id == null) flowOf(emptyList()) else locationRepository.observeItemsIn(id)
    }

    val uiState: StateFlow<LocationBrowseUiState> = combine(
        locationRepository.observeTree(),
        itemsInCurrent,
        currentId,
        // 与 `SearchViewModel` 同一口径：阈值作为一条流进 combine，保证**首帧**的超期判定
        // 就用真实值，而不是先拿一个猜的默认值渲染一屏再纠正（FR-27）。
        flow { emit(configRepository.thresholdMonths()) },
    ) { tree, items, id, thresholdMonths ->
        val currentRow = id?.let { wanted -> tree.firstOrNull { it.location.id == wanted } }
        val now = timeUtil.nowMillis()
        LocationBrowseUiState(
            locationId = id,
            pathText = currentRow?.pathText ?: ROOT_PATH_TEXT,
            // 只渲染**当前层的直属**子位置（下钻语义）；整棵树另由 allRows 提供给删除对话框的迁移目标选择。
            children = tree.filter { it.location.parentId == id },
            items = items.map { item -> item.toItemUi(thresholdMonths, now) },
            allRows = tree,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = LocationBrowseUiState(),
    )

    /** 进入某一层；null = 根级。 */
    fun load(locationId: String?) {
        currentId.value = locationId
    }

    /** FR-01：在当前层下新建子位置。 */
    fun onCreateChild(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return
        viewModelScope.launch {
            locationRepository.create(name = trimmed, parentId = currentId.value)
        }
    }

    /** FR-03：重命名当前层（改名后所有路径展示自动跟随，不动物品记录）。 */
    fun onRenameCurrent(name: String) {
        val id = currentId.value ?: return
        viewModelScope.launch {
            locationRepository.rename(id = id, name = name)
        }
    }

    /** FR-03：当前层备注。 */
    fun onSetNoteCurrent(note: String?) {
        val id = currentId.value ?: return
        viewModelScope.launch {
            locationRepository.setNote(id = id, note = note)
        }
    }

    /**
     * FR-05：删除当前层（以子树为单位）。
     * 成功后回到根级 —— 当前层已不存在，留在原地会显示一个不存在的路径。
     * 失败（迁移目标非法等）时不改层级，由 UI 侧的选择约束避免（对话框只提供合法目标）。
     */
    fun onDeleteCurrent(mode: LocationDeleteMode, migrateTargetId: String? = null) {
        val id = currentId.value ?: return
        viewModelScope.launch {
            val deleted = locationRepository.delete(id = id, mode = mode, migrateTargetId = migrateTargetId)
            if (deleted) {
                currentId.value = null
            }
        }
    }

    /**
     * 物品行（P0-03 ③）：这里**不带路径** —— 页面顶部的面包屑已经说明「我在哪一层」，
     * 行内再重复一遍是噪音。只补「最后确认」与 FR-27 的超期标记（列表行同样只放一个时间）。
     */
    private fun StoredItem.toItemUi(thresholdMonths: Int, now: Long): LocationItemUi = LocationItemUi(
        id = id,
        name = name,
        subtitle = if (lastConfirmedAt == null) {
            TimeUtil.NEVER_CONFIRMED
        } else {
            "最后确认 ${timeUtil.relativeText(lastConfirmedAt, now)}"
        },
        isOverdue = timeUtil.isOverdue(lastConfirmedAt, thresholdMonths, now),
    )

    // --- P1-02（FR-04 / 06 / 28）：位置树深化的动作入口（骨架） --------------------------
    // 只立签名：对应的界面入口（移动 / 合并的目标选择弹层、临时标记、批量确认按钮）在 P1-02
    // 实现期与 `LocationMoveSheet` / `TemporaryMark` 一并挂上，**此刻 Screen 不调用它们**
    // —— 避免出现「可点但会走到 TODO 桩」的中间态。

    /** FR-04：把当前层（**含整棵子树**）移动到 [newParentId] 下。 */
    fun onMoveCurrent(newParentId: String?) {
        TODO("P1-02 ①: repository.move(currentId, newParentId)；失败不改状态")
    }

    /** FR-04：把当前层合并进 [targetId]（当前层的子位置与物品改挂目标，随后当前层消失）。 */
    fun onMergeCurrent(targetId: String) {
        TODO("P1-02 ②: repository.merge(currentId, targetId)；成功后回根级")
    }

    /** FR-06：标记 / 取消把当前层作为临时位置。 */
    fun onToggleTemporaryCurrent(flag: Boolean) {
        TODO("P1-02 ⑤: repository.setTemporary(currentId, flag)")
    }

    /**
     * FR-28：对当前层**含子层**的全部物品一键确认。
     * 界面上必须先弹出影响范围（「含子层共 M 件」）再执行 —— 别让用户以为只动了本层。
     */
    fun onConfirmAllInCurrent() {
        TODO("P1-02 ⑥: repository.confirmByLocation(currentId)；确认前先展示含子层件数")
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

data class LocationBrowseUiState(
    /** 当前层；null = 根级。 */
    val locationId: String? = null,
    /** 面包屑文本；根级显示 [ROOT_PATH_TEXT]。 */
    val pathText: String = ROOT_PATH_TEXT,
    /**
     * P1-02（FR-06）：当前层是否被标记为**临时位置**。
     * 骨架期恒 false：由 `location.is_temporary` 读出后填入，随同层数据一并组装。
     */
    val isTemporary: Boolean = false,
    /** 当前层的直属子位置。 */
    val children: List<LocationTreeRow> = emptyList(),
    /** 当前层的直属物品（FR-01）。 */
    val items: List<LocationItemUi> = emptyList(),
    /**
     * P1-02（FR-21）：当前层**含子层**的物品件数。
     * 骨架期恒 0；页面届时展示「本层 N 件 / 含子层共 M 件」（递归口径见 P1 §8.1-7）。
     */
    val subtreeItemCount: Int = 0,
    /** 整棵位置树（供删除时的「迁移到…」目标选择）。 */
    val allRows: List<LocationTreeRow> = emptyList(),
) {
    val isRoot: Boolean get() = locationId == null
}

/**
 * 位置浏览的物品行（P0-03 ③）：名称 + 「最后确认 …」+ FR-27 超期标记。
 * 与 `SearchResultUi` 的差别：这里不含路径（本页有面包屑）。
 */
data class LocationItemUi(
    val id: String,
    val name: String,
    val subtitle: String,
    val isOverdue: Boolean,
)

/** 根级的面包屑文案（根级不是一个真实位置，没有父链可拼）。 */
const val ROOT_PATH_TEXT: String = "全部位置"
