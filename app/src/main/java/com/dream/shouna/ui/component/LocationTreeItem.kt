package com.dream.shouna.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.dream.shouna.domain.model.LocationTreeRow

/**
 * 位置树的一行（P0-02 §4 ⑤）。
 *
 * 无状态：展开态与选中态都由外部下传（展开集由调用方的 `expandedIds` 持有，
 * 选中态来自当前选择），本组件只负责渲染与回调。
 *
 * 展开箭头用文字符号（`▾` / `▸`）而非图标资源：省一个 `material-icons` 依赖，
 * 且不受字号锁定（`fontScale = 1f`）之外的影响。
 */
@Composable
fun LocationTreeItem(
    row: LocationTreeRow,
    expanded: Boolean,
    selected: Boolean,
    onToggleExpand: () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .background(
                if (selected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent,
            )
            // 缩进 = 层级 × 16dp：层级信息只在这里体现（不额外画连接线）。
            .padding(start = (row.depth * INDENT_DP).dp, end = 12.dp, top = 10.dp, bottom = 10.dp),
    ) {
        if (row.hasChildren) {
            Text(
                text = if (expanded) "▾" else "▸",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier
                    // 箭头只切换展开态，不触发选中（两级点击分离）。
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 4.dp),
            )
        } else {
            Spacer(modifier = Modifier.width(20.dp))
        }

        Text(
            text = row.location.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        Text(
            text = "${row.itemCount} 件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (trailing != null) {
            trailing()
        }
    }
}

/** 每层缩进宽度。 */
private const val INDENT_DP: Int = 16
