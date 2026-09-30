package com.dream.shouna.ui.screen.location

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.data.repository.LocationDeleteMode
import com.dream.shouna.ui.component.EmptyState
import com.dream.shouna.ui.component.ItemRow
import com.dream.shouna.ui.component.LocationBreadcrumb
import com.dream.shouna.ui.component.LocationMoveSheet
import com.dream.shouna.ui.component.LocationPickerSheet
import com.dream.shouna.ui.component.LocationTreeItem
import com.dream.shouna.ui.component.TemporaryMark
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goItemDetail
import com.dream.shouna.ui.navigation.goLocationBrowse
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（ARCHITECTURE §2）：取 ViewModel、把 UiState 下传；导航动作在此组装。
 */
@Composable
fun LocationBrowseRoute(locationId: String?) {
    val viewModel: LocationBrowseViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    LaunchedEffect(locationId) {
        viewModel.load(locationId)
    }

    LocationBrowseScreen(
        uiState = uiState,
        onChildClick = { childId -> navController.goLocationBrowse(childId) },
        onItemClick = { itemId -> navController.goItemDetail(itemId) },
        onBack = { navController.popBack() },
        onCreateChild = viewModel::onCreateChild,
        onRenameCurrent = viewModel::onRenameCurrent,
        onDeleteCurrent = viewModel::onDeleteCurrent,
        onMoveCurrent = viewModel::onMoveCurrent,
        onMergeCurrent = viewModel::onMergeCurrent,
        onToggleTemporary = viewModel::onToggleTemporaryCurrent,
        onConfirmAllInCurrent = viewModel::onConfirmAllInCurrent,
    )
}

/**
 * P-BROWSE（FR-01 / 02 / 03 / 05 + P1-02 的 04 / 06 / 21 / 28）：当前层的位置浏览。
 *
 * 一页只表达一层 —— 面包屑显示「我在哪」，列表显示「这一层有什么」，底部提供增删改与
 * P1-02 新增的**移动 / 合并 / 标记临时 / 含子层一键确认**。
 *
 * 【决策】删除对话框把两档写在同一处：「标记不在了」直接执行（不需要选目标），
 * 「迁移物品到…」再开一层选择弹层选目标 —— 因为 `item.location_id` 是 `NOT NULL`，
 * 两档都必须给物品一个去处。
 *
 * 【决策】移动与合并共用同一个目标选择弹层内容（[LocationMoveSheet]），只换标题：
 * 两者「选一个位置」的交互完全一致，差别只在动作语义，没必要做两套选择界面。
 * 移动的合法目标**多一个「根级」**（把当前层提到最外层）——它不是一个节点，故单独给一行入口。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LocationBrowseScreen(
    uiState: LocationBrowseUiState,
    onChildClick: (String) -> Unit,
    onItemClick: (String) -> Unit,
    onBack: () -> Unit,
    onCreateChild: (String) -> Unit,
    onRenameCurrent: (String) -> Unit,
    onDeleteCurrent: (LocationDeleteMode, String?) -> Unit,
    onMoveCurrent: (String?) -> Unit,
    onMergeCurrent: (String) -> Unit,
    onToggleTemporary: (Boolean) -> Unit,
    onConfirmAllInCurrent: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }
    var moving by remember { mutableStateOf(false) }
    var merging by remember { mutableStateOf(false) }
    var confirmingAll by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = onBack) { Text(text = "返回") }
            Text(
                text = if (uiState.isRoot) "位置" else "位置详情",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp),
        ) {
            LocationBreadcrumb(
                pathText = uiState.pathText,
                modifier = Modifier.weight(1f),
            )
            if (uiState.isTemporary) {
                TemporaryMark()
            }
        }

        // P1-02（FR-21）：本层与含子层两个数都摆出来 —— 「一键确认」会动到几件，用户看得到才敢按。
        if (!uiState.isRoot) {
            Text(
                text = "本层 ${uiState.directItemCount} 件 · 含子层共 ${uiState.subtreeItemCount} 件",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        LazyColumn(modifier = Modifier.weight(1f)) {
            items(items = uiState.children, key = { it.location.id }) { child ->
                LocationTreeItem(
                    // 当前层只显示一层 → 缩进归零，层级由面包屑表达。
                    row = child.copy(depth = 0),
                    expanded = true,
                    selected = false,
                    onToggleExpand = {},
                    onClick = { onChildClick(child.location.id) },
                )
            }
            items(items = uiState.items, key = { it.id }) { item ->
                ItemRow(
                    name = item.name,
                    // 位置浏览不是检索场景，无关键词可高亮。
                    highlightRange = null,
                    // P0-03 ③：列表行同样补「最后确认」与 FR-27 的 ⚠（路径由上方面包屑表达）。
                    subtitle = item.subtitle,
                    showOverdue = item.isOverdue,
                    onClick = { onItemClick(item.id) },
                )
            }
        }

        if (uiState.children.isEmpty() && uiState.items.isEmpty()) {
            EmptyState(
                text = if (uiState.isRoot) "还没有位置。点「＋ 子位置」建一个" else "这个位置还没有东西",
            )
        }

        HorizontalDivider()

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Button(onClick = { draft = ""; creating = true }) { Text(text = "＋ 子位置") }
            if (!uiState.isRoot) {
                TextButton(onClick = { draft = ""; renaming = true }) { Text(text = "重命名") }
                TextButton(onClick = { deleting = true }) { Text(text = "删除") }
            }
        }

        // P1-02：位置树深化的四个动作。均只在「非根级」时出现（根级不是一个位置）。
        if (!uiState.isRoot) {
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 16.dp, end = 16.dp, bottom = 12.dp),
            ) {
                TextButton(onClick = { moving = true }) { Text(text = "移动到…") }
                TextButton(onClick = { merging = true }) { Text(text = "合并到…") }
                TextButton(onClick = { onToggleTemporary(!uiState.isTemporary) }) {
                    Text(text = if (uiState.isTemporary) "取消临时" else "标记临时")
                }
                TextButton(onClick = { confirmingAll = true }) { Text(text = "全部确认") }
            }
        }
    }

    if (creating) {
        TextInputDialog(
            title = "新建子位置",
            initial = "",
            onConfirm = { name -> onCreateChild(name); creating = false },
            onDismiss = { creating = false },
        )
    }

    if (renaming) {
        TextInputDialog(
            title = "重命名位置",
            initial = uiState.pathText.substringAfterLast(" › "),
            onConfirm = { name -> onRenameCurrent(name); renaming = false },
            onDismiss = { renaming = false },
        )
    }

    if (deleting) {
        AlertDialog(
            onDismissRequest = { deleting = false },
            title = { Text(text = "删除这个位置？") },
            text = {
                Text(
                    text = "该位置及其所有子位置都会被删除。其下的物品不会被删除，" +
                        "但必须选择去向：标记为「不在了」保留，或迁移到其它位置。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onDeleteCurrent(LocationDeleteMode.ARCHIVE, null)
                        deleting = false
                    },
                ) {
                    Text(text = "标记不在了并删除")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleting = false; migrating = true }) {
                        Text(text = "迁移物品到…")
                    }
                    TextButton(onClick = { deleting = false }) { Text(text = "取消") }
                }
            },
        )
    }

    if (migrating) {
        LocationPickerSheet(
            rows = uiState.allRows.filter { it.location.id != uiState.locationId },
            recentLocations = emptyList(),
            selectedLocationId = null,
            onSelect = { targetId ->
                onDeleteCurrent(LocationDeleteMode.MIGRATE, targetId)
                migrating = false
            },
            onCreateLocation = { /* 迁移目标必须已存在，不支持此处新建 */ },
            onDismiss = { migrating = false },
            // 迁移目标必须已存在 —— 关掉新建入口，不给一个点了没反应的按钮。
            allowCreate = false,
        )
    }

    // FR-04：移动。目标集已由 ViewModel 排除自身与自身子树（判定不进组件，§3-4）。
    if (moving) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { moving = false }, sheetState = sheetState) {
            LocationMoveSheet(
                rows = uiState.moveTargetRows,
                disabledIds = emptySet(),
                title = "把「${uiState.pathText.substringAfterLast(" › ")}」移动到…",
                onPick = { targetId ->
                    onMoveCurrent(targetId)
                    moving = false
                },
                onDismiss = { moving = false },
            )
            // 「根级」不是树上的节点，故不放进目标列表，单独一行 —— 把当前层提到最外层。
            TextButton(
                onClick = {
                    onMoveCurrent(null)
                    moving = false
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(text = "移到最外层（根级）")
            }
        }
    }

    // FR-04：合并。方向由用户显式给出（把当前层并进所选位置）——不做自动推断（P1 §8.1-10）。
    if (merging) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { merging = false }, sheetState = sheetState) {
            LocationMoveSheet(
                rows = uiState.moveTargetRows,
                disabledIds = emptySet(),
                title = "把「${uiState.pathText.substringAfterLast(" › ")}」合并进…",
                onPick = { targetId ->
                    onMergeCurrent(targetId)
                    merging = false
                },
                onDismiss = { merging = false },
            )
        }
    }

    // FR-28：批量确认前**先摊开影响范围**（含子层 M 件），避免「以为只动本层」。
    if (confirmingAll) {
        AlertDialog(
            onDismissRequest = { confirmingAll = false },
            title = { Text(text = "确认这一层的全部物品？") },
            text = {
                Text(
                    text = "将对「${uiState.pathText.substringAfterLast(" › ")}」及其所有子位置下" +
                        "共 ${uiState.subtreeItemCount} 件物品记一次「还在」。" +
                        "已标记「不在了」的物品不受影响。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmAllInCurrent()
                        confirmingAll = false
                    },
                ) {
                    Text(text = "确认 ${uiState.subtreeItemCount} 件")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingAll = false }) { Text(text = "取消") }
            },
        )
    }
}

/** 单行文本输入的确认对话框（新建 / 重命名共用）。 */
@Composable
private fun TextInputDialog(
    title: String,
    initial: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var value by remember { mutableStateOf(initial) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title) },
        text = {
            OutlinedTextField(
                value = value,
                onValueChange = { value = it },
                label = { Text(text = "名称") },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
                enabled = value.isNotBlank(),
            ) {
                Text(text = "确定")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text(text = "取消") }
        },
    )
}
