package com.dream.shouna.ui.screen.category

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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.domain.model.Category
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（ARCHITECTURE §2）。二次确认的态在 ViewModel（[CategoryManageViewModel.pendingDeleteId]），
 * 页面只负责渲染，避免「对话框开着但状态已变」这类两边各存一份的问题。
 */
@Composable
fun CategoryManageRoute() {
    val viewModel: CategoryManageViewModel = hiltViewModel()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val pendingDeleteId by viewModel.pendingDeleteId.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    CategoryManageScreen(
        categories = categories,
        pendingDeleteId = pendingDeleteId,
        onCreate = viewModel::onCreate,
        onRename = viewModel::onRename,
        onRequestDelete = viewModel::onRequestDelete,
        onConfirmDelete = viewModel::onConfirmDelete,
        onDismissDelete = viewModel::onDismissDelete,
        onBack = { navController.popBack() },
    )
}

/**
 * 分类管理页（FR-44）：列表 + 新建 + 改名 + 删除。
 *
 * 【内置 vs 自定义】内置分类显示「内置」标签且**不出删除入口**（可改名）；自定义分类两入口都出。
 * 删除**必须二次确认**（`实现约束.md` §4-2）：其下物品会回落「未分类」，是个不可逆的语义变化。
 *
 * 删除后列表自动刷新（数据源是 `observeCategories` 的流），本页不做手工重取。
 */
@Composable
fun CategoryManageScreen(
    categories: List<Category>,
    pendingDeleteId: String?,
    onCreate: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onRequestDelete: (String) -> Unit,
    onConfirmDelete: () -> Unit,
    onDismissDelete: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var creating by remember { mutableStateOf(false) }
    var renamingTarget by remember { mutableStateOf<Category?>(null) }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = onBack) { Text(text = "返回") }
            Text(
                text = "分类管理",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = { creating = true }) { Text(text = "＋ 新建") }
        }

        if (categories.isEmpty()) {
            Text(
                text = "还没有分类",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        } else {
            LazyColumn(modifier = Modifier.weight(1f)) {
                items(items = categories, key = { it.id }) { category ->
                    CategoryRow(
                        category = category,
                        onRename = { renamingTarget = category },
                        onDelete = { onRequestDelete(category.id) },
                    )
                }
            }
        }
    }

    if (creating) {
        CategoryNameDialog(
            title = "新建分类",
            initial = "",
            onConfirm = { name -> onCreate(name); creating = false },
            onDismiss = { creating = false },
        )
    }

    renamingTarget?.let { target ->
        CategoryNameDialog(
            title = "重命名分类",
            initial = target.name,
            onConfirm = { name -> onRename(target.id, name); renamingTarget = null },
            onDismiss = { renamingTarget = null },
        )
    }

    // 二次确认（`实现约束.md` §4-2）：只有**自定义**分类能走到这里（内置项无删除入口）。
    pendingDeleteId?.let { id ->
        val name = categories.firstOrNull { it.id == id }?.name.orEmpty()
        AlertDialog(
            onDismissRequest = onDismissDelete,
            title = { Text(text = "删除分类「$name」？") },
            text = {
                Text(
                    text = "使用这个分类的物品不会被删除，会变成「未分类」。",
                )
            },
            confirmButton = {
                TextButton(onClick = onConfirmDelete) { Text(text = "删除") }
            },
            dismissButton = {
                TextButton(onClick = onDismissDelete) { Text(text = "取消") }
            },
        )
    }
}

/** 一行分类：名称 + 内置标签 + 「改名」；自定义项额外给「删除」。 */
@Composable
private fun CategoryRow(
    category: Category,
    onRename: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp),
    ) {
        Text(
            text = category.name,
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.weight(1f),
        )

        if (category.isBuiltIn) {
            Text(
                text = "内置",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        // 内置分类可改名（FR-44）；改名不影响任何物品记录。
        TextButton(onClick = onRename) { Text(text = "改名") }

        // 内置分类不可删 —— 直接不给入口，而不是给一个点了没反应的按钮。
        if (!category.isBuiltIn) {
            TextButton(onClick = onDelete) { Text(text = "删除") }
        }
    }
    HorizontalDivider()
}

/** 单行文本输入的确认对话框（新建 / 改名共用）。 */
@Composable
private fun CategoryNameDialog(
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
