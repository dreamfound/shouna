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
import com.dream.shouna.ui.component.CategoryChips

/**
 * 有状态包装层：唯一职责 = `hiltViewModel()` 取 ViewModel 后把 UiState 下传（ARCHITECTURE §2）。
 */
@Composable
fun QuickAddRoute() {
    val viewModel: QuickAddViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()

    QuickAddScreen(
        uiState = uiState,
        categories = categories,
        onNameChange = viewModel::onNameChange,
        onCategorySelected = viewModel::onCategorySelected,
        onNoteChange = viewModel::onNoteChange,
        onSaveAndContinue = viewModel::onSaveAndContinue,
    )
}

/**
 * 无状态页：名称（自动聚焦，唯一必填）+ 分类 chips + 折叠备注 + 保存并继续 + 计数器，零弹窗。
 */
@Composable
fun QuickAddScreen(
    uiState: QuickAddUiState,
    categories: List<Category>,
    onNameChange: (String) -> Unit,
    onCategorySelected: (String?) -> Unit,
    onNoteChange: (String) -> Unit,
    onSaveAndContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val focusRequester = remember { FocusRequester() }
    val keyboard = LocalSoftwareKeyboardController.current
    var noteExpanded by rememberSaveable { mutableStateOf(false) }

    // 首次进入与每次保存成功（sessionCount 变化）后都把焦点送回名称框 → FR-10「保持焦点」。
    LaunchedEffect(uiState.sessionCount) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(16.dp),
    ) {
        OutlinedTextField(
            value = uiState.inputName,
            onValueChange = onNameChange,
            label = { Text(text = "物品名称") },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = {
                    if (uiState.canSave) {
                        keyboard?.hide()
                        onSaveAndContinue()
                    }
                },
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

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = "本次已记录 ${uiState.sessionCount} 件",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
