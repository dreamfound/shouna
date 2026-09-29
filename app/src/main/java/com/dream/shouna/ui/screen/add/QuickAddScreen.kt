package com.dream.shouna.ui.screen.add

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.CategoryChips
import com.dream.shouna.ui.component.LocationPickerSheet

/**
 * 有状态包装层：唯一职责 = `hiltViewModel()` 取 ViewModel 后把 UiState 下传（ARCHITECTURE §2）。
 */
@Composable
fun QuickAddRoute() {
    val viewModel: QuickAddViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val locationTree by viewModel.locationTree.collectAsStateWithLifecycle()
    val recentLocations by viewModel.recentLocations.collectAsStateWithLifecycle()
    val isPickerVisible by viewModel.isLocationPickerVisible.collectAsStateWithLifecycle()

    QuickAddScreen(
        uiState = uiState,
        categories = categories,
        locationTree = locationTree,
        recentLocations = recentLocations,
        isLocationPickerVisible = isPickerVisible,
        onNameChange = viewModel::onNameChange,
        onCategorySelected = viewModel::onCategorySelected,
        onNoteChange = viewModel::onNoteChange,
        onSaveAndContinue = viewModel::onSaveAndContinue,
        onOpenLocationPicker = viewModel::onOpenLocationPicker,
        onDismissLocationPicker = viewModel::onDismissLocationPicker,
        onLocationSelected = viewModel::onLocationSelected,
        onCreateLocation = viewModel::onCreateLocation,
    )
}

/**
 * 无状态页：**位置条（必填）** + 名称（自动聚焦）+ 分类 chips + 折叠备注 + 保存并继续 + 计数器。
 *
 * 与 F1 的唯一界面差别 = 位置条。位置未选时保存按钮禁用并给出原因文案
 * —— 这是「所有物品必须有位置」在 UI 上的唯一表达（ARCHITECTURE-P0 §0）。
 *
 * 保存成功后**位置条不清空**（VM 保留位置），所以连续录入只需重复「输名称 → 保存」。
 */
@Composable
fun QuickAddScreen(
    uiState: QuickAddUiState,
    categories: List<Category>,
    locationTree: List<LocationTreeRow>,
    recentLocations: List<Location>,
    isLocationPickerVisible: Boolean,
    onNameChange: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onNoteChange: (String) -> Unit,
    onSaveAndContinue: () -> Unit,
    onOpenLocationPicker: () -> Unit,
    onDismissLocationPicker: () -> Unit,
    onLocationSelected: (String) -> Unit,
    onCreateLocation: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var noteExpanded by rememberSaveable { mutableStateOf(false) }

    val hasLocation = uiState.selectedLocationId != null

    // 首次进入与每次保存成功（sessionCount 变化）后都把焦点送回名称框 → FR-10「保持焦点」。
    LaunchedEffect(uiState.sessionCount) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        // 位置条：必填项。未选时用错误色提示，点击打开树选择弹层（弹层内可「＋ 新建位置」）。
        OutlinedButton(
            onClick = onOpenLocationPicker,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = if (hasLocation) {
                    "位置：${uiState.selectedLocationPath.ifEmpty { "已选" }}"
                } else {
                    "选择位置（必填）"
                },
                color = if (hasLocation) {
                    MaterialTheme.colorScheme.onSurface
                } else {
                    MaterialTheme.colorScheme.error
                },
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        OutlinedTextField(
            value = uiState.inputName,
            onValueChange = onNameChange,
            label = { Text(text = "物品名称") },
            singleLine = true,
            // IME 完成键**只收起键盘，不代替保存**：保存的唯一入口是下方「保存并继续」按钮
            // （键盘弹起时按钮由容器级 IME 避让抬到键盘上方，见 RouteHandler）。
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { keyboard?.hide() },
            ),
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // FR-12：可跳过，不选不影响保存。
        CategoryChips(
            categories = categories,
            selectedCategoryId = uiState.selectedCategoryId,
            onCategorySelected = onCategorySelected,
        )

        Spacer(modifier = Modifier.height(4.dp))

        TextButton(onClick = { noteExpanded = !noteExpanded }) {
            Text(text = if (noteExpanded) "收起备注" else "添加备注")
        }
        if (noteExpanded) {
            OutlinedTextField(
                value = uiState.note.orEmpty(),
                onValueChange = onNoteChange,
                label = { Text(text = "备注") },
                minLines = 2,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                keyboard?.hide()
                onSaveAndContinue()
            },
            enabled = uiState.canSave,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = "保存并继续")
        }

        // 禁用原因直说，避免用户对着灰按钮猜（位置必填是本次新增的硬约束）。
        if (!hasLocation) {
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = "请先选择或新建一个位置",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "本次已记录 ${uiState.sessionCount} 件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }

    if (isLocationPickerVisible) {
        LocationPickerSheet(
            rows = locationTree,
            recentLocations = recentLocations,
            selectedLocationId = uiState.selectedLocationId,
            onSelect = onLocationSelected,
            onCreateLocation = onCreateLocation,
            onDismiss = onDismissLocationPicker,
        )
    }
}
