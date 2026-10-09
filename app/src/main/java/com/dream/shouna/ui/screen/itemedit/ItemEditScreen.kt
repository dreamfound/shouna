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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.AliasEditor
import com.dream.shouna.ui.component.CategoryChips
import com.dream.shouna.ui.component.LocationPickerSheet
import com.dream.shouna.ui.component.PhotoInputRow
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.popBack
import com.dream.shouna.util.PhotoStore

/**
 * 有状态包装层（docs/实现约束.md）：取 ViewModel、把 UiState 与分类列表下传。
 * 保存成功的事件在本层消费（删旧照片文件 → 关页），导航不进 `ItemEditScreen`。
 */
@Composable
fun ItemEditRoute(itemId: String) {
    val viewModel: ItemEditViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val locationTree by viewModel.locationTree.collectAsStateWithLifecycle()
    val navController = LocalNavController.current
    val context = LocalContext.current

    LaunchedEffect(itemId) {
        viewModel.load(itemId)
    }

    // 保存成功 → **先删被替换的旧照片文件，再关页**（零弹窗取向）；失败不发事件，用户留在本页改。
    //
    // 「先落库、后删文件」这个顺序不可颠倒：编辑页的照片是**延迟删除**的
    // （`PhotoInputRow(deleteReplacedImmediately = false)`，见其类注释），旧文件名只在
    // 保存成功的这一刻才真正无用。反过来的话，用户选完新图又返回不保存，库里 `photo_path`
    // 仍指向已被删掉的旧文件，详情页从此永远显示不出照片。
    LaunchedEffect(Unit) {
        viewModel.savedEvents.collect { event ->
            PhotoStore.delete(context, event.obsoletePhotoName)
            navController.popBack()
        }
    }

    ItemEditScreen(
        uiState = uiState,
        categories = categories,
        locationTree = locationTree,
        onNameChange = viewModel::onNameChange,
        onCategorySelected = viewModel::onCategorySelected,
        onAddAlias = viewModel::onAddAlias,
        onRemoveAlias = viewModel::onRemoveAlias,
        onQuantityChange = viewModel::onQuantityChange,
        onNoteChange = viewModel::onNoteChange,
        onPhotoChanged = viewModel::onPhotoChanged,
        onLocationSelected = viewModel::onLocationSelected,
        onSave = viewModel::onSave,
        onBack = { navController.popBack() },
    )
}

/**
 * P-ITEM-EDIT 物品编辑页（P1-07 起为**字段总入口**）。
 *
 * 可改七项：**名称 / 照片 / 位置** / 分类 / 别名 / 数量 / 备注。
 * 时间戳语义要分清：这七项里只有**位置**变化会刷新 `last_modified_at`，其余六项一律不动时间列。
 * 状态类动作（待归位 / 不在了 / 恢复）**不在这里** —— 它们按 §3.5 矩阵要刷新 `last_modified_at`，
 * 留在详情页即时生效，避免与「编辑保存」混成一套语义（见 `ItemEditViewModel` 类注释）。
 *
 * 名称 / 数量非法时保存按钮置灰 —— 判定在 VM 的 `canSave` / `isNameValid`，本页只读它。
 *
 * 内容区可滚动：字段较多，键盘弹起时（容器级 IME 避让已处理）仍要能滚到保存按钮。
 */
@Composable
fun ItemEditScreen(
    uiState: ItemEditUiState,
    categories: List<Category>,
    locationTree: List<LocationTreeRow>,
    onNameChange: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onAddAlias: (String) -> Unit,
    onRemoveAlias: (String) -> Unit,
    onQuantityChange: (String) -> Unit,
    onNoteChange: (String) -> Unit,
    onPhotoChanged: (String?) -> Unit,
    onLocationSelected: (String) -> Unit,
    onSave: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // 「更换位置」弹层的开合：本页的纯粹 UI 态，不进 ViewModel
    // （与 `StatsScreen.putBackTarget` 同一取向：VM 只接「落到哪个位置」这个最终结果）。
    var pickerVisible by remember { mutableStateOf(false) }

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
            if (!uiState.isLoaded) {
                // 未载入（并发删除等）：只给一句说明，不铺一张按了没反应的空白表单。
                Text(
                    text = "（物品不存在）",
                    style = MaterialTheme.typography.headlineSmall,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(modifier = Modifier.height(16.dp))
            } else {
                // P1-07：名称由只读改为可编辑。名称是必填项 ⇒ 空白不可保存。
                OutlinedTextField(
                    value = uiState.nameInput,
                    onValueChange = onNameChange,
                    label = { Text(text = "名称") },
                    singleLine = true,
                    isError = !uiState.isNameValid,
                    supportingText = {
                        if (!uiState.isNameValid) Text(text = "名称不能为空")
                    },
                    modifier = Modifier.fillMaxWidth(),
                )

                Spacer(modifier = Modifier.height(16.dp))

                // FR-16：照片（可拍 / 可从相册选 / 可移除）。
                // `deleteReplacedImmediately = false` —— 编辑页**必须**延迟删除，理由见组件类注释。
                PhotoInputRow(
                    photoName = uiState.photoName,
                    onPhotoChanged = onPhotoChanged,
                    enabled = !uiState.isSaving,
                    deleteReplacedImmediately = false,
                )

                Spacer(modifier = Modifier.height(16.dp))

                // P1-07：位置（可更换）。位置是必填项，故不给「清除位置」；文案 = 完整面包屑路径。
                Text(
                    text = "位置",
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = uiState.locationPath,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(
                        onClick = { pickerVisible = true },
                        enabled = !uiState.isSaving,
                    ) {
                        Text(text = "更换")
                    }
                }

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
                    isError = !uiState.isQuantityValid,
                    supportingText = {
                        if (!uiState.isQuantityValid) {
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

    // P1-07：更换位置。`allowCreate = false` —— 这里是在既有位置里挑一个去处，
    // 「新建位置」属位置管理（P-BROWSE / 录入页），不在这条路径上重复出现。
    if (pickerVisible) {
        LocationPickerSheet(
            rows = locationTree,
            recentLocations = emptyList(),
            selectedLocationId = uiState.locationId.ifEmpty { null },
            onSelect = { locationId ->
                onLocationSelected(locationId)
                pickerVisible = false
            },
            onCreateLocation = { /* 更换位置只从既有位置里挑，不支持此处新建 */ },
            onDismiss = { pickerVisible = false },
            allowCreate = false,
        )
    }
}
