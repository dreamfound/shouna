package com.dream.shouna.ui.screen.settings

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
        onCategoryManageClick = { navController.goCategoryManage() },
        onBack = { navController.popBack() },
    )
}

/**
 * P-SETTINGS 设置页（FR-47）—— **骨架**。
 *
 * 当前渲染：标题 + 四块静态内容（阈值 / 分类管理入口 / 隐私说明 / 关于）+ 返回。
 * 唯一**可用**的交互是「分类管理」入口 —— 它只做导航，不经过任何 TODO 桩，
 * 因此骨架期就能验证 FR-44 的路由可达。
 *
 * 隐私说明是**固定文案**（不是待实现项），在此就写明：数据仅本机、不可迁移、卸载即永久丢失
 * （P1 §8.1-14 的硬要求；`allowBackup = false` 见 `AndroidManifest.xml`）。
 *
 * 未渲染：阈值三档的**可点**选择器（保护 TODO 桩）、关于的版本号读取、V2 的 AI 设置节
 * （`prd/14` `V2-SEAM-05`：只留文档级位置，连灰置入口都不给）。
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    onCategoryManageClick: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val options = SettingsViewModel.THRESHOLD_OPTIONS.joinToString(" / ")

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        Text(
            text = "设置",
            style = MaterialTheme.typography.headlineSmall,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ① 超期阈值（FR-29 / 47）。骨架期只展示档位，不含可点选择器。
        Text(
            text = "超期提醒阈值：$options 个月",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "当前：${uiState.thresholdMonths?.let { "$it 个月" } ?: "未载入"}（P1-06 待实现）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(16.dp))

        // ② 分类管理入口（FR-44）。骨架期唯一可用的交互：纯导航。
        TextButton(onClick = onCategoryManageClick) {
            Text(text = "分类管理")
        }

        Spacer(modifier = Modifier.height(8.dp))

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

        Spacer(modifier = Modifier.height(16.dp))

        // ④ 关于。
        Text(
            text = "关于",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = "收纳助手 · 本地离线使用（版本号读取待 P1-06 接入）",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(24.dp))

        TextButton(onClick = onBack) {
            Text(text = "返回")
        }
    }
}
