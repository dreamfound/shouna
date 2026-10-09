package com.dream.shouna.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.dream.shouna.R
import com.dream.shouna.domain.ai.AiDraftRow

/**
 * AI 识别结果的**页内确认卡**（`docs/code-map/02-数据与领域.md` AI-02）。
 *
 * 【为什么是卡片而不是对话框】`实现约束.md` §4-4：录入期间不弹位置 / 分类对话框 ——
 * 录入的节奏是「输名称 → 保存 → 再输」，任何模态弹窗都会打断它。卡片只是页面里多出来的一段，
 * 用户不理会它也能继续手动录入。
 *
 * 【无状态】数据由用例层组装好（[AiDraftRow] 已含「命中 / 待新建」判定与展示文本），
 * 本组件只画与回调 —— 守 `实现约束.md` §2-2：展示类派生一律在 VM / 用例层。
 *
 * 【两个调用方】连续录入页（P-ADD）与首页（P-HOME）。两者的差别只有一处：
 * 录入页背后有主表单，1 件时可以「填入」让它回填；首页没有主表单，1 件也必须直接落库。
 * 这个差别由 [canFillBack] 表达，其余逐行勾选与按钮语义完全一致。
 *
 * 【逐行勾选】`V2-FR-11`「默认全选，可逐行取消」：模型认错的东西不该被顺手写进库，勾选框给用户
 * 一个**零成本**的否决位。
 */
@Composable
fun AiDraftCard(
    rows: List<AiDraftRow>,
    onToggle: (Int) -> Unit,
    onApply: () -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    canFillBack: Boolean = true,
) {
    val selectedCount = rows.count { it.selected }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(
                color = MaterialTheme.colorScheme.surfaceVariant,
                shape = RoundedCornerShape(8.dp),
            )
            .padding(12.dp),
    ) {
        Text(
            text = if (rows.size == 1) {
                stringResource(R.string.ai_card_title_one)
            } else {
                stringResource(R.string.ai_card_title_many, rows.size)
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.fillMaxWidth(),
        )

        rows.forEachIndexed { index, row ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(
                    checked = row.selected,
                    onCheckedChange = { onToggle(index) },
                )
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = row.name,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Text(
                        text = row.subtitle(),
                        style = MaterialTheme.typography.bodySmall,
                        // 「缺位置」是这一行**不会被记下**的信号，用错误色让它不可能被漏看
                        // （落库时会跳过它，见 `AiIntake.commitRows`）。
                        color = if (row.hasNoLocationHint) {
                            MaterialTheme.colorScheme.error
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            horizontalArrangement = Arrangement.End,
            modifier = Modifier.fillMaxWidth(),
        ) {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.ai_card_action_dismiss))
            }
            Spacer(modifier = Modifier.width(4.dp))
            TextButton(
                onClick = onApply,
                // 一件都没勾时无事可做，直接置灰比点了再给提示好。
                enabled = selectedCount > 0,
            ) {
                // 【按钮文案随**勾选数**变】录入页：1 件 → 「填入」（回填主表单，用户还能改后再自己
                // 保存）；多件 → 「全部记下」（逐条直接落库，`V2-FR-11` 的批量落库）。
                // 判据是「勾了几件」而不是「识别出几件」—— 从 3 件里只留下 1 件时，用户想要的正是
                // 「填入」。首页没有主表单可回填（`canFillBack = false`），1 件也只能是「记下」。
                val oneLabel = if (canFillBack) {
                    R.string.ai_card_action_fill
                } else {
                    R.string.ai_card_action_save_one
                }
                Text(
                    text = if (selectedCount == 1) {
                        stringResource(oneLabel)
                    } else {
                        stringResource(R.string.ai_card_action_save_all)
                    },
                )
            }
        }
    }
}

/**
 * 一行草稿的副标题：位置 · 分类 · 备注（缺项不占位）。
 *
 * 「待新建」标记来自 `LocationPathMatcher` 的 `pendingNames`：位置树里还没有这一层，卡片上先标
 * 出来，用户点「填入 / 全部记下」时才会真的逐层新建（`docs/code-map/02-数据与领域.md` 硬边界 2：
 * **未点确认前库中零新增**）。
 */
@Composable
private fun AiDraftRow.subtitle(): String {
    val pendingText = stringResource(
        R.string.ai_card_location_pending,
        locationText.orEmpty(),
    )
    val parts = buildList {
        locationText?.takeIf { it.isNotBlank() }?.let { text ->
            add(if (locationPendingCreation) pendingText else text)
        }
        // 一行位置线索都没有（首页通道才会出现）：明说它不会被记下，别让用户白勾一次。
        if (hasNoLocationHint) add(stringResource(R.string.ai_card_location_missing))
        categoryName?.takeIf { it.isNotBlank() }?.let { add(it) }
        note?.takeIf { it.isNotBlank() }?.let { add(it) }
    }
    return if (parts.isEmpty()) stringResource(R.string.ai_card_no_fields) else parts.joinToString(" · ")
}
