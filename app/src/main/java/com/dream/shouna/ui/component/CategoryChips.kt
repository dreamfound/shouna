package com.dream.shouna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dream.shouna.domain.model.Category

/**
 * FR-12：内置分类 chips 一排，点击即赋值，可跳过（再次点击取消选中）。
 *
 * 无状态：选中态由外部 [selectedCategoryId] 下传，点击只回调 [onCategorySelected]。
 */
@Composable
fun CategoryChips(
    categories: List<Category>,
    selectedCategoryId: String?,
    onCategorySelected: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    LazyRow(
        modifier = modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(horizontal = 2.dp),
    ) {
        items(items = categories.sortedBy { it.sortOrder }, key = { it.id }) { category ->
            val selected = category.id == selectedCategoryId
            FilterChip(
                selected = selected,
                // 再次点击已选中的 chip → 传 null = 取消选中（可跳过，FR-12）。
                onClick = { onCategorySelected(if (selected) null else category.id) },
                label = { Text(text = category.name) },
            )
        }
    }
}
