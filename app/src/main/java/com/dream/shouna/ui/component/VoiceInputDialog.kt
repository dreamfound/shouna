package com.dream.shouna.ui.component

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * 语音输入弹窗（FR-18 的**简化形态**）：长按话筒说话、松手出文字。
 *
 * 【识别走哪条通道已与本文无关】识别内核（手势 / 权限 / 实时上屏 / 看门狗）全在 [VoiceCapturePad]，
 * 而它只认 `AsrEngine` 接口 —— 系统识别优先、不可用则内置离线（2026-10-10 起）。
 * 本弹窗对那次改动零感知，也不需要知道当前用的是哪一个引擎。
 *
 * 【本文件只剩一层壳】识别内核全在 [VoiceCapturePad] —— 语音 AI 录入页（P-VOICE-AI）
 * **页内直接用那个内核**，不需要再套一层窗口。本弹窗留给需要「在一个弹窗里补一句」的场合
 * （录入页的名称 / 备注框）。
 *
 * 【底栏只有「关闭」】原来「授予录音权限」也在底栏，现在它随内核走（放进 [VoiceCapturePad]）——
 * 页内宿主没有底栏可放，权限按钮只能跟内核在一起。这一步是行为等价、位置变化。
 *
 * 被调用方：`ui/screen/add/QuickAddScreen.kt` 的 `QuickAddVoiceDialog`。
 * 本弹窗与「填哪个字段」**解耦**：结果通过 [onResult] 抛给调用方，由调用方决定写进名称还是备注。
 */
@Composable
fun VoiceInputDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = "语音输入") },
        text = { VoiceCapturePad(onResult = onResult) },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(text = "关闭")
            }
        },
    )
}
