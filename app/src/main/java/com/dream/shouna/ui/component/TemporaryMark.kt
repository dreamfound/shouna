package com.dream.shouna.ui.component

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * FR-06：临时位置的显著标记（P1-02 ⑤）。
 *
 * 无状态、无判定：**是否**临时由调用方决定（`Location.isTemporary` 作为渲染参数传入判定结果），
 * 本组件只负责把它画成一个短标签（守 `实现约束.md` §3-4）。
 *
 * 用文字符号而非图标资源：与 `LocationTreeItem` 的 `▾ / ▸` 同一取向，省一个 `material-icons`
 * 依赖，也不受字号锁定之外的缩放影响。
 *
 * 被调用方（P1-02 实现期）：`LocationTreeItem` 的树行尾、`StatsScreen` 的待归位清单行
 */
@Composable
fun TemporaryMark(modifier: Modifier = Modifier) {
    Text(
        text = "临时",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onTertiaryContainer,
        modifier = modifier
            .background(
                color = MaterialTheme.colorScheme.tertiaryContainer,
                shape = RoundedCornerShape(4.dp),
            )
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
