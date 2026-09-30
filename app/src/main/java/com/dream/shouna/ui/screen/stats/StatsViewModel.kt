package com.dream.shouna.ui.screen.stats

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dream.shouna.data.repository.ConfigRepository
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.util.TimeUtil
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * P-STATS（FR-33 / 29 / 38）的状态机 —— **骨架**。
 *
 * 页面形态（P1 §8.1-16）：**不新增路由**，页内两态 —— 卡片态（C-1 两块数字）与明细态
 * （C-4 超期清单 / C-5 待归位清单）。返回键从明细态回卡片态，再按一次才退出页面。
 *
 * 本次只搭骨架：`load()` 是**空实现**（仅置位 `isLoaded`，让页面能停住、能返回），
 * 三个数据块与两个动作留 TODO。展示类派生（相对时间、超期判定、件数文案）按 `实现约束.md` §2-2
 * 全部在本层组装成字符串，Screen 只渲染。
 *
 * 被调用方：`StatsRoute`
 */
@HiltViewModel
class StatsViewModel @Inject constructor(
    private val itemRepository: ItemRepository,
    private val locationRepository: LocationRepository,
    private val configRepository: ConfigRepository,
    private val timeUtil: TimeUtil,
) : ViewModel() {

    private val state = MutableStateFlow(StatsUiState())

    val uiState: StateFlow<StatsUiState> = state.asStateFlow()

    /**
     * 骨架期空实现：只置 `isLoaded`。
     *
     * TODO(P1-03 ①): C-1 两块数字 —— 物品总数（`in_storage` + `to_be_put_back`，即非 `gone`）
     *                 与**非内置**位置总数。
     * TODO(P1-03 ②): C-4 超期未确认清单（`last_confirmed_at IS NULL OR < now - threshold`）+ 条数徽标。
     * TODO(P1-03 ③): C-5 待归位清单 = `to_be_put_back` ∪ 「位于用户标记过的临时位置」，**按物品去重**
     *                 （P1 §8.1-9）。
     * TODO(P1-03 ⑤): 阈值从 `app_config` 读，设置页改后即时生效（与 SearchViewModel 同口径：
     *                 阈值作为一条流进 combine，不做「先渲染猜的默认值再纠正」）。
     */
    fun load() {
        state.update { it.copy(isLoaded = true) }
    }

    /** P1-03 ④：卡片 → 明细。**不新增路由**，只切页内态。 */
    fun onOpenDetail(kind: StatsDetailKind) {
        TODO("P1-03 ④: 切换到 $kind 的明细态（页内两态，不动导航栈）")
    }

    /** 明细态的返回：先回卡片态，不退出页面（返回键的第一次消费由页面负责）。 */
    fun onBackToCards() {
        state.update { it.copy(mode = StatsMode.CARDS, detailKind = null, detailRows = emptyList()) }
    }

    /** 明细行动作：超期行 → 「确认还在」；待归位行 → 「归位到…」（复用位置选择弹层）。 */
    fun onDetailRowAction(itemId: String) {
        TODO("P1-03 ②③: 按当前明细类型分发到 confirmItem / setStatus(IN_STORAGE)")
    }
}

data class StatsUiState(
    /** 骨架期标志；真数据装载完成后仍由 [StatsViewModel.load] 维护。 */
    val isLoaded: Boolean = false,
    /** 页内两态：卡片 / 明细（P1 §8.1-16）。 */
    val mode: StatsMode = StatsMode.CARDS,
    /** C-1 第一块：物品总数（非 `gone`）。骨架期恒 0。 */
    val itemCount: Int = 0,
    /** C-1 第二块：位置总数（**不含**内置哨兵）。骨架期恒 0。 */
    val locationCount: Int = 0,
    /** C-4 条数徽标。骨架期恒 0。 */
    val overdueCount: Int = 0,
    /** C-5 去重后的合计条数。骨架期恒 0。 */
    val toBePutBackCount: Int = 0,
    /** 当前明细类型；null = 未进明细。 */
    val detailKind: StatsDetailKind? = null,
    /** 明细行（VM 已把副标题与动作文案组装好）。 */
    val detailRows: List<StatsDetailRowUi> = emptyList(),
)

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
)
