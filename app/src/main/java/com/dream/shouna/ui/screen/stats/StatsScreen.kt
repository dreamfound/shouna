package com.dream.shouna.ui.screen.stats

import androidx.activity.compose.BackHandler
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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.ui.component.EmptyState
import com.dream.shouna.ui.component.LocationPickerSheet
import com.dream.shouna.ui.component.StatCard
import com.dream.shouna.ui.component.TemporaryMark
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goItemDetail
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（ARCHITECTURE §2）：取 ViewModel、把 UiState 下传。
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
        onItemClick = { itemId -> navController.goItemDetail(itemId) },
        onBack = { navController.popBack() },
    )
}

/**
 * P-STATS 归纳统计页（FR-33 / 29 / 38）。
 *
 * 页内两态（P1 §8.1-16）：**卡片态**渲染 C-1 的两块 [StatCard]（只出 2 块，「存放关系数」已裁掉）
 * 与两个清单入口；**明细态**渲染 C-4 / C-5 的清单，每行带一个行内动作。
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
    onItemClick: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 待归位的目标物品 id：非空 = 「归位到…」弹层开着。
    var putBackTarget by remember { mutableStateOf<String?>(null) }

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
                    // 超期行一步到底；待归位行要先选位置（FR-38 的「归位到…」）。
                    if (uiState.detailKind == StatsDetailKind.OVERDUE) {
                        onConfirmOverdue(itemId)
                    } else {
                        putBackTarget = itemId
                    }
                },
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
    putBackTarget?.let { itemId ->
        LocationPickerSheet(
            rows = uiState.locationRows,
            recentLocations = emptyList(),
            selectedLocationId = null,
            onSelect = { locationId ->
                onPutBack(itemId, locationId)
                putBackTarget = null
            },
            onCreateLocation = { /* 归位目标从既有位置里挑，不支持此处新建 */ },
            onDismiss = { putBackTarget = null },
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
        // C-1 **只出 2 块**：物品总数 / 位置总数。两块都是「总览数字」，本身不含可下钻的清单。
        StatCard(title = "物品总数", value = uiState.itemCount)
        Spacer(modifier = Modifier.height(8.dp))
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

/** 明细态：当前清单的判据说明 + 行列表（每行一个动作）。 */
@Composable
private fun DetailContent(
    uiState: StatsUiState,
    onRowAction: (String) -> Unit,
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

/** 明细行：标题 + 副标题（+ 临时标记）在左，动作按钮在右。 */
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
                Text(
                    text = row.subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (row.isTemporaryLocation) {
                    TemporaryMark(modifier = Modifier.padding(start = 6.dp))
                }
            }
        }

        TextButton(onClick = onAction) {
            Text(text = row.actionLabel)
        }
    }
    HorizontalDivider()
}

/** 明细态标题。 */
private fun StatsDetailKind?.title(): String = when (this) {
    StatsDetailKind.OVERDUE -> "超期未确认"
    StatsDetailKind.TO_BE_PUT_BACK -> "待归位"
    null -> "归纳统计"
}

/** 明细态的判据说明（把「为什么它在这张清单里」写在列表上方；阈值未载入时不编造档位）。 */
private fun StatsDetailKind?.hintOf(thresholdMonths: Int?): String = when (this) {
    StatsDetailKind.OVERDUE ->
        thresholdMonths?.let { months -> "超过 $months 个月没有确认过（含从未确认），最久未确认的排在前面。" }
            ?: "超过阈值没有确认过（含从未确认），最久未确认的排在前面。"
    StatsDetailKind.TO_BE_PUT_BACK ->
        "被标记为「待归位」的物品，以及放在临时位置上的物品（两者去重）。"
    null -> ""
}

/** 明细态空清单文案。 */
private fun StatsDetailKind?.emptyText(): String = when (this) {
    StatsDetailKind.OVERDUE -> "没有超期未确认的物品"
    StatsDetailKind.TO_BE_PUT_BACK -> "没有待归位的物品"
    null -> "暂无内容"
}
