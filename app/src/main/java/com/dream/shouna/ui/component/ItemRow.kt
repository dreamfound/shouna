package com.dream.shouna.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 搜索结果行（ARCHITECTURE §1.2）：名称 + 关键词高亮。
 * F1 无路径、无分类后缀、无时间。
 *
 * 无状态：高亮区间由 `SearchScorer.highlightRange` 在 ViewModel 侧算好后下传；为 null 即纯文本行。
 */
@Composable
fun ItemRow(
    name: String,
    highlightRange: IntRange?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    // 区间越界一律裁剪到 [0, name.length]，不抛异常。
    val rawRange = highlightRange
    val start = rawRange?.first?.coerceIn(0, name.length)
    val end = if (rawRange == null || start == null) 0 else (rawRange.last + 1).coerceIn(start, name.length)
    val hasHighlight = start != null && end > start

    val text = buildAnnotatedString {
        append(name)
        if (hasHighlight && start != null) {
            addStyle(
                style = SpanStyle(
                    background = highlightColor,
                    fontWeight = FontWeight.SemiBold,
                ),
                start = start,
                end = end,
            )
        }
    }

    Text(
        text = text,
        style = MaterialTheme.typography.bodyLarge,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    )
}
