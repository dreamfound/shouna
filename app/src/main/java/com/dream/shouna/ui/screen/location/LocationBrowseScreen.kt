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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.data.repository.LocationDeleteMode
import com.dream.shouna.ui.component.EmptyState
import com.dream.shouna.ui.component.ItemRow
import com.dream.shouna.ui.component.LocationBreadcrumb
import com.dream.shouna.ui.component.LocationPickerSheet
import com.dream.shouna.ui.component.LocationTreeItem
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
    )
}

/**
 * P-BROWSE（FR-01 / 02 / 03 / 05）：当前层的位置浏览。
 *
 * 一页只表达一层 —— 面包屑显示「我在哪」，列表显示「这一层有什么」，底部提供增删改。
 * 【决策】删除对话框把两档写在同一处：「标记不在了」直接执行（不需要选目标），
 * 「迁移物品到…」再开一层选择弹层选目标 —— 因为 `item.location_id` 是 `NOT NULL`，
 * 两档都必须给物品一个去处。
 */
@Composable
fun LocationBrowseScreen(
    uiState: LocationBrowseUiState,
    onChildClick: (String) -> Unit,
    onItemClick: (String) -> Unit,
    onBack: () -> Unit,
    onCreateChild: (String) -> Unit,
    onRenameCurrent: (String) -> Unit,
    onDeleteCurrent: (LocationDeleteMode, String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var migrating by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
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

        LocationBreadcrumb(
            pathText = uiState.pathText,
            modifier = Modifier.padding(horizontal = 16.dp),
        )

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

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
        ) {
            Button(onClick = { draft = ""; creating = true }) { Text(text = "＋ 子位置") }
            if (!uiState.isRoot) {
                TextButton(onClick = { draft = ""; renaming = true }) { Text(text = "重命名") }
                TextButton(onClick = { deleting = true }) { Text(text = "删除") }
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
