package com.dream.shouna.ui.component

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * FR-27 超期标记：超过 `threshold_months`（默认 6）个月未确认时显示。
 *
 * 无状态：[visible] 由 VM 用 `TimeUtil.isOverdue` 判定后下传
 * —— Screen 层不持有业务工具（ARCHITECTURE §2）。
 */
@Composable
fun OverdueMark(
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    Text(
        text = "⚠ 超期未确认",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
        modifier = modifier,
    )
}
