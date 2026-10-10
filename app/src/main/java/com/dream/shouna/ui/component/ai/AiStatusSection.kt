package com.dream.shouna.ui.component.ai

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dream.shouna.R
import com.dream.shouna.domain.ai.AiState

/**
 * AI 状态的落点：进度一行 / 失败一行 / 确认卡一段。`Hidden` 与 `Idle` 什么都不画
 * —— 开关关着时页面与 v1.5 逐字一致（`V2-AI-PRD` §5 硬约束）。
 *
 * 三种状态**互斥且都只占页面里的一小段**：没有模态、没有遮罩，用户不理会也能继续手工录入
 * （`V2-FR-07`：失败一律回落人工，不阻塞）。
 *
 * 【当前只有录入页（P-ADD）一个宿主】2026-10-09 起首页与语音 AI 录入页都不再走这一段：
 * 前者两个 AI 入口只做导航，后者结果走逐条弹窗、状态行由那一页自绘。于是 [canFillBack]
 * 恒为默认的 `true`（有主表单的宿主才有「填入」），参数保留不删的理由见 `AiDraftCard`。
 *
 * 【为什么进度只是一个省略号/一行字而不是转圈】`CircularProgressIndicator` 会带来一个持续的
 * 动画帧源 —— 而这两页都对首帧成本敏感（实测新结构冷首帧 400~700ms）。静态文本既表达了
 * 「在跑」，又不引入任何额外帧。
 */
@Composable
fun AiStatusSection(
    aiState: AiState,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    canFillBack: Boolean = true,
) {
    when (aiState) {
        AiState.Hidden, AiState.Idle -> Unit

        AiState.Parsing -> Text(
            text = stringResource(R.string.ai_entry_parsing),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = modifier.fillMaxWidth(),
        )

        is AiState.Failed -> Text(
            text = aiState.reason.text(),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = modifier.fillMaxWidth(),
        )

        is AiState.Draft -> {
            Spacer(modifier = Modifier.height(4.dp))
            AiDraftCard(
                rows = aiState.rows,
                onToggle = onToggle,
                onApply = onApply,
                onDismiss = onDismiss,
                modifier = modifier,
                canFillBack = canFillBack,
            )
        }
    }
}
