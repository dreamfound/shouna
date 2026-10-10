package com.dream.shouna.ui.component.common

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController

/**
 * 「点空白处收起键盘」：把**未被任何子控件接管的那一次点击**变成 `clearFocus()` + `hide()`。
 *
 * 【为什么只挂容器】键盘是窗口级状态，与系统栏避让同源（**容器级唯一职责**，页面不各自处理）。
 * 当前唯一挂点是 `RouteHandler` 的 `NavHost`，一处覆盖全部页面，新增页面自动继承。
 *
 * 【为什么点按钮 / 输入框不误触】Compose 的手势派发是**子级优先、消费即止**：子控件
 * （`Button` / `TextField` / chip / 可滚动列表）自己吃掉点击后，事件**不会**冒泡到本层；
 * 只有真的无人接管的空白区（录入页 `Spacer(weight(1f))` 那一大片）才会落到这里。
 * 反过来也说明：不要指望本层在**已被子控件消费**的区域生效 —— 那种区域要单独处理。
 *
 * 【两个动作缺一不可】`clearFocus()` 把焦点从输入框摘掉（否则光标还在、下次输入会闪回），
 * `hide()` 立刻收键盘 —— 只调前者在部分 ROM 上键盘不收。
 *
 * 【`pointerInput(Unit)` 的 key 固定为 `Unit`】key 一变块协程即取消重启，`detectTapGestures`
 * 里的等待随之断掉；故 key 不得取任何会被自己翻转的状态（`code-map/05-改动须知.md` §1-21）。
 */
@Composable
fun Modifier.clearFocusOnTap(): Modifier {
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    return pointerInput(Unit) {
        detectTapGestures(
            onTap = {
                focusManager.clearFocus()
                keyboardController?.hide()
            },
        )
    }
}
