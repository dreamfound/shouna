package com.dream.shouna.ui.component

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow

/**
 * 位置选择弹层（ARCHITECTURE-P0 §8.1-9）：底部弹层内嵌同一棵位置树 + **可选的「＋ 新建位置」**。
 *
 * 为什么用弹层而不是独立页：少一条路由变体、少一个跨页结果回传通道（`savedStateHandle`）。
 * 树可能很深，弹层高度按 80% 屏高固定。
 *
 * 「必须手动选择位置」在录入场景由此兑现：用户要么在树里点一个，要么用「＋ 新建位置」**输入**一个。
 * 默认全展开（用 `collapsedIds` 记录折叠项，空集 = 全展开），避免用户为了找一个深层位置反复点击。
 *
 * P1 起 [allowCreate] 可关：「归位到…」「移动到…」「迁移到…」这类**在既有位置里挑一个**的场景
 * 不需要新建入口 —— 与其摆一个点了没反应的按钮，不如整块不出现（守 `实现约束.md` §3-4：
 * 组件只接渲染参数，是否需要新建入口由调用方决定）。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationPickerSheet(
    rows: List<LocationTreeRow>,
    recentLocations: List<Location>,
    selectedLocationId: String?,
    onSelect: (String) -> Unit,
    onCreateLocation: (String) -> Unit,
    onDismiss: () -> Unit,
    /** 是否给出「＋ 新建位置」入口（默认给，录入页需要；挑目标的场景一般关掉）。 */
    allowCreate: Boolean = true,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    // 记「折叠了什么」而不是「展开了什么」：新增位置默认就是展开的，不必同步两套状态。
    var collapsedIds by remember { mutableStateOf<Set<String>>(emptySet()) }
    var creating by remember { mutableStateOf(false) }
    var newLocationName by remember { mutableStateOf("") }

    val rowById = rows.associateBy { it.location.id }
    val visibleRows = rows.filter { row -> isVisible(row, rowById, collapsedIds) }

    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
        Column(modifier = Modifier.fillMaxHeight(SHEET_HEIGHT_FRACTION)) {
            Text(
                text = "选择位置",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )

            // FR-11 / FR-23：最近使用的位置，一键落位。
            if (recentLocations.isNotEmpty()) {
                LazyRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    items(items = recentLocations, key = { RECENT_CHIP_PREFIX + it.id }) { location ->
                        AssistChip(
                            onClick = { onSelect(location.id) },
                            label = { Text(text = "最近 · ${location.name}") },
                        )
                    }
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 8.dp))

            // 「或输入位置」：新建一个位置节点。调用方不需要新建时整块不出现
            // （不给一个点了没反应的入口，与「内置分类不可删 → 直接不出现删除按钮」同一取向）。
            if (allowCreate) {
                if (creating) {
                    Row(
                        verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                    ) {
                        OutlinedTextField(
                            value = newLocationName,
                            onValueChange = { newLocationName = it },
                            label = { Text(text = "新位置名称") },
                            singleLine = true,
                            modifier = Modifier.weight(1f),
                        )
                        Button(
                            onClick = {
                                val name = newLocationName.trim()
                                if (name.isNotEmpty()) {
                                    onCreateLocation(name)
                                    newLocationName = ""
                                    creating = false
                                }
                            },
                            enabled = newLocationName.isNotBlank(),
                            modifier = Modifier.padding(start = 8.dp),
                        ) {
                            Text(text = "创建")
                        }
                    }
                    Text(
                        text = "将建在「${selectedLocationId?.let { id -> rowById[id]?.pathText } ?: "根级"}」之下",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                    androidx.compose.foundation.layout.Spacer(modifier = Modifier.height(8.dp))
                } else {
                    TextButton(
                        onClick = { creating = true },
                        modifier = Modifier.padding(horizontal = 8.dp),
                    ) {
                        Text(text = "＋ 新建位置")
                    }
                }
            }

            HorizontalDivider()

            LazyColumn(modifier = Modifier.fillMaxWidth()) {
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

/** 弹层高度占屏比。 */
private const val SHEET_HEIGHT_FRACTION = 0.8f

/** 防脏数据成环导致死循环。 */
private const val MAX_DEPTH_GUARD = 64

/** 「最近」chips 的 key 前缀，避免与树行的 key（位置 id）冲突。 */
private const val RECENT_CHIP_PREFIX = "recent-"
