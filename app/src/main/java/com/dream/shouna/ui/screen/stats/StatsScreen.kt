package com.dream.shouna.ui.screen.stats

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.domain.model.ItemStatus
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.common.EmptyState
import com.dream.shouna.ui.component.search.FilterChips
import com.dream.shouna.ui.component.search.FilterOption
import com.dream.shouna.ui.component.location.LocationPickerSheet
import com.dream.shouna.ui.component.item.OverdueMark
import com.dream.shouna.ui.component.common.StatCard
import com.dream.shouna.ui.component.item.TemporaryMark
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goItemDetail
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（docs/实现约束.md）：取 ViewModel、把 UiState 下传。
 * 导航动作由本层（Route）下发，`StatsScreen` 不读 `LocalNavController`。
 */
@Composable
fun StatsRoute() {
    val viewModel: StatsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    StatsScreen(
        uiState = uiState,
        onOpenDetail = viewModel::onOpenDetail,
        onBackToCards = viewModel::onBackToCards,
        onConfirmOverdue = viewModel::onConfirmOverdue,
        onPutBack = viewModel::onPutBack,
        onCategoryFilterSelected = viewModel::onCategoryFilterSelected,
        onLocationFilterSelected = viewModel::onLocationFilterSelected,
        onStatusFilterSelected = viewModel::onStatusFilterSelected,
        onClearFilters = viewModel::onClearFilters,
        onItemClick = { itemId -> navController.goItemDetail(itemId) },
        onBack = { navController.popBack() },
    )
}

/**
 * P-STATS 归纳统计页（FR-33 / 29 / 38 + P1-07 的全部物品）。
 *
 * 页内两态（P1 §8.1-16）：**卡片态**渲染 C-1 的两块 [StatCard]（只出 2 块，「存放关系数」已裁掉）
 * 与两个清单入口；**明细态**渲染 OVERDUE / TO_BE_PUT_BACK / ALL 三种清单。
 *
 * 【P1-07 新增「全部物品」】入口 = 「物品总数」卡片本身（该卡片一直支持 `onClick`，
 * 零新增行、零新概念）。它是三种明细里**唯一带三维筛选**的（分类 / 位置 / 状态），
 * 也是唯一没有行内动作的 —— 动作是「这只清单专属」的语义（确认还在 / 归位到…），
 * 在一张总览清单上给一个语义含糊的按钮比不给更糟。
 *
 * 【行动作】C-4 行 = 「确认还在」（一步到底）；C-5 行 = 「归位到…」（要选目标位置，故弹层）。
 * 弹层的「当前被操作行」是本页的纯粹 UI 态（`putBackTarget`），不进 ViewModel —— VM 只接
 * 「哪件物品、落到哪个位置」这个最终结果。
 *
 * 【返回键】明细态下系统返回键的**第一次消费**用于回卡片态，不退出页面（[BackHandler] 仅在
 * 明细态启用，卡片态自然交还系统退出）；页面左上角「返回」同此语义，避免两种返回手势行为不一致。
 *
 * 【下钻不新增路由】卡片 → 明细只是换内容，不 `navigate`（[StatsDetailKind] 决定明细内容）。
 */
@Composable
fun StatsScreen(
    uiState: StatsUiState,
    onOpenDetail: (StatsDetailKind) -> Unit,
    onBackToCards: () -> Unit,
    onConfirmOverdue: (String) -> Unit,
    onPutBack: (String, String) -> Unit,
    onCategoryFilterSelected: (String?) -> Unit,
    onLocationFilterSelected: (String?) -> Unit,
    onStatusFilterSelected: (ItemStatus?) -> Unit,
    onClearFilters: () -> Unit,
    onItemClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 待归位的目标物品 id：非空 = 「归位到…」弹层开着。
    // 本页**只写不读**（`putBackTarget.value = itemId` 不建立订阅），读取下沉到 [StatsPutBackPicker]。
    val putBackTarget = remember { mutableStateOf<String?>(null) }

    // 明细态下拦截系统返回：先回卡片态。卡片态不拦截 → 交还系统 popBackStack。
    BackHandler(enabled = uiState.isDetail) { onBackToCards() }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { if (uiState.isDetail) onBackToCards() else onBack() }) {
                Text(text = "返回")
            }
            Text(
                text = if (uiState.isDetail) uiState.detailKind.title() else "归纳统计",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        if (uiState.isDetail) {
            DetailContent(
                uiState = uiState,
                onRowAction = { itemId ->
                    // 三步动作分派。ALL 行的 actionLabel 为 null ⇒ 按钮根本不渲染，
                    // 这一支不会被触达（仍然写全，让 `when` 保持穷尽）。
                    when (uiState.detailKind) {
                        StatsDetailKind.OVERDUE -> onConfirmOverdue(itemId)
                        StatsDetailKind.TO_BE_PUT_BACK -> putBackTarget.value = itemId
                        StatsDetailKind.ALL, null -> Unit
                    }
                },
                onCategoryFilterSelected = onCategoryFilterSelected,
                onLocationFilterSelected = onLocationFilterSelected,
                onStatusFilterSelected = onStatusFilterSelected,
                onClearFilters = onClearFilters,
                onItemClick = onItemClick,
                modifier = Modifier.weight(1f),
            )
        } else {
            CardsContent(
                uiState = uiState,
                onOpenDetail = onOpenDetail,
                modifier = Modifier.weight(1f),
            )
        }
    }

    // FR-38：C-5 的「归位到…」—— 复用位置选择弹层（P1 §4 P1-03 ③）。
    StatsPutBackPicker(
        target = putBackTarget,
        rows = uiState.locationRows,
        onPutBack = onPutBack,
        onDismiss = { putBackTarget.value = null },
    )
}

/**
 * 「归位到…」弹层的**薄宿主**：本页对该开关的唯一读取点。
 *
 * 开关若在 [StatsScreen] 体内读，每次开合都会重跑整页（含卡片态/明细态的 `LazyColumn`）。
 * 下沉后父层**只写不读**，开合仅重跑本层与弹层子树。
 *
 * 挂载位置不影响观感：`LocationPickerSheet` 渲染为**独立 Dialog 窗口**，在组合树里的位置不决定层级。
 */
@Composable
private fun StatsPutBackPicker(
    target: MutableState<String?>,
    rows: List<LocationTreeRow>,
    onPutBack: (String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    target.value?.let { itemId ->
        LocationPickerSheet(
            rows = rows,
            recentLocations = emptyList(),
            selectedLocationId = null,
            onSelect = { locationId ->
                onPutBack(itemId, locationId)
                onDismiss()
            },
            onCreateLocation = { /* 归位目标从既有位置里挑，不支持此处新建 */ },
            onDismiss = onDismiss,
            allowCreate = false,
        )
    }
}

/** 卡片态：C-1 两块数字 + 两个清单入口（入口带条数，点进去才看明细）。 */
@Composable
private fun CardsContent(
    uiState: StatsUiState,
    onOpenDetail: (StatsDetailKind) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        // C-1 **只出 2 块**：物品总数 / 位置总数。
        //
        // P1-07：「物品总数」整块可点 → 下钻「全部物品」明细。数字与明细同口径（都排除 `gone`），
        // 因此点进去看到的条数与卡片上的数字一致；`hint` 是必要的可见线索
        // —— 卡片默认没有任何「可点」的视觉表达，不给提示这个入口等于不存在。
        StatCard(
            title = "物品总数",
            value = uiState.itemCount,
            onClick = { onOpenDetail(StatsDetailKind.ALL) },
            hint = "查看全部物品 ›",
        )
        Spacer(modifier = Modifier.height(8.dp))
        // 「位置总数」不可点：位置清单的归属是 P-BROWSE（按层浏览），不在本页重开一份。
        StatCard(title = "位置总数", value = uiState.locationCount)

        Spacer(modifier = Modifier.height(24.dp))

        // C-4 / C-5 的入口：条数直接摆在入口上，用户不必先进去才知道有没有内容。
        EntryRow(
            title = "超期未确认",
            count = uiState.overdueCount,
            hint = uiState.thresholdMonths
                ?.let { months -> "超过 $months 个月没有确认过的物品" }
                .orEmpty(),
            onClick = { onOpenDetail(StatsDetailKind.OVERDUE) },
        )
        EntryRow(
            title = "待归位",
            count = uiState.toBePutBackCount,
            hint = "标记为待归位、或放在临时位置上的物品",
            onClick = { onOpenDetail(StatsDetailKind.TO_BE_PUT_BACK) },
        )
    }
}

/** 清单入口行：标题 + 条数 + 一行说明；整行可点。 */
@Composable
private fun EntryRow(
    title: String,
    count: Int,
    hint: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.weight(1f),
            )
            Text(
                text = "$count 件 ›",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (hint.isNotEmpty()) {
            Text(
                text = hint,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    HorizontalDivider()
}

/**
 * 明细态：当前清单的判据说明 + （仅「全部物品」）三维筛选行 + 行列表。
 *
 * 每行的动作按钮由 [StatsDetailRowUi.actionLabel] 决定是否出现 —— 「全部物品」的行为 null，
 * 于是它只是一张可点的清单（点行 → 详情页）。
 */
@Composable
private fun DetailContent(
    uiState: StatsUiState,
    onRowAction: (String) -> Unit,
    onCategoryFilterSelected: (String?) -> Unit,
    onLocationFilterSelected: (String?) -> Unit,
    onStatusFilterSelected: (ItemStatus?) -> Unit,
    onClearFilters: () -> Unit,
    onItemClick: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = uiState.detailKind.hintOf(uiState.thresholdMonths),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(8.dp))

        // P1-07：只有「全部物品」带筛选行。三维实现与搜索页共用同一套组件与同一份筛选逻辑
        // （`domain.search.DocFilter`），不新增概念、不新开一套交互手势。
        if (uiState.detailKind == StatsDetailKind.ALL) {
            AllFilterSection(
                uiState = uiState,
                onCategoryFilterSelected = onCategoryFilterSelected,
                onLocationFilterSelected = onLocationFilterSelected,
                onStatusFilterSelected = onStatusFilterSelected,
                onClearFilters = onClearFilters,
            )
        }

        if (uiState.detailRows.isEmpty()) {
            EmptyState(text = uiState.detailKind.emptyText())
            return@Column
        }

        LazyColumn(modifier = Modifier.fillMaxWidth()) {
            items(items = uiState.detailRows, key = { it.itemId }) { row ->
                DetailRowUiItem(
                    row = row,
                    onAction = { onRowAction(row.itemId) },
                    onClick = { onItemClick(row.itemId) },
                )
            }
        }
    }
}

/**
 * P1-07：三维筛选行（分类 / 位置 / 状态）+ 清空。与搜索页的 `FilterSection` 同形。
 *
 * 位置维度的 label 用**完整路径文本**（VM 给的 `pathText`）：位置可能同名，
 * 只显示末级名字会让用户分不清选的是哪一个。
 */
@Composable
private fun AllFilterSection(
    uiState: StatsUiState,
    onCategoryFilterSelected: (String?) -> Unit,
    onLocationFilterSelected: (String?) -> Unit,
    onStatusFilterSelected: (ItemStatus?) -> Unit,
    onClearFilters: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxWidth()) {
        FilterChips(
            label = "分类",
            options = uiState.filterChoices.categories.map { FilterOption(it.id, it.label) },
            selectedId = uiState.filters.categoryId,
            onSelect = onCategoryFilterSelected,
        )

        FilterChips(
            label = "位置",
            options = uiState.filterChoices.locations.map { FilterOption(it.id, it.label) },
            selectedId = uiState.filters.locationId,
            // 组件回传 null = 取消该维度（再次点击已选 chip）。
            onSelect = onLocationFilterSelected,
        )

        FilterChips(
            label = "状态",
            options = uiState.filterChoices.statuses.map { FilterOption(it.id, it.label) },
            selectedId = uiState.filters.status?.code,
            onSelect = { code -> onStatusFilterSelected(code?.let { ItemStatus.fromCode(it) }) },
        )

        // 已筛选才给「清空」—— 三维皆空时它是个空操作，不占位置。
        if (!uiState.filters.isEmpty) {
            TextButton(onClick = onClearFilters) {
                Text(text = "清空筛选")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))
    }
}

/** 明细行：标题 + 副标题（+ 各种标记）在左，动作按钮在右（无动作时整块不出现）。 */
@Composable
private fun DetailRowUiItem(
    row: StatsDetailRowUi,
    onAction: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 4.dp, vertical = 10.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = row.title,
                style = MaterialTheme.typography.bodyLarge,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                // 「不在了」的标记放在副标题**之前**：副标题是「路径 · 时间」这类事实描述，
                // 状态是要抢在它前面被看到的东西。
                if (row.isGone) {
                    GoneMark(modifier = Modifier.padding(end = 6.dp))
                }
                Text(
                    text = row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.isTemporaryLocation) {
                    TemporaryMark(modifier = Modifier.padding(start = 6.dp))
                }
                OverdueMark(
                    visible = row.showOverdue,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }

        // actionLabel == null ⇒ 本行没有行内动作（「全部物品」清单），不渲染一个空按钮。
        if (row.actionLabel != null) {
            TextButton(onClick = onAction) {
                Text(text = row.actionLabel)
            }
        }
    }
    HorizontalDivider()
}

/** P1-07：「已标记不在了」的行标记（与 [TemporaryMark] 同一形态，用错误色系以示警示）。 */
@Composable
private fun GoneMark(modifier: Modifier = Modifier) {
    Text(
        text = "不在了",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onErrorContainer,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.errorContainer,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}

/** 明细态标题。 */
private fun StatsDetailKind?.title(): String = when (this) {
    StatsDetailKind.OVERDUE -> "超期未确认"
    StatsDetailKind.TO_BE_PUT_BACK -> "待归位"
    StatsDetailKind.ALL -> "全部物品"
    null -> "归纳统计"
}

/** 明细态的判据说明（把「为什么它在这张清单里」写在列表上方；阈值未载入时不编造档位）。 */
private fun StatsDetailKind?.hintOf(thresholdMonths: Int?): String = when (this) {
    StatsDetailKind.OVERDUE ->
        thresholdMonths?.let { months -> "超过 $months 个月没有确认过（含从未确认），最久未确认的排在前面。" }
            ?: "超过阈值没有确认过（含从未确认），最久未确认的排在前面。"
    StatsDetailKind.TO_BE_PUT_BACK ->
        "被标记为「待归位」的物品，以及放在临时位置上的物品（两者去重）。"
    // 口径必须写出来：用户看到「全部物品」四个字会以为含已删除的，而这里**默认不含**。
    StatsDetailKind.ALL ->
        "库里的全部物品，按录入时间倒序。默认不含已标记「不在了」的 —— 要看得用下面的状态筛选。"
    null -> ""
}

/** 明细态空清单文案。 */
private fun StatsDetailKind?.emptyText(): String = when (this) {
    StatsDetailKind.OVERDUE -> "没有超期未确认的物品"
    StatsDetailKind.TO_BE_PUT_BACK -> "没有待归位的物品"
    StatsDetailKind.ALL -> "没有符合条件的物品"
    null -> "暂无内容"
}
