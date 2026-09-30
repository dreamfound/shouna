package com.dream.shouna.ui.screen.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ConfigRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.domain.model.ItemStatus
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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * P-STATS（FR-33 / 29 / 38）的状态机。
 *
 * 页面形态（P1 §8.1-16）：**不新增路由**，页内两态 —— 卡片态（C-1 两块数字）与明细态
 * （C-4 超期清单 / C-5 待归位清单）。返回键从明细态回卡片态，再按一次才退出页面。
 *
 * 三块数据的口径：
 * - **C-1**：物品总数 = 非 `gone`（`in_storage` + `to_be_put_back`）；位置总数 = **不含内置哨兵**。
 *   「存放关系数」块已裁定裁掉（P1 §8.1-8：一物一处下它恒等于物品总数）。
 * - **C-4**（FR-29 / 27）：超期未确认 = 活跃且 `last_confirmed_at IS NULL` 或早于阈值。
 * - **C-5**（FR-38）：待归位 = 状态 `to_be_put_back` ∪ 位于用户标记过的临时位置，**按物品去重**。
 *
 * 阈值以**流**参与 combine（P1 §4 P1-06 ③）：设置页改档后，已经在返回栈上的本页无需重建
 * 就能让 C-4 清单即时变化；且**不预置猜测值** —— combine 要等阈值首次发射才产出首个 UiState
 * （`initialValue` 是「未装载」态，不是「猜的 6 个月」）。
 *
 * 展示类派生（相对时间、原因文案、动作标签）全部在本层组装成字符串，Screen 只渲染
 * （守 `实现约束.md` §2-2）。
 *
 * 被调用方：`StatsRoute`
 */
@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val locationRepository: LocationRepository,
    private val configRepository: ConfigRepository,
    private val timeUtil: TimeUtil,
) : ViewModel() {

    /** 页内两态与明细类型：都不进路由，只在本层切换（P1 §8.1-16）。 */
    private val mode = MutableStateFlow(StatsMode.CARDS)
    private val detailKind = MutableStateFlow<StatsDetailKind?>(null)

    /** 「已进入本页」标志；数据由流驱动，不依赖一次性的装载动作。 */
    private val isLoaded = MutableStateFlow(false)

    /** 超期阈值（月）—— 一条流，设置页改后本页即时跟随（FR-47）。 */
    private val thresholdFlow: Flow<Int> = configRepository.observeThresholdMonths()

    /** C-4 清单随阈值变化重建：阈值是 `observeOverdueItems` 的入参，故用 `flatMapLatest` 串起来。 */
    private val overdueFlow: Flow<List<StoredItem>> =
        thresholdFlow.flatMapLatest { months -> itemRepository.observeOverdueItems(months) }

    /** C-1 两块数字 + 当前阈值（阈值也进 UiState，供明细态表头说明超期判据）。 */
    private val countsFlow: Flow<StatsCounts> = combine(
        itemRepository.observeActiveItemCount(),
        locationRepository.observeLocationCount(),
        thresholdFlow,
    ) { itemCount, locationCount, thresholdMonths ->
        StatsCounts(itemCount = itemCount, locationCount = locationCount, thresholdMonths = thresholdMonths)
    }

    /**
     * 两份清单 + 位置树 + 临时位置 id 集。
     *
     * 临时位置集取自位置树（`location.is_temporary`）而不是由 `item` 侧反查：
     * 位置侧的「临时」是个位置事实（FR-06），物品只是恰好落在那里，判定必须回位置侧取。
     * 同一棵树也直接供 C-5 的「归位到…」选择弹层使用（`LocationPickerSheet` 要的就是展平行）。
     */
    private val listsFlow: Flow<StatsLists> = combine(
        overdueFlow,
        itemRepository.observeToBePutBackItems(),
        locationRepository.observeTree(),
    ) { overdue, toBePutBack, tree ->
        StatsLists(
            overdue = overdue,
            toBePutBack = toBePutBack,
            tree = tree,
            temporaryLocationIds = tree.filter { it.location.isTemporary }
                .mapTo(HashSet()) { it.location.id },
        )
    }

    /** 页面两态的「谁在显示」—— 与数据流分开聚合，避免一次 combine 塞进 8 条流。 */
    private val viewFlow: Flow<StatsView> = combine(mode, detailKind, isLoaded) { currentMode, kind, loaded ->
        StatsView(mode = currentMode, detailKind = kind, isLoaded = loaded)
    }

    val uiState: StateFlow<StatsUiState> = combine(countsFlow, listsFlow, viewFlow) { counts, lists, view ->
        val now = timeUtil.nowMillis()
        StatsUiState(
            isLoaded = view.isLoaded,
            mode = view.mode,
            itemCount = counts.itemCount,
            locationCount = counts.locationCount,
            thresholdMonths = counts.thresholdMonths,
            overdueCount = lists.overdue.size,
            toBePutBackCount = lists.toBePutBack.size,
            detailKind = view.detailKind,
            detailRows = buildDetailRows(kind = view.detailKind, lists = lists, now = now),
            // C-5 的「归位到…」需要一个位置选择弹层；本页不另开数据源，直接用同一棵树。
            locationRows = lists.tree,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(SUBSCRIBE_TIMEOUT_MILLIS),
        initialValue = StatsUiState(),
    )

    /** 置位「已进入本页」。数据本身由上面的流驱动，这里不发起任何读取。 */
    fun load() {
        isLoaded.value = true
    }

    /** P1-03 ④：卡片 → 明细。**不新增路由**，只切页内态。 */
    fun onOpenDetail(kind: StatsDetailKind) {
        detailKind.value = kind
        mode.value = StatsMode.DETAIL
    }

    /** 明细态的返回：先回卡片态，不退出页面（返回键的第一次消费由页面负责）。 */
    fun onBackToCards() {
        mode.value = StatsMode.CARDS
        detailKind.value = null
    }

    /**
     * C-4 明细行的动作：「确认还在」→ 只写 `last_confirmed_at`（FR-26），不刷 `last_modified_at`。
     *
     * **只依赖流刷新**，不做手工重取：`observeOverdue` 是 Room 的 Flow，确认后该行不再满足超期
     * 条件，会自己从清单里消失。C-5 行的动作是个「选位置」的多步交互，由 [onPutBack] 承载。
     */
    fun onConfirmOverdue(itemId: String) {
        viewModelScope.launch { itemRepository.confirmItem(itemId) }
    }

    /**
     * C-5 明细行的动作：「归位到…」→ 用户挑好位置后调用。
     *
     * 归位 = 落到该位置 + 状态回到「在存放中」+ 刷新 `last_modified_at`（§3.5 矩阵：状态变化算变动）。
     * 写入后该行会自行从清单消失（状态不再是待归位、或已离开临时位置）。
     */
    fun onPutBack(itemId: String, locationId: String) {
        viewModelScope.launch { itemRepository.putBack(itemId, locationId) }
    }

    /** 明细行组装：只在明细态产出 —— 卡片态不必先算一遍用不上的文案。 */
    private fun buildDetailRows(
        kind: StatsDetailKind?,
        lists: StatsLists,
        now: Long,
    ): List<StatsDetailRowUi> = when (kind) {
        StatsDetailKind.OVERDUE -> lists.overdue.map { item ->
            // 列表行只放「确认」这一个时间（与搜索结果行同一取向：一行塞两个时间看不出哪个在报警）。
            StatsDetailRowUi(
                itemId = item.id,
                title = item.name,
                subtitle = item.lastConfirmedAt
                    ?.let { at -> "最后确认 ${timeUtil.relativeText(at, now)}" }
                    ?: TimeUtil.NEVER_CONFIRMED,
                actionLabel = ACTION_CONFIRM,
            )
        }

        StatsDetailKind.TO_BE_PUT_BACK -> lists.toBePutBack.map { item ->
            val inTemporaryLocation = item.locationId in lists.temporaryLocationIds
            // 副标题 = 命中该清单的**原因**；两个原因可同时成立，都摊开说，不让用户猜为什么在列表里。
            val reasons = buildList {
                if (item.status == ItemStatus.TO_BE_PUT_BACK) add(REASON_STATUS_PENDING)
                if (inTemporaryLocation) add(REASON_TEMPORARY_LOCATION)
            }
            StatsDetailRowUi(
                itemId = item.id,
                title = item.name,
                // 兜底：两个原因都不成立在 DB 的 OR 条件下不会出现；真出现也给个非空副标题。
                subtitle = reasons.joinToString(REASON_SEPARATOR)
                    .ifEmpty { "最后变动 ${timeUtil.relativeText(item.lastModifiedAt, now)}" },
                actionLabel = ACTION_PUT_BACK,
                isTemporaryLocation = inTemporaryLocation,
            )
        }

        null -> emptyList()
    }

    /** C-1 两块数字的聚合载体。 */
    private data class StatsCounts(
        val itemCount: Int,
        val locationCount: Int,
        val thresholdMonths: Int,
    )

    /** C-4 / C-5 两份清单的聚合载体。 */
    private data class StatsLists(
        val overdue: List<StoredItem>,
        val toBePutBack: List<StoredItem>,
        val tree: List<LocationTreeRow>,
        val temporaryLocationIds: Set<String>,
    )

    /** 页内两态的聚合载体。 */
    private data class StatsView(
        val mode: StatsMode,
        val detailKind: StatsDetailKind?,
        val isLoaded: Boolean,
    )

    private companion object {
        const val SUBSCRIBE_TIMEOUT_MILLIS = 5_000L

        /** C-4 行的动作文案（「确认还在」= 写 `last_confirmed_at`）。 */
        const val ACTION_CONFIRM = "确认还在"

    /** C-5 行的动作文案（「归位到…」= 选中目标位置后落位并回到「在存放中」）。 */
    const val ACTION_PUT_BACK = "归位到…"

        /** C-5 行副标题的原因分隔符与两条原因文案。 */
        const val REASON_SEPARATOR = " · "
        const val REASON_STATUS_PENDING = "状态：待归位"
        const val REASON_TEMPORARY_LOCATION = "位于临时位置"
    }
}

data class StatsUiState(
    /** 「已进入本页」标志；[StatsViewModel.load] 置位，数据由流驱动。 */
    val isLoaded: Boolean = false,
    /** 页内两态：卡片 / 明细（P1 §8.1-16）。 */
    val mode: StatsMode = StatsMode.CARDS,
    /** C-1 第一块：物品总数（非 `gone`）。 */
    val itemCount: Int = 0,
    /** C-1 第二块：位置总数（**不含**内置哨兵）。 */
    val locationCount: Int = 0,
    /** 当前超期阈值（月）；首个 emit 之前为 null，页面据此才敢说明超期的判据。 */
    val thresholdMonths: Int? = null,
    /** C-4 条数徽标。 */
    val overdueCount: Int = 0,
    /** C-5 去重后的合计条数。 */
    val toBePutBackCount: Int = 0,
    /** 当前明细类型；null = 未进明细。 */
    val detailKind: StatsDetailKind? = null,
    /** 明细行（VM 已把副标题与动作文案组装好）。 */
    val detailRows: List<StatsDetailRowUi> = emptyList(),
    /** C-5「归位到…」的位置选择弹层数据源（整棵位置树，与浏览页同一份派生）。 */
    val locationRows: List<LocationTreeRow> = emptyList(),
) {
    /** 明细态 = 页内第二态（返回键先回卡片态）。 */
    val isDetail: Boolean get() = mode == StatsMode.DETAIL
}

/** 页内两态。 */
enum class StatsMode {
    /** C-1 两块数字（`ARCHITECTURE-P1` §8.1-8：只出 2 块）。 */
    CARDS,

    /** C-4 / C-5 的清单。 */
    DETAIL,
}

/** 明细清单的种类（决定每行给什么动作）。 */
enum class StatsDetailKind {
    /** C-4：超期未确认（含「从未确认」）。 */
    OVERDUE,

    /** C-5：待归位（状态为待归位 ∪ 位于临时位置，已去重）。 */
    TO_BE_PUT_BACK,
}

/** 明细行：文案与动作标签都由 ViewModel 组装（Screen 不做判定）。 */
data class StatsDetailRowUi(
    val itemId: String,
    val title: String,
    val subtitle: String,
    val actionLabel: String,
    /** FR-06：该行物品位于用户标记过的临时位置 → Screen 显示「临时」标记。 */
    val isTemporaryLocation: Boolean = false,
)
