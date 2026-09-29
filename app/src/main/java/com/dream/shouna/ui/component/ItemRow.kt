package com.dream.shouna.ui.component

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/**
 * 物品行（搜索结果 / 位置浏览共用）。
 *
 * - F1：只有「名称 + 关键词高亮」。
 * - P0-03 起补 `subtitle`（路径 + 时间，由 VM 拼好后下传）与 `showOverdue`（FR-27 的 ⚠）。
 *   两者都是可选参数 → 既有调用点无需改动。
 * - P0-04 起补 `copyablePath`（FR-02 / US-04：结果行的路径长按复制）。
 *
 * 无状态：高亮区间由 `SearchScorer.highlightRange` 在 VM 侧算好；为 null 即纯文本行。
 * 剪贴板是**平台 UI 能力**（非业务工具），与 `LocationBreadcrumb` 一样留在组件层。
 */
@Composable
fun ItemRow(
    name: String,
    highlightRange: IntRange?,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    /** 副标题（如「家 › 储物间 · 3 个月前」）；null 表示不显示第二行。 */
    subtitle: String? = null,
    /** FR-27：该物品超期未确认 → 副标题行尾加 ⚠。 */
    showOverdue: Boolean = false,
    /**
     * FR-02 / US-04：副标题里的**位置路径**，非空时副标题行可长按复制该路径
     * （只复制路径，不含「· 最后确认 …」—— 与详情页面包屑的复制口径一致）。
     */
    copyablePath: String? = null,
) {
    val highlightColor = MaterialTheme.colorScheme.primaryContainer
    // 区间越界一律裁剪到 [0, name.length]，不抛异常。
    val rawRange = highlightRange
    val start = rawRange?.first?.coerceIn(0, name.length)
    val end = if (rawRange == null || start == null) 0 else (rawRange.last + 1).coerceIn(start, name.length)

    val text = buildAnnotatedString {
        append(name)
        // `end > start` 同时覆盖「区间为空」与「start 为 null」两种不可高亮的情形。
        if (start != null && end > start) {
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

    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyLarge,
        )

        if (subtitle != null) {
            val copyable = copyablePath?.takeIf { it.isNotBlank() }
            val copyModifier = if (copyable == null) {
                Modifier
            } else {
                Modifier.combinedClickable(
                    onClick = {},
                    onLongClick = {
                        clipboard.setText(AnnotatedString(copyable))
                        Toast.makeText(context, "已复制位置路径", Toast.LENGTH_SHORT).show()
                    },
                )
            }

            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .then(copyModifier),
            ) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OverdueMark(
                    visible = showOverdue,
                    modifier = Modifier.padding(start = 6.dp),
                )
            }
        }
    }
}
