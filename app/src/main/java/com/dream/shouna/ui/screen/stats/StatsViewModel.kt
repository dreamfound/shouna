package com.dream.shouna.ui.screen.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ConfigRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.domain.model.StoredItem
import com.dream.shouna.domain.search.SearchDoc
import com.dream.shouna.domain.search.SearchFilterChoices
import com.dream.shouna.domain.search.SearchFilters
import com.dream.shouna.domain.search.applyDocFilters
import com.dream.shouna.domain.search.buildDocFilterChoices
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
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * P-STATS（FR-33 / 29 / 38）的状态机。
 *
 * 页面形态（P1 §8.1-16）：**不新增路由**，页内两态 —— 卡片态（C-1 两块数字）与明细态
 * （C-4 超期清单 / C-5 待归位清单 / P1-07 全部物品）。返回键从明细态回卡片态，再按一次才退出页面。
 *
 * 三块数据的口径：
 * - **C-1**：物品总数 = 非 `gone`（`in_storage` + `to_be_put_back`）；位置总数 = **不含内置哨兵**。
 *   「存放关系数」块已裁定裁掉（P1 §8.1-8：一物一处下它恒等于物品总数）。
 * - **C-4**（FR-29 / 27）：超期未确认 = 活跃且 `last_confirmed_at IS NULL` 或早于阈值。
 * - **C-5**（FR-38）：待归位 = 状态 `to_be_put_back` ∪ 位于用户标记过的临时位置，**按物品去重**。
 * - **ALL**（P1-07）：全部物品 = 非 `gone`（与 C-1 卡片同数）+ 三维筛选；状态维度可筛出「不在了」。
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

    /**
     * P1-07：「全部物品」明细的筛选态（分类 / 位置 / 状态）。
     *
     * 与搜索页共用 `domain.search.SearchFilters` 与同一份筛选实现 —— 两个页面若各写一套，
     * 「位置含子层」的判定与状态 chip 文案迟早会分裂。
     */
    private val allFilters = MutableStateFlow(SearchFilters())

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

    /**
     * P1-07：「全部物品」明细的**懒订阅**数据流。
     *
     * 【为什么必须懒订阅】`observeAllDocs()` 是 `item` × `category` × `location` 三表 join +
     * 位置路径实时拼音化，比卡片态那三条轻流重得多。若把它塞进常驻的 UiState combine，
     * 每次数据变动（包括跟本页无关的编辑）都要付一次全量文档重建的代价。卡片态只需要两个数字，
     * 不该为它买单。
     *
     * 【切走即退订】`flatMapLatest` 在 [detailKind] 变回卡片态 / OVERDUE / TO_BE_PUT_BACK 时
     * 自动取消上游订阅，只发一个空载荷。**筛选态 [allFilters] 刻意不重置**：用户从「不在了」
     * 退回卡片再进来，多半还想看同一批东西。
     */
    private val allDetailFlow: Flow<AllDetail> = detailKind.flatMapLatest { kind ->
        if (kind != StatsDetailKind.ALL) {
            flowOf(AllDetail())
        } else {
            combine(
                itemRepository.observeAllDocs(),
                allFilters,
                locationRepository.observeTree(),
            ) { docs, filters, tree ->
                // 口径（P1-07，用户裁决）：**默认不含已标记「不在了」**的物品 —— 与 C-1
                // 「物品总数」卡片同数，用户不会看到两个对不上的数字。要看已标记的，
                // 用状态维度里的「不在了」那一支（选中后就不再滤）。
                //
                // 【为什么数据源仍取 `observeAllDocs`】筛选是「可以筛出 gone」这一能力的来源；
                // 若改回 `observeSearchDocs`（恒滤 gone），状态维度里的「不在了」永远筛出空列表。
                val scoped = if (filters.status == null) {
                    docs.filter { it.status.isActive }
                } else {
                    docs
                }
                AllDetail(
                    rows = applyDocFilters(scoped, filters, tree),
                    filters = filters,
                    // 候选项取自**未筛**的文档集：三个维度的选项不会因为选了另一个维度而消失
                    // （否则「选中的 chip 从列表里消失、又只能靠清空筛选取消」就是个死结）。
                    choices = buildDocFilterChoices(docs, tree),
                )
            }
        }
    }

    val uiState: StateFlow<StatsUiState> = combine(
        countsFlow,
        listsFlow,
        viewFlow,
        allDetailFlow,
    ) { counts, lists, view, all ->
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
            detailRows = buildDetailRows(
                kind = view.detailKind,
                lists = lists,
                all = all,
                thresholdMonths = counts.thresholdMonths,
                now = now,
            ),
            // C-5 的「归位到…」需要一个位置选择弹层；本页不另开数据源，直接用同一棵树。
            locationRows = lists.tree,
            filters = all.filters,
            filterChoices = all.choices,
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

    // --- P1-07：「全部物品」明细的三维筛选 ------------------------------------------------
    // 三个入口都只写筛选态，重算由 allDetailFlow 自动完成（它是那条 combine 的输入）。

    /** P1-07：分类维度。 */
    fun onCategoryFilterSelected(categoryId: String?) {
        allFilters.update { it.copy(categoryId = categoryId) }
    }

    /** P1-07：位置维度。**含子层** —— 选中一个位置即含其全部子孙（P1 §8.1-7）。 */
    fun onLocationFilterSelected(locationId: String?) {
        allFilters.update { it.copy(locationId = locationId) }
    }

    /** P1-07：状态维度。选中「不在了」即只看已标记的那些（默认口径是不含它们）。 */
    fun onStatusFilterSelected(status: ItemStatus?) {
        allFilters.update { it.copy(status = status) }
    }

    /** P1-07：一键清空（三个维度一起）→ 回到「非 `gone` 的全部物品」。纯状态复位，无副作用。 */
    fun onClearFilters() {
        allFilters.value = SearchFilters()
    }

    /** 明细行组装：只在明细态产出 —— 卡片态不必先算一遍用不上的文案。 */
    private fun buildDetailRows(
        kind: StatsDetailKind?,
        lists: StatsLists,
        all: AllDetail,
        thresholdMonths: Int?,
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

        // P1-07「全部物品」：行文案与搜索结果行同构（位置路径 · 最后确认 + FR-27 的 ⚠），
        // 但**不带行内动作** —— 「确认还在」只对超期清单有意义，「归位到…」只对待归位清单有意义；
        // 在一张总览清单上给一个语义含糊的按钮，比不给更糟。
        StatsDetailKind.ALL -> all.rows.map { doc -> doc.toAllRowUi(thresholdMonths, now) }

        null -> emptyList()
    }

    /** 「全部物品」行的文案组装（`thresholdMonths` 为空 = 阈值未载入，此时不判超期，不编造档位）。 */
    private fun SearchDoc.toAllRowUi(thresholdMonths: Int?, now: Long): StatsDetailRowUi =
        StatsDetailRowUi(
            itemId = itemId,
            title = name,
            // 两段都可能缺（位置数据异常 / 时间缺失）→ 用 buildList 兜住，不留以分隔符开头的副标题。
            subtitle = buildList {
                if (locationPath.isNotEmpty()) add(locationPath)
                add(
                    if (lastConfirmedAt == null) {
                        TimeUtil.NEVER_CONFIRMED
                    } else {
                        "最后确认 ${timeUtil.relativeText(lastConfirmedAt, now)}"
                    },
                )
            }.joinToString(SUBTITLE_SEPARATOR),
            actionLabel = null,
            showOverdue = thresholdMonths != null && timeUtil.isOverdue(lastConfirmedAt, thresholdMonths, now),
            // 状态筛到「不在了」时整个列表都是它；默认口径下不会出现 —— 但标记仍给出，
            // 因为「这张列表里混着已标记的」这件事必须一眼看得出，不能让用户以为它还在。
            isGone = status == ItemStatus.GONE,
        )

    /** 「全部物品」明细的聚合载体。 */
    private data class AllDetail(
        val rows: List<SearchDoc> = emptyList(),
        val filters: SearchFilters = SearchFilters(),
        val choices: SearchFilterChoices = SearchFilterChoices(),
    )

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

        /** 「全部物品」行副标题的分隔符（与搜索结果行同一形态：`路径 · 最后确认 …`）。 */
        const val SUBTITLE_SEPARATOR = " · "
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
    /** P1-07「全部物品」明细的筛选态（分类 / 位置 / 状态）。其它明细类型下恒为未筛选。 */
    val filters: SearchFilters = SearchFilters(),
    /** P1-07「全部物品」明细的三维候选项（由文档集与位置树派生）。 */
    val filterChoices: SearchFilterChoices = SearchFilterChoices(),
) {
    /** 明细态 = 页内第二态（返回键先回卡片态）。 */
    val isDetail: Boolean get() = mode == StatsMode.DETAIL
}

/** 页内两态。 */
enum class StatsMode {
    /** C-1 两块数字（`ARCHITECTURE-P1` §8.1-8：只出 2 块）。 */
    CARDS,

    /** C-4 / C-5 / ALL 的清单。 */
    DETAIL,
}

/** 明细清单的种类（决定每行给什么动作）。 */
enum class StatsDetailKind {
    /** C-4：超期未确认（含「从未确认」）。 */
    OVERDUE,

    /** C-5：待归位（状态为待归位 ∪ 位于临时位置，已去重）。 */
    TO_BE_PUT_BACK,

    /**
     * P1-07：**全部物品**（「物品总数」卡片的下钻）。
     *
     * 默认 = 全部非 `gone` 的物品（与卡片同数）；状态维度可筛出「不在了」那些。
     * 与另两类的区别：**这是唯一带筛选行的明细**，也是唯一没有行内动作的明细。
     */
    ALL,
}

/** 明细行：文案与动作标签都由 ViewModel 组装（Screen 不做判定）。 */
data class StatsDetailRowUi(
    val itemId: String,
    val title: String,
    val subtitle: String,
    /** 行内动作文案；**null = 本行没有动作**（「全部物品」清单即此，Screen 据此不渲染按钮）。 */
    val actionLabel: String?,
    /** FR-06：该行物品位于用户标记过的临时位置 → Screen 显示「临时」标记。 */
    val isTemporaryLocation: Boolean = false,
    /** P1-07：FR-27 超期判定 → Screen 显示 ⚠（「全部物品」清单用）。 */
    val showOverdue: Boolean = false,
    /** P1-07：该行物品已被标记「不在了」→ Screen 显示状态标记。 */
    val isGone: Boolean = false,
)
