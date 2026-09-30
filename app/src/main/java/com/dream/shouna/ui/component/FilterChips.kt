package com.dream.shouna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * FR-22：搜索页的**一个**筛选维度（分类 / 位置 / 状态各一行）。
 *
 * 之所以按维度拆成一个可复用的组件、而不是做成一块「筛选面板」：三个维度的取值来源
 * （分类表 / 位置树 / 状态枚举）与是否含子层各不相同，唯一共通的只有「一排单选 chip」。
 *
 * 无状态：[selectedId] 由调用方下传，[onSelect] 只回传 id（`null` = 取消该维度）。
 * 再次点击已选中的 chip 即取消 —— 与 `CategoryChips` 的既有交互一致，不引入第二种取消手势。
 *
 * 位置维度需**含子层**（P1 §8.1-7）：那层语义在 `SearchViewModel` 组装 options 时体现，
 * 组件只认 id 与 label。
 */
@Composable
fun FilterChips(
    label: String,
    options: List<FilterOption>,
    selectedId: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (options.isEmpty()) return

    Column(modifier = modifier.fillMaxWidth()) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 2.dp),
        )

        LazyRow(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 2.dp),
        ) {
            items(items = options, key = { it.id }) { option ->
                val selected = option.id == selectedId
                FilterChip(
                    selected = selected,
                    onClick = { onSelect(if (selected) null else option.id) },
                    label = { Text(text = option.label) },
                )
            }
        }
    }
}

/** 一个筛选项。[id] 与 [label] 分离：id 用于比较（分类与位置都是 UUID，不能拿名字当键）。 */
data class FilterOption(
    val id: String,
    val label: String,
)
