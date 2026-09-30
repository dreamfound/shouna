package com.dream.shouna.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.FilterChip
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
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goCategoryManage
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（ARCHITECTURE §2）。分类管理入口的导航动作由本层下发。
 */
@Composable
fun SettingsRoute() {
    val viewModel: SettingsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    SettingsScreen(
        uiState = uiState,
        onThresholdSelected = viewModel::onThresholdSelected,
        onCategoryManageClick = { navController.goCategoryManage() },
        onBack = { navController.popBack() },
    )
}

/**
 * P-SETTINGS 设置页（FR-47）：超期阈值选择器 + 分类管理入口 + 隐私说明 + 关于。
 *
 * 【阈值选择器】三档 chip，选中态 = 当前阈值；未载入时**一档都不选中**（不预选一个猜的值，
 * 用户看到的高亮就是库里的真值）。改档后写入即刻生效：`StatsViewModel` 订阅的是同一条配置流，
 * 返回栈上的统计页 C-4 清单会随之变化，不需要重建页面。
 *
 * 【隐私说明是固定文案】（不是待实现项）：数据仅本机、不可迁移、卸载即永久丢失
 * （P1 §8.1-14 的硬要求；`allowBackup = false` 见 `AndroidManifest.xml`）。
 *
 * 未含：V2 的 AI 设置节（`prd/14` `V2-SEAM-05`：只留文档级位置，连灰置入口都不给）。
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onThresholdSelected: (Int) -> Unit,
    onCategoryManageClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = onBack) { Text(text = "返回") }
            Text(
                text = "设置",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // ① 超期阈值（FR-29 / 47）：三档单选，改后 C-4 清单即时变化。
        Text(
            text = "超期提醒阈值",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "超过这个时间没有确认过的物品，会在「归纳统计」里被列出来。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 6.dp),
        ) {
            SettingsViewModel.THRESHOLD_OPTIONS.forEach { months ->
                FilterChip(
                    // 未载入（thresholdMonths = null）时无一选中：高亮必须与库里的真值一致。
                    selected = uiState.thresholdMonths == months,
                    onClick = { onThresholdSelected(months) },
                    label = { Text(text = "$months 个月") },
                )
            }
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ② 分类管理入口（FR-44）。
        TextButton(onClick = onCategoryManageClick) {
            Text(text = "分类管理")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ③ 隐私说明（固定文案）。
        Text(
            text = "隐私说明",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "数据仅保存在本机，不联网、不上传、不申请任何权限；" +
                "不提供备份与迁移通道，卸载应用或更换设备即永久丢失且不可恢复。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ④ 关于。
        Text(
            text = "关于",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "收纳助手 · 完全离线运行",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
