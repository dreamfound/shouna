package com.dream.shouna.ui.component

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 详情页那一行相对时间（FR-26 的可见反馈）。
 *
 * 纯展示：文案由 `TimeUtil.relativeConfirmText` 产出后经 UiState 下传，本组件**不**直接调用
 * TimeUtil（§2：Screen 层不持有业务工具）。
 */
@Composable
fun RelativeTimeText(
    text: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.fillMaxWidth(),
    )
}
