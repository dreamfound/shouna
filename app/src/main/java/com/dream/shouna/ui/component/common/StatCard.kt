package com.dream.shouna.ui.component.common

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * FR-33：总览统计卡片（P1-03）。C-1 **只出 2 块**（物品总数 / 位置总数），
 * 「存放关系数」块已裁定裁掉 —— 一物一处下它恒等于物品总数（P1 §8.1-8）。
 *
 * 无状态：数字与文案都由调用方算好（超期判定、递归计数都在 ViewModel 组装，守 §2-2）。
 *
 * [onClick] 可空：`null` = **不可点**（不显示点击反馈），而非「可点但无反应」。
 * 骨架期传入 null（下钻未接），P1-03 接入明细态时传入下钻动作。
 *
 * [hint]（P1-07 新增）：可点时的**可见线索**。整块卡片的可点性没有任何默认视觉反馈，
 * 不写一行提示，用户根本不会想到去点它 —— 那这个下钻入口等于不存在。
 * `onClick == null` 时忽略它（不可点的卡片上写「查看…」是误导）。
 */
@Composable
fun StatCard(
    title: String,
    value: Int,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    hint: String? = null,
) {
    val cardModifier = if (onClick == null) {
        modifier.fillMaxWidth()
    } else {
        modifier.fillMaxWidth().clickable(onClick = onClick)
    }

    Card(modifier = cardModifier) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Text(
                text = value.toString(),
                style = MaterialTheme.typography.headlineMedium,
            )
            if (hint != null && onClick != null) {
                Text(
                    text = hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}
