package com.dream.shouna.ui.screen.stats

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.ui.component.StatCard
import com.dream.shouna.ui.navigation.LocalNavController
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
        onBack = { navController.popBack() },
    )
}

/**
 * P-STATS 归纳统计页（FR-33 / 29 / 38）—— **骨架**。
 *
 * 当前渲染：标题 + C-1 的两块 [StatCard]（骨架期传入 `onClick = null`，即**不可点**，
 * 下钻未接）+ 「待实现」清单 + 返回。
 *
 * 未渲染（P1-03 实现期补）：C-4 超期清单与其条数徽标、C-5 待归位清单、明细态与行的动作按钮。
 * 明细态**不新增路由**：`mode` 切到 `DETAIL` 时同一页面换内容（P1 §8.1-16）。
 */
@Composable
fun StatsScreen(
    uiState: StatsUiState,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "归纳统计",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // C-1 **只出 2 块**：物品总数 / 位置总数。「存放关系数」块已裁定裁掉（P1 §8.1-8）。
        StatCard(title = "物品总数", value = uiState.itemCount)
        Spacer(modifier = Modifier.height(8.dp))
        StatCard(title = "位置总数", value = uiState.locationCount)

        Spacer(modifier = Modifier.height(24.dp))

        Text(
            text = "待实现（P1-03）：超期未确认清单 ${uiState.overdueCount} 条 · " +
                "待归位清单 ${uiState.toBePutBackCount} 条",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        TextButton(onClick = onBack) {
            Text(text = "返回")
        }
    }
}
