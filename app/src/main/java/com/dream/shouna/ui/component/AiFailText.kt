package com.dream.shouna.ui.component

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.dream.shouna.R
import com.dream.shouna.domain.ai.AiFailReason

/**
 * AI 失败原因 → 一行文案。
 *
 * 【为什么单拆一个文件】`AiFailReason` 由两个页面（录入页、首页）共用，文案必须一致 ——
 * 映射留在任一页里，另一页要么反向依赖那一页，要么抄一份（抄的那份迟早会与原版不一致）。
 *
 * 【为什么是 `@Composable`】取值要走 `stringResource`（持资源的是界面层）。VM / 用例层只给
 * 原因枚举，不与 Android 资源耦合（`实现约束.md` §2-8 的同一条取向）。
 */
@Composable
fun AiFailReason.text(): String = stringResource(messageRes())

/** 每种失败一句，用户能据此知道「下一步做什么」（`V2-FR-07`：失败一律回落人工，不阻塞）。 */
@StringRes
private fun AiFailReason.messageRes(): Int = when (this) {
    AiFailReason.NO_KEY -> R.string.ai_entry_fail_no_key
    AiFailReason.NEED_LOCATION_FOR_PHOTO -> R.string.ai_entry_fail_need_location
    AiFailReason.NEED_INPUT -> R.string.ai_entry_fail_need_input
    AiFailReason.PHOTO_READ_FAILED -> R.string.ai_entry_fail_photo_read
    AiFailReason.PARSE_FAILED -> R.string.ai_entry_fail_parse
    AiFailReason.NOTHING_RECOGNIZED -> R.string.ai_entry_fail_nothing
    AiFailReason.NOTHING_SELECTED -> R.string.ai_entry_fail_nothing_selected
}
