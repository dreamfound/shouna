package com.dream.shouna.ui.screen.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.R
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goCategoryManage
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（docs/实现约束.md）。分类管理入口的导航动作由本层下发。
 *
 * AI 的开关与用量在这里**上提**成独立参数（不塞进 `SettingsUiState`）：它们是**订阅来的流**，
 * 而 UiState 是本地可变快照 —— 混在一起会让「开关的真源」变成两个。
 */
@Composable
fun SettingsRoute() {
    val viewModel: SettingsViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val aiUsageCount by viewModel.aiUsageCount.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    SettingsScreen(
        uiState = uiState,
        aiEnabled = aiEnabled,
        aiUsageCount = aiUsageCount,
        onThresholdSelected = viewModel::onThresholdSelected,
        onCategoryManageClick = { navController.goCategoryManage() },
        onAiEnabledChanged = viewModel::onAiEnabledChanged,
        onApiKeySubmitted = viewModel::onApiKeySubmitted,
        onBack = { navController.popBack() },
    )
}

/**
 * P-SETTINGS 设置页（FR-47）+ **V2 AI 节**（`V2-SEAM-05`）：超期阈值选择器 + 分类管理入口 +
 * AI 录入 + 隐私说明 + 关于。
 *
 * 【阈值选择器】三档 chip，选中态 = 当前阈值；未载入时**一档都不选中**（不预选一个猜的值，
 * 用户看到的高亮就是库里的真值）。改档后写入即刻生效：`StatsViewModel` 订阅的是同一条配置流，
 * 返回栈上的统计页 C-4 清单会随之变化，不需要重建页面。
 *
 * 【AI 节】出厂默认**关**（`V2-FR-06`）。开关关着时录入页不会出现 AI 按钮、也不发任何请求；
 * 密钥**只写入、不回显**（`V2-COST-05`），页面上只显示「已配置 / 尚未配置」。**关闭开关不删密钥**，
 * 再开启时不必重填。
 *
 * 【隐私说明已随 AI 落地订正】原文案写「不联网、不上传、不申请任何权限」——这句话在 AI 落地后
 * **不再成立**（已申请 `INTERNET`，且用户主动提交的内容会发往他自己配置的服务商）。现按
 * `docs/code-map/02-数据与领域.md` AI-03 子步骤③ 改成两句：本页通用的「数据仅存本机 + 离线可用」，
 * 以及 AI 节内的**出设备白名单**（物品数据永不出设备）。改动依据见 `V2-AI-PRD` §5。
 */
@Composable
fun SettingsScreen(
    uiState: SettingsUiState,
    aiEnabled: Boolean,
    aiUsageCount: Int,
    onThresholdSelected: (Int) -> Unit,
    onCategoryManageClick: () -> Unit,
    onAiEnabledChanged: (Boolean) -> Unit,
    onApiKeySubmitted: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        Spacer(modifier = Modifier.height(20.dp))

        // ③ AI 录入（V2-SEAM-05 / V2-FR-06 / 15 / V2-COST-05）。
        AiSection(
            aiEnabled = aiEnabled,
            aiUsageCount = aiUsageCount,
            aiHasApiKey = uiState.aiHasApiKey,
            onAiEnabledChanged = onAiEnabledChanged,
            onApiKeySubmitted = onApiKeySubmitted,
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ④ 隐私说明（固定文案）。
        Text(
            text = "隐私说明",
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )
        Text(
            text = stringResource(R.string.settings_privacy_body),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ⑤ 关于。
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

/**
 * AI 录入节。页面私有、无状态 —— 输入的密钥草稿**留在本层**（`remember`）而不是 VM：
 * 明文只活到提交那一刻，提交后立即清空，UiState 里从不出现密钥（`V2-COST-05` 的「只写入不回显」）。
 */
@Composable
private fun AiSection(
    aiEnabled: Boolean,
    aiUsageCount: Int,
    aiHasApiKey: Boolean,
    onAiEnabledChanged: (Boolean) -> Unit,
    onApiKeySubmitted: (String) -> Unit,
) {
    var keyDraft by remember { mutableStateOf("") }

    Text(
        text = stringResource(R.string.settings_ai_title),
        style = MaterialTheme.typography.bodyLarge,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.settings_ai_subtitle),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    ) {
        Switch(checked = aiEnabled, onCheckedChange = onAiEnabledChanged)
        Spacer(modifier = Modifier.width(12.dp))
        Text(
            text = if (aiEnabled) "已开启" else "已关闭",
            style = MaterialTheme.typography.bodyMedium,
        )
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
    ) {
        OutlinedTextField(
            value = keyDraft,
            onValueChange = { keyDraft = it },
            label = { Text(text = stringResource(R.string.settings_ai_key_label)) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.weight(1f),
        )
        Spacer(modifier = Modifier.width(8.dp))
        Button(
            // 提交后立即清空草稿：明文不在界面上停留，也不进 UiState。
            onClick = {
                onApiKeySubmitted(keyDraft)
                keyDraft = ""
            },
            enabled = keyDraft.isNotBlank(),
        ) {
            Text(text = stringResource(R.string.settings_ai_key_save))
        }
    }

    Text(
        text = stringResource(
            if (aiHasApiKey) R.string.settings_ai_key_present else R.string.settings_ai_key_missing,
        ),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    )
    Text(
        text = stringResource(R.string.settings_ai_usage, aiUsageCount),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    )
    Text(
        text = stringResource(R.string.settings_ai_privacy),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp),
    )
}
