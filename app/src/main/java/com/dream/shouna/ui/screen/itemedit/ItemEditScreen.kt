package com.dream.shouna.ui.screen.itemedit

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.domain.model.Category
import com.dream.shouna.ui.component.AliasEditor
import com.dream.shouna.ui.component.CategoryChips
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（ARCHITECTURE §2）：取 ViewModel、把 UiState 与分类列表下传。
 * 保存成功的事件在本层消费（关页），导航不进 `ItemEditScreen`。
 */
@Composable
fun ItemEditRoute(itemId: String) {
    val viewModel: ItemEditViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }

    // 保存成功 → 静默关闭本页（零弹窗取向）。失败不发事件，用户留在本页改。
    LaunchedEffect(Unit) {
        viewModel.savedEvents.collect { navController.popBack() }
    }

    ItemEditScreen(
        uiState = uiState,
        categories = categories,
        onCategorySelected = viewModel::onCategorySelected,
        onAddAlias = viewModel::onAddAlias,
        onRemoveAlias = viewModel::onRemoveAlias,
        onQuantityChange = viewModel::onQuantityChange,
        onNoteChange = viewModel::onNoteChange,
        onSave = viewModel::onSave,
        onBack = { navController.popBack() },
    )
}

/**
 * P-ITEM-EDIT 物品编辑页（别名 / 数量 / FR-14 跳转落点）。
 *
 * 名称**只读展示**（本页不可改名）；可改四项：分类 / 别名 / 数量 / 备注。
 * 数量非法（非整数、0、负数）时保存按钮置灰 —— 判定在 VM 的 `canSave`，本页只读它。
 *
 * 内容区可滚动：字段较多，键盘弹起时（容器级 IME 避让已处理）仍要能滚到保存按钮。
 */
@Composable
fun ItemEditScreen(
    uiState: ItemEditUiState,
    categories: List<Category>,
    onCategorySelected: (String?) -> Unit,
    onAddAlias: (String) -> Unit,
    onRemoveAlias: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 4.dp),
        ) {
            TextButton(onClick = onBack) { Text(text = "返回") }
            Text(
                text = "编辑物品",
                style = MaterialTheme.typography.titleMedium,
            )
        }

        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 16.dp),
        ) {
            // 名称只读：本页改的是分类 / 别名 / 数量 / 备注（改名不在 P1 范围）。
            Text(
                text = if (uiState.isLoaded) uiState.name else "（物品不存在）",
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))

            // FR-12 / FR-44：分类可选（列表来自分类管理页维护的动态表）。
            Text(
                text = "分类",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.height(4.dp))
            CategoryChips(
                categories = categories,
                selectedCategoryId = uiState.categoryId,
                onCategorySelected = onCategorySelected,
            )

            Spacer(modifier = Modifier.height(16.dp))

            AliasEditor(
                aliases = uiState.aliases,
                max = ItemEditViewModel.MAX_ALIAS_COUNT,
                onAdd = onAddAlias,
                onRemove = onRemoveAlias,
            )

            Spacer(modifier = Modifier.height(16.dp))

            OutlinedTextField(
                value = uiState.quantityInput,
                onValueChange = onQuantityChange,
                label = { Text(text = "数量") },
                singleLine = true,
                // 数量 = 整数 ≥ 1、无单位（`prd/11` Q3）：只给数字键盘，减少非法输入。
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                isError = uiState.isLoaded && !uiState.isQuantityValid,
                supportingText = {
                    if (uiState.isLoaded && !uiState.isQuantityValid) {
                        Text(text = "数量需要是不小于 1 的整数")
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(8.dp))

            OutlinedTextField(
                value = uiState.note,
                onValueChange = onNoteChange,
                label = { Text(text = "备注") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(modifier = Modifier.height(16.dp))
        }

        Button(
            onClick = onSave,
            enabled = uiState.canSave,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
        ) {
            Text(text = "保存")
        }
    }
}
