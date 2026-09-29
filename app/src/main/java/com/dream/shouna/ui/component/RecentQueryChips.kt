package com.dream.shouna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * FR-23：最近搜索词（**仅搜索页空态**展示）。点击即回填输入框并立刻触发检索。
 *
 * 无状态：词表与点击回调都由 VM 下传。**为空时整块不渲染** —— 空态保持安静，
 * 不出现一个只有标题没有内容的区块。
 *
 * 用 `SuggestionChip` 而非 `FilterChip`：这里点击是「采用这条建议」而不是「切换一个筛选条件」，
 * 不存在选中态（FR-23 的最近搜索词没有多选语义）。
 */
@Composable
fun RecentQueryChips(
    queries: List<String>,
    onQueryClick: (String) -> Unit,
    modifier: Modifier = Modifier,
    title: String = "最近搜索",
) {
    if (queries.isEmpty()) return

    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        // 横向滚动与 `CategoryChips` 同一形态（词表上限 20 条，一行放不下）。
        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            // 去重由 `recent_search.normalized_query` 唯一索引保证 → key 唯一。
            items(items = queries, key = { it }) { query ->
                SuggestionChip(
                    onClick = { onQueryClick(query) },
                    label = { Text(text = query) },
                )
            }
        }
    }
}
