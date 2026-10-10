package com.dream.shouna.ui.component.location
import com.dream.shouna.ui.component.ai.AiItemDialog

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.dream.shouna.domain.model.LocationTreeRow

/**
 * **可折叠的位置树列表**（已展平的行 → 逐行渲染 + 展开 / 收起）。
 *
 * 【为什么单独抽出来】它有两个宿主，形状却完全一样：位置选择弹层（`LocationPickerSheet`，
 * 页面级）与逐条确认弹窗里的**位置选择态**（`AiItemDialog`，语音通道专用）。
 * 折叠算法（`collapsedIds` + 祖先链可见性）写两遍，迟早只修一处。
 *
 * 【为什么由本组件持有折叠态】折叠是**纯呈现状态** —— 调用方关心的是「用户选了哪一个」
 * （[onSelect] 回调），不是「哪几枝被折了」。同理它不进 ViewModel（§3-4：组件只接渲染参数与回调）。
 *
 * 【为什么默认全展开】用 `collapsedIds` 记「折叠了什么」（空集 = 全展开）而不是记「展开了什么」：
 * 新增的位置默认就是展开的，不必同步两套状态；用户为了找一个深层位置反复点击也是最烦的一种交互。
 *
 * 无状态（除折叠这一项呈现状态）：行数据、选中态与回调全部由调用方下传。
 */
@Composable
fun LocationTreeList(
    rows: List<LocationTreeRow>,
    selectedLocationId: String?,
    onSelect: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var collapsedIds by remember { mutableStateOf<Set<String>>(emptySet()) }

    val rowById = rows.associateBy { it.location.id }
    val visibleRows = rows.filter { row -> isVisible(row, rowById, collapsedIds) }

    LazyColumn(modifier = modifier.fillMaxWidth()) {
        items(items = visibleRows, key = { it.location.id }) { row ->
            LocationTreeItem(
                row = row,
                expanded = row.location.id !in collapsedIds,
                selected = row.location.id == selectedLocationId,
                onToggleExpand = {
                    collapsedIds = if (row.location.id in collapsedIds) {
                        collapsedIds - row.location.id
                    } else {
                        collapsedIds + row.location.id
                    }
                },
                onClick = { onSelect(row.location.id) },
            )
        }
    }
}

/** 行的祖先链上是否有被折叠者（被折叠即整枝不可见）。 */
private fun isVisible(
    row: LocationTreeRow,
    rowById: Map<String, LocationTreeRow>,
    collapsedIds: Set<String>,
): Boolean {
    var parentId = row.location.parentId
    var guard = 0
    while (parentId != null && guard < MAX_DEPTH_GUARD) {
        if (parentId in collapsedIds) return false
        parentId = rowById[parentId]?.location?.parentId
        guard += 1
    }
    return true
}

/** 防脏数据成环导致死循环。 */
private const val MAX_DEPTH_GUARD = 64
