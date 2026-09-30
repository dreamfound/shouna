package com.dream.shouna.ui.screen.location

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ConfigRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.LocationCounts
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
    private val itemRepository: ItemRepository,
    private val configRepository: ConfigRepository,
    private val timeUtil: TimeUtil,
) : ViewModel() {

    /** 当前层；null = 根级（全部位置）。 */
    private val currentId = MutableStateFlow<String?>(null)

    private val itemsInCurrent: Flow<List<StoredItem>> = currentId.flatMapLatest { id ->
        // 根级不展示物品（根级本身不是一个位置），只展示顶层位置。
        if (id == null) flowOf(emptyList()) else locationRepository.observeItemsIn(id)
    }

    /**
     * P1-02（FR-21 / FR-28）：当前层的件数（本层 / 含子层）。
     *
     * 走仓库的 [LocationRepository.observeCounts] 而不是从树行里取：「批量确认的影响范围」
     * 与树行展示必须同源，否则会出现「提示说 5 件、实际只确认了 4 件」。
     */
    private val countsInCurrent: Flow<LocationCounts?> = currentId.flatMapLatest { id ->
        if (id == null) flowOf(null) else locationRepository.observeCounts(id)
    }

    val uiState: StateFlow<LocationBrowseUiState> = combine(
        locationRepository.observeTree(),
        itemsInCurrent,
        currentId,
        // 与 `SearchViewModel` 同一口径：阈值作为一条流进 combine，保证**首帧**的超期判定
        // 就用真实值，而不是先拿一个猜的默认值渲染一屏再纠正（FR-27）。
        flow { emit(configRepository.thresholdMonths()) },
        countsInCurrent,
    ) { tree, items, id, thresholdMonths, counts ->
        val currentRow = id?.let { wanted -> tree.firstOrNull { it.location.id == wanted } }
        val now = timeUtil.nowMillis()
        LocationBrowseUiState(
            locationId = id,
            pathText = currentRow?.pathText ?: ROOT_PATH_TEXT,
            // 只渲染**当前层的直属**子位置（下钻语义）；整棵树另由 allRows 提供给删除对话框的迁移目标选择。
            children = tree.filter { it.location.parentId == id },
            items = items.map { item -> item.toItemUi(thresholdMonths, now) },
            // FR-06：临时位置标记（位置侧读出，与物品时间戳无关）。
            isTemporary = currentRow?.location?.isTemporary ?: false,
            // FR-21：本层 / 含子层两数分开给，用户才不会被「一键确认」的范围吓到。
            directItemCount = counts?.directCount ?: items.size,
            subtreeItemCount = counts?.subtreeCount ?: items.size,
            allRows = tree,
            // FR-04：可作移动 / 合并目标的行 = 整棵树 − 自身 − 自身子树。
            moveTargetRows = id?.let { itself -> tree.filterNot { it.isSelfOrDescendantOf(itself, tree) } }
                ?: emptyList(),
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

    // --- P1-02（FR-04 / 06 / 28）：位置树深化 -------------------------------------------

    /**
     * FR-04：把当前层（**含整棵子树**）移动到 [newParentId] 下（null = 根级）。
     *
     * 失败（目标非法 / 移入自身子树）时数据零变动，界面也不需要回滚 —— 目标列表本身
     * 已经把自身与子树排除在外，走到失败说明是并发变动，静默忽略即可（§4-1 零弹窗）。
     */
    fun onMoveCurrent(newParentId: String?) {
        val id = currentId.value ?: return
        viewModelScope.launch {
            locationRepository.move(nodeId = id, newParentId = newParentId)
        }
    }

    /** FR-04：把当前层合并进 [targetId]（当前层的子位置与物品改挂目标，随后当前层消失 → 回根级）。 */
    fun onMergeCurrent(targetId: String) {
        val id = currentId.value ?: return
        viewModelScope.launch {
            if (locationRepository.merge(sourceId = id, targetId = targetId)) {
                // 当前层已不存在，留在原地会显示一个不存在的路径（与删除同处理）。
                currentId.value = null
            }
        }
    }

    /** FR-06：标记 / 取消把当前层作为临时位置。 */
    fun onToggleTemporaryCurrent(flag: Boolean) {
        val id = currentId.value ?: return
        viewModelScope.launch {
            locationRepository.setTemporary(nodeId = id, flag = flag)
        }
    }

    /**
     * FR-28：对当前层**含子层**的全部物品一键确认。
     *
     * 影响范围由界面在确认前展示（`uiState.subtreeItemCount`）—— 别让用户以为只动了本层。
     */
    fun onConfirmAllInCurrent() {
        val id = currentId.value ?: return
        viewModelScope.launch {
            itemRepository.confirmByLocation(id)
        }
    }

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L
    }
}

/**
 * [this] 是否就是 [rootId] 或位于其子树内 —— 按**父链**上溯判定。
 *
 * 为什么不用 `location.path` 前缀：树行只带展示用的名称路径；ID 序列路径虽有物化列，
 * 但父链才是它的真源（P1 §3.4-7），按父链走不依赖「物化列是否已回填」。
 */
private fun LocationTreeRow.isSelfOrDescendantOf(
    rootId: String,
    tree: List<LocationTreeRow>,
): Boolean {
    val parentById = tree.associate { it.location.id to it.location.parentId }
    var cursor: String? = location.id
    val visited = HashSet<String>()
    while (cursor != null) {
        if (cursor == rootId) return true
        if (!visited.add(cursor)) return false
        cursor = parentById[cursor]
    }
    return false
}

data class LocationBrowseUiState(
    /** 当前层；null = 根级。 */
    val locationId: String? = null,
    /** 面包屑文本；根级显示 [ROOT_PATH_TEXT]。 */
    val pathText: String = ROOT_PATH_TEXT,
    /** P1-02（FR-06）：当前层是否被标记为**临时位置**。 */
    val isTemporary: Boolean = false,
    /** 当前层的直属子位置。 */
    val children: List<LocationTreeRow> = emptyList(),
    /** 当前层的直属物品（FR-01）。 */
    val items: List<LocationItemUi> = emptyList(),
    /** P1-02（FR-21）：本层**直属**的件数（`gone` 不计）。 */
    val directItemCount: Int = 0,
    /** P1-02（FR-21）：本层**含子层**的件数；批量确认的影响范围就是它。 */
    val subtreeItemCount: Int = 0,
    /** 整棵位置树（供删除时的「迁移到…」目标选择）。 */
    val allRows: List<LocationTreeRow> = emptyList(),
    /** P1-02（FR-04）：可作移动 / 合并目标的位置行（已排除自身与自身子树）。 */
    val moveTargetRows: List<LocationTreeRow> = emptyList(),
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
