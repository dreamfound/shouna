package com.dream.shouna.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dream.shouna.domain.model.LocationTreeRow

/**
 * FR-04：移动 / 合并的**目标位置选择**弹层内容（P1-02）。
 *
 * 无状态：整棵树、禁用集与标题都由调用方下传；选中只回调 [onPick]。
 * **禁用集由调用方算好**——「不能移进自身子树」是业务判定（`LocationPath.isDescendantPath`），
 * 属 ViewModel 的职责，不放在组件里（守 `实现约束.md` §3-4）。
 *
 * 只画内容、不自带 `ModalBottomSheet` 壳：挂载方式（底部弹层 / 全屏对话框）由调用页决定，
 * 组件不预设，P1-02 接入时再定。骨架期**未挂载**，仅提供签名与静态渲染。
 */
@Composable
fun LocationMoveSheet(
    rows: List<LocationTreeRow>,
    disabledIds: Set<String>,
    title: String,
    onPick: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.fillMaxWidth(),
            )

            if (rows.isEmpty()) {
                Text(
                    text = "暂无可选目标",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 12.dp),
                )
            } else {
                LazyColumn(modifier = Modifier.fillMaxWidth()) {
                    items(items = rows, key = { it.location.id }) { row ->
                        val disabled = row.location.id in disabledIds
                        Text(
                            text = row.pathText,
                            style = MaterialTheme.typography.bodyLarge,
                            color = if (disabled) {
                                MaterialTheme.colorScheme.onSurfaceVariant
                            } else {
                                MaterialTheme.colorScheme.onSurface
                            },
                            modifier = Modifier
                                .fillMaxWidth()
                                // 禁用目标不可点（不改层级、不给出非法选择）。
                                .clickable(enabled = !disabled) { onPick(row.location.id) }
                                .padding(start = (row.depth * INDENT_DP).dp, top = 10.dp, bottom = 10.dp),
                        )
                    }
                }
            }

            TextButton(onClick = onDismiss) {
                Text(text = "取消")
            }
        }
    }
}

/** 每层缩进宽度，与 `LocationTreeItem` 保持一致（同一棵树在两个入口下观感统一）。 */
private const val INDENT_DP: Int = 16
