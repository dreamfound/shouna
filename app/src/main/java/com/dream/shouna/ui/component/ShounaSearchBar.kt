package com.dream.shouna.ui.component

import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp

/**
 * 首页搜索框 + 搜索页输入框（共用）。
 * 首页形态：只读、点击跳搜索页并聚焦（[onSearchClick] 非空）；
 * 搜索页形态：可编辑，[onValueChange] 驱动输入即搜。
 */
@Composable
fun ShounaSearchBar(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    readOnly: Boolean = false,
    onSearchClick: (() -> Unit)? = null,
) {
    val focusRequester = remember { FocusRequester() }
    val isEntryForm = !readOnly && onSearchClick == null

    // 搜索页形态：进入本页即聚焦，落地「首页点击搜索框 → 进搜索页并聚焦」。
    LaunchedEffect(isEntryForm) {
        if (isEntryForm) focusRequester.requestFocus()
    }

    Box(modifier = modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            placeholder = { Text(text = "搜索物品") },
            singleLine = true,
            readOnly = readOnly,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
            shape = RoundedCornerShape(12.dp),
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(focusRequester),
        )

        // 首页形态：透明覆盖层接管点击，避免焦点落到只读输入框上。
        if (onSearchClick != null) {
            Box(
                modifier = Modifier
                    .matchParentSize()
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = onSearchClick,
                    ),
            )
        }
    }
}
