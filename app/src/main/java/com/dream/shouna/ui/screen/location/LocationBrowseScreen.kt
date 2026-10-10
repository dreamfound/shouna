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
import androidx.compose.runtime.MutableState
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
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.common.EmptyState
import com.dream.shouna.ui.component.item.ItemRow
import com.dream.shouna.ui.component.location.LocationBreadcrumb
import com.dream.shouna.ui.component.location.LocationMoveSheet
import com.dream.shouna.ui.component.location.LocationPickerSheet
import com.dream.shouna.ui.component.location.LocationTreeItem
import com.dream.shouna.ui.component.item.TemporaryMark
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.goItemDetail
import com.dream.shouna.ui.navigation.goLocationBrowse
import com.dream.shouna.ui.navigation.goQuickAdd
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（docs/实现约束.md）：取 ViewModel、把 UiState 下传；导航动作在此组装。
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
        // P1 补齐（`PRD.md` §6.2 的 `BROWSE --> ADD`）：带着**当前这一层**进录入页。
        // 根级时 `locationId` 为 null ⇒ 按钮本就不显示（见 Screen 内注释），这里的 `?.let` 是二道保险。
        onQuickAddHere = { uiState.locationId?.let { navController.goQuickAdd(it) } },
    )
}

/**
 * P-BROWSE（FR-01 / 02 / 03 / 05 + P1-02 的 04 / 06 / 21 / 28）：当前层的位置浏览。
 *
 * 一页只表达一层 —— 面包屑显示「我在哪」，列表显示「这一层有什么」，底部提供增删改与
 * P1-02 新增的**移动 / 合并 / 标记临时 / 含子层一键确认**，以及**「＋ 往这里记一件」**。
 *
 * 【新增入口的定位】`PRD.md` §6.1 给 P-BROWSE 的职责里本就写着「批量录入入口」、§6.2 的跳转图
 * 亦有一条 `BROWSE --> ADD`；入口长期缺失（功能未实现，非入口被藏）。本次补齐：点它进 P-ADD，
 * **位置条预选当前这一层** —— 这正是「打开一个箱子，往里连续记东西」的主路径。
 * 只给非根级：根级不是一个位置，位置条无处可预填（位置必填口径见 docs/PRD.md）。
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
    onQuickAddHere: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 7 个弹层开关在本页**只写不读**（`flag.value = true` 不建立订阅），
    // `if (flag.value)` 的读取全部下沉到下方 4 个私有宿主 —— 读在哪，失效就只到哪。
    // 若在此读取，开任何一个弹层都会重跑整页，含 `LazyColumn` 里已可见的每一行。
    val creating = remember { mutableStateOf(false) }
    val renaming = remember { mutableStateOf(false) }
    val deleting = remember { mutableStateOf(false) }
    val migrating = remember { mutableStateOf(false) }
    val moving = remember { mutableStateOf(false) }
    val merging = remember { mutableStateOf(false) }
    val confirmingAll = remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }

    // 弹层标题 / 初值用的「当前层名」：面包屑取末段，四处弹层共用（原先各算一遍）。
    val currentName = uiState.pathText.substringAfterLast(" › ")

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

        // `PRD.md` §6.1 给 P-BROWSE 的「批量录入入口」（§6.2 跳转图的 `BROWSE --> ADD`）。
        // 单起一行且用主色 `Button`：这是本页最高频的去向（开箱 → 往里记），不该混进下面那两排
        // 次级动作里；也避免把 Row 压到 5 个按钮而溢出（两排现有 3 + 4 个）。
        // 文案与首页主按钮「＋ 记一件」成对：这里多出的「往这里」明确了落点 = **当前这一层**。
        if (!uiState.isRoot) {
            Button(
                onClick = onQuickAddHere,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
            ) {
                Text(text = "＋ 往这里记一件")
            }
        }

        HorizontalDivider()

        Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
        ) {
            Button(onClick = { draft = ""; creating.value = true }) { Text(text = "＋ 子位置") }
            if (!uiState.isRoot) {
                TextButton(onClick = { draft = ""; renaming.value = true }) { Text(text = "重命名") }
                TextButton(onClick = { deleting.value = true }) { Text(text = "删除") }
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
                TextButton(onClick = { moving.value = true }) { Text(text = "移动到…") }
                TextButton(onClick = { merging.value = true }) { Text(text = "合并到…") }
                TextButton(onClick = { onToggleTemporary(!uiState.isTemporary) }) {
                    Text(text = if (uiState.isTemporary) "取消临时" else "标记临时")
                }
                TextButton(onClick = { confirmingAll.value = true }) { Text(text = "全部确认") }
            }
        }
    }

    LocationTextDialogs(
        creating = creating,
        renaming = renaming,
        currentName = currentName,
        onCreateChild = onCreateChild,
        onRenameCurrent = onRenameCurrent,
    )

    LocationDeleteDialogs(
        deleting = deleting,
        migrating = migrating,
        currentName = currentName,
        allRows = uiState.allRows,
        excludeLocationId = uiState.locationId,
        onDeleteCurrent = onDeleteCurrent,
    )

    // FR-04：移动。目标集已由 ViewModel 排除自身与自身子树（判定不进组件，§3-4）。
    // FR-04：合并。方向由用户显式给出（把当前层并进所选位置）——不做自动推断（P1 §8.1-10）。
    // 两者共用同一目标弹层内容，只换标题。
    LocationMoveMergeSheets(
        moving = moving,
        merging = merging,
        currentName = currentName,
        moveTargetRows = uiState.moveTargetRows,
        onMoveCurrent = onMoveCurrent,
        onMergeCurrent = onMergeCurrent,
    )

    // FR-28：批量确认前**先摊开影响范围**（含子层 M 件），避免「以为只动本层」。
    LocationConfirmAllDialog(
        confirmingAll = confirmingAll,
        currentName = currentName,
        subtreeItemCount = uiState.subtreeItemCount,
        onConfirmAllInCurrent = onConfirmAllInCurrent,
    )
}

// ---- 弹层宿主：本页 7 个开关的唯一读取点（父层只写不读）----
//
// 【为什么单拆这一层】Compose 的失效粒度是「**读了该 state 的那个重启作用域**」。7 个开关若在
// [LocationBrowseScreen] 体内读，开任何一个弹层都会重跑整页 —— 包含 `LazyColumn` 里已可见的每一行
// （`LocationTreeItem` 的 `row` 由 `child.copy(depth = 0)` 现造，逐行判定为「变了」）。
// 下沉后按钮只写（**写不订阅**），翻开关只重跑对应的宿主那一小块。
//
// 挂载位置不影响观感：两个 `AlertDialog` 与两个 `ModalBottomSheet` 都渲染为**独立窗口**，
// 在组合树里的位置不决定层级。

/** 新建子位置 / 重命名：两个单行输入对话框。 */
@Composable
private fun LocationTextDialogs(
    creating: MutableState<Boolean>,
    renaming: MutableState<Boolean>,
    currentName: String,
    onCreateChild: (String) -> Unit,
    onRenameCurrent: (String) -> Unit,
) {
    if (creating.value) {
        TextInputDialog(
            title = "新建子位置",
            initial = "",
            onConfirm = { name -> onCreateChild(name); creating.value = false },
            onDismiss = { creating.value = false },
        )
    }

    if (renaming.value) {
        TextInputDialog(
            title = "重命名位置",
            initial = currentName,
            onConfirm = { name -> onRenameCurrent(name); renaming.value = false },
            onDismiss = { renaming.value = false },
        )
    }
}

/** 删除位置（二次确认 + 两档去向）与其后的「迁移物品到…」目标选择。 */
@Composable
private fun LocationDeleteDialogs(
    deleting: MutableState<Boolean>,
    migrating: MutableState<Boolean>,
    currentName: String,
    allRows: List<LocationTreeRow>,
    excludeLocationId: String?,
    onDeleteCurrent: (LocationDeleteMode, String?) -> Unit,
) {
    if (deleting.value) {
        AlertDialog(
            onDismissRequest = { deleting.value = false },
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
                        deleting.value = false
                    },
                ) {
                    Text(text = "标记不在了并删除")
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = { deleting.value = false; migrating.value = true }) {
                        Text(text = "迁移物品到…")
                    }
                    TextButton(onClick = { deleting.value = false }) { Text(text = "取消") }
                }
            },
        )
    }

    if (migrating.value) {
        LocationPickerSheet(
            rows = allRows.filter { it.location.id != excludeLocationId },
            recentLocations = emptyList(),
            selectedLocationId = null,
            onSelect = { targetId ->
                onDeleteCurrent(LocationDeleteMode.MIGRATE, targetId)
                migrating.value = false
            },
            onCreateLocation = { /* 迁移目标必须已存在，不支持此处新建 */ },
            onDismiss = { migrating.value = false },
            // 迁移目标必须已存在 —— 关掉新建入口，不给一个点了没反应的按钮。
            allowCreate = false,
        )
    }
}

/** FR-04：移动 / 合并的目标选择弹层（只换标题；`currentName` 为空串时标题仍可读）。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LocationMoveMergeSheets(
    moving: MutableState<Boolean>,
    merging: MutableState<Boolean>,
    currentName: String,
    moveTargetRows: List<LocationTreeRow>,
    onMoveCurrent: (String?) -> Unit,
    onMergeCurrent: (String) -> Unit,
) {
    if (moving.value) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { moving.value = false }, sheetState = sheetState) {
            LocationMoveSheet(
                rows = moveTargetRows,
                disabledIds = emptySet(),
                title = "把「$currentName」移动到…",
                onPick = { targetId ->
                    onMoveCurrent(targetId)
                    moving.value = false
                },
                onDismiss = { moving.value = false },
            )
            // 「根级」不是树上的节点，故不放进目标列表，单独一行 —— 把当前层提到最外层。
            TextButton(
                onClick = {
                    onMoveCurrent(null)
                    moving.value = false
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) {
                Text(text = "移到最外层（根级）")
            }
        }
    }

    if (merging.value) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(onDismissRequest = { merging.value = false }, sheetState = sheetState) {
            LocationMoveSheet(
                rows = moveTargetRows,
                disabledIds = emptySet(),
                title = "把「$currentName」合并进…",
                onPick = { targetId ->
                    onMergeCurrent(targetId)
                    merging.value = false
                },
                onDismiss = { merging.value = false },
            )
        }
    }
}

/** FR-28：含子层的批量确认（确认前列出影响范围）。 */
@Composable
private fun LocationConfirmAllDialog(
    confirmingAll: MutableState<Boolean>,
    currentName: String,
    subtreeItemCount: Int,
    onConfirmAllInCurrent: () -> Unit,
) {
    if (confirmingAll.value) {
        AlertDialog(
            onDismissRequest = { confirmingAll.value = false },
            title = { Text(text = "确认这一层的全部物品？") },
            text = {
                Text(
                    text = "将对「$currentName」及其所有子位置下" +
                        "共 $subtreeItemCount 件物品记一次「还在」。" +
                        "已标记「不在了」的物品不受影响。",
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        onConfirmAllInCurrent()
                        confirmingAll.value = false
                    },
                ) {
                    Text(text = "确认 $subtreeItemCount 件")
                }
            },
            dismissButton = {
                TextButton(onClick = { confirmingAll.value = false }) { Text(text = "取消") }
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
