package com.dream.shouna.ui.component

import androidx.compose.foundation.layout.size
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp

/**
 * 输入框**尾部**的语音按钮（`OutlinedTextField.trailingIcon` 的落点）。
 *
 * 【为什么用 emoji 而不是矢量图标】`Icons.Filled.Mic` 不在 `material-icons-core`（Compose 只随
 * material3 带一小套核心图标），要用它就得引入 `material-icons-extended` —— 为了一个图标加
 * 一个依赖不划算。本工程既有界面也是这个路子（「✓ 还在」「✕ 不在了」都是文字符号）。
 *
 * 用 [IconButton]（48dp）而不是裸 `Text`：守 NFR-15 的「可点控件 ≥ 48dp」，
 * 并用 `contentDescription` 让 TalkBack 能读出用途 —— emoji 本身没有可读文本。
 *
 * 被调用方：`ui/screen/add/QuickAddScreen.kt`（物品名称框、备注框各一个）。
 */
@Composable
fun VoiceInputButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    IconButton(
        onClick = onClick,
        modifier = modifier
            .size(48.dp)
            .semantics { contentDescription = "语音输入" },
    ) {
        Text(
            text = "🎤",
            style = MaterialTheme.typography.titleMedium,
        )
    }
}
