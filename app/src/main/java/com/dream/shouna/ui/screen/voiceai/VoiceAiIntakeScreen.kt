package com.dream.shouna.ui.screen.voiceai

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dream.shouna.R
import com.dream.shouna.domain.ai.AiState
import com.dream.shouna.domain.model.Category
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.ui.component.ai.AiQueueState
import com.dream.shouna.ui.component.ai.AiItemDialog
// 【2026-10-10 停用 · 注释保留】原 `import com.dream.shouna.ui.component.AudioCapturePad`
import com.dream.shouna.ui.component.ai.VoiceCapturePad
import com.dream.shouna.ui.component.ai.text
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.popBack

/**
 * 有状态包装层（docs/实现约束.md）：唯一职责 = `hiltViewModel()` 取 ViewModel 后把 UiState 下传。
 *
 * 本页**没有「离开时要清什么」这件事** —— 与拍照页最大的结构差别。那边有一张尚未落库的临时照片
 * 要收（否则成为私有目录里的孤儿），本页的全部产物都只在内存里：队列、进度、汇总。
 * 已确认的件在点确认那一刻就投给了后台串行落库（应用级作用域），**不随本页销毁而取消**
 * —— 所以返回键是一条干净的 `popBackStack()`。
 */
@Composable
fun VoiceAiIntakeRoute() {
    val viewModel: VoiceAiIntakeViewModel = hiltViewModel()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    // 结果段（队列 / 逐条进度 / 汇总）在独立流上：它与拍照 AI 录入页共用同一份实现
    // （`ui/ai/AiItemQueue`），不在本页的 UiState 里。
    val queueState by viewModel.queueState.collectAsStateWithLifecycle()
    // 弹窗内「位置选择态」与「分类 chips」的数据源。两条都在独立流上下发 ——
    // 它们各自的变更不该把整页 body 拖进重组（与拍照页同一取向）。
    val locationTree by viewModel.locationTree.collectAsStateWithLifecycle()
    val categories by viewModel.categories.collectAsStateWithLifecycle()
    val navController = LocalNavController.current

    VoiceAiIntakeScreen(
        uiState = uiState,
        queue = queueState,
        categories = categories,
        locationTree = locationTree,
        onBack = { navController.popBack() },
        onSpokenText = viewModel::onSpokenText,
        // 【2026-10-10 停用 · 注释保留】录音通道的接线：`onSpokenAudio = viewModel::onSpokenAudio,`
        onItemNameChanged = viewModel::onItemNameChanged,
        onItemNoteChanged = viewModel::onItemNoteChanged,
        onItemCategorySelected = viewModel::onItemCategorySelected,
        onItemLocationSelected = viewModel::onItemLocationSelected,
        onItemConfirm = viewModel::onConfirmItem,
        onItemDiscard = viewModel::onDiscardItem,
        onCloseIntake = viewModel::onCloseIntake,
    )
}

/**
 * 无状态页：**录一段话 → 逐条确认**。整页没有位置条、也没有「识别」按钮 —— 位置由那段话决定，
 * 松手就是收工（`V2-FR-10` 的「用户不接触原始文本」）。
 *
 * 【输入是「说话变字」】长按说、抬手停，识别出来的**文字**交给模型（`V2-FR-10` 的原始口径）。
 * ⚠️ 上行给模型的只有那段**文字**，音频在本机识别完就丢（走 `VoiceCapturePad` 与
 * `data/local/asr/`，不出设备）。
 *
 * 【2026-10-10 试过「录音直送」，已回退】曾把这一行话筒换成 `AudioCapturePad`（长按录一段
 * WAV、连同提示词**直接上行**给模型），但**平台不受理音频输入**：`content` part 只认
 * `text` / `image_url` / `file`（`input_audio` 被 422 枚举拒收），而 `file` 槽也只吃图片
 * （400：格式白名单里没有音频）。那次改动的代码**整体注释保留**（`data/local/audio/`、
 * `ui/component/AudioCapturePad.kt`），将来平台开口或换供应商时可按注释接回；本页现用回
 * `VoiceCapturePad`。
 *
 * 【与拍照 AI 录入页（P-PHOTO-AI）的分工】两页的**结果段逐字相同**（同一个 `AiItemQueue` +
 * 同一个 `AiItemDialog`），差别全在输入段：本页一个话筒，那边「位置条 → 照片行 → 识别按钮」。
 * 说不清东西放在哪时，拍一张、自己挑位置；说得清时，说一句、位置跟着话走。
 *
 * 【弹窗里的位置可改】本页传 `locationEditable = true` + `locationRows`：位置是模型从那句话里
 * **猜的**，猜错 / 降级 / 没说全都要能就地纠（用户 2026-10-09 口径）。拍照页传 `false` ——
 * 那边的位置是用户识别前亲手选的。
 *
 * 【话筒在解析期间失能】`enabled` 同时看两个条件：AI 总开关（关着时**一个字都不该录** ——
 * `V2-AI-PRD` §5 的「未开启零请求」）与「当前没有在途请求」（避免两次解析互相覆盖结果）。
 *
 * 失败只有一条降级路径：**一行行内提示**，不弹窗、不阻塞（`实现约束.md` §4-1、`V2-FR-07`）。
 */
@Composable
fun VoiceAiIntakeScreen(
    uiState: VoiceAiIntakeUiState,
    queue: AiQueueState,
    categories: List<Category>,
    locationTree: List<LocationTreeRow>,
    onBack: () -> Unit,
    onSpokenText: (String) -> Unit,
    // 【2026-10-10 停用 · 注释保留】录音入口的形参：`/** 录完一段音频（裸 base64 的 WAV）。 */`
    // onSpokenAudio: (String) -> Unit,
    onItemNameChanged: (String) -> Unit,
    onItemNoteChanged: (String) -> Unit,
    onItemCategorySelected: (String?) -> Unit,
    onItemLocationSelected: (String, String) -> Unit,
    onItemConfirm: () -> Unit,
    onItemDiscard: () -> Unit,
    onCloseIntake: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler { onBack() }

    val parsing = uiState.aiState is AiState.Parsing

    Column(
        modifier = modifier
            .fillMaxSize()
            // 本页内容会随软键盘长高（键盘避让已由 RouteHandler 的 NavHost 统一处理，本页不自加
            // `imePadding`）：超出时整体可滚，免得小屏上「发送」按钮被顶出屏幕。
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = "返回")
        }

        Text(
            text = stringResource(R.string.voice_ai_title),
            style = MaterialTheme.typography.titleMedium,
        )

        Spacer(modifier = Modifier.height(4.dp))

        // 说这句话时**位置必须说出来**：模型这一路只负责把它解析成库里的位置（解析不出就降级到
        // 最近的已存在祖先，再不行就得用户在弹窗里补）。给一个例句比讲规则有用。
        Text(
            text = stringResource(R.string.voice_ai_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(modifier = Modifier.height(12.dp))

        // 页内话筒（无弹窗壳）：长按说话、抬手出字，识别结果**不落任何输入框**、直接进 AI。
        //
        // 【2026-10-10 停用 · 注释保留】这里曾换成录音件 ——
        //     AudioCapturePad(onRecorded = onSpokenAudio, enabled = uiState.aiEnabled && !parsing)
        // 弃用原因见本函数 KDoc（平台不受理音频输入）。`VoiceCapturePad` 与它的识别引擎
        // （`data/local/asr/`）一行未动；本页与录入页（P-ADD）的 🎤 就是同一个组件。
        VoiceCapturePad(
            onResult = onSpokenText,
            enabled = uiState.aiEnabled && !parsing,
        )

        Spacer(modifier = Modifier.height(8.dp))

        // TODO(临时测试入口：待移除) 打字与说话**共用同一个 `onSpokenText`** —— 门控、轮次号、
        // 密钥前置判断、`createMissingLayers = false` 的位置降级全部照跑，测的是真链路而非旁路。
        // 发送后清空输入框，便于连着试下一句。
        var testInputText by remember { mutableStateOf("") }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedTextField(
                value = testInputText,
                onValueChange = { testInputText = it },
                singleLine = true,
                placeholder = { Text(text = "客厅抽屉里的充电器") },
                modifier = Modifier.weight(1f),
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextButton(
                onClick = {
                    val spoken = testInputText.trim()
                    if (spoken.isNotEmpty()) {
                        onSpokenText(spoken)
                        testInputText = ""
                    }
                },
                enabled = uiState.aiEnabled && !parsing,
            ) {
                Text(text = "发送")
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // AI 状态区（本页只有两种形态）：识别中一行 / 失败一行。`AiState.Draft` 在本页
        // **不再产生** —— 结果走逐条弹窗；这里仍列出来只是为了穷尽这个密封类型。
        when (val aiState = uiState.aiState) {
            AiState.Hidden, AiState.Idle, is AiState.Draft -> Unit

            AiState.Parsing -> Text(
                text = stringResource(R.string.ai_entry_parsing),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth(),
            )

            is AiState.Failed -> Text(
                // 文案映射仍走共用组件里的那一个扩展函数（`AiFailReason.text()`），
                // 与录入页、拍照页逐字一致。
                text = aiState.reason.text(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 开关被关掉时（极端情形：进来之后在别处关了）明说，不让用户对着一个按不动的话筒猜。
        if (!uiState.aiEnabled) {
            Text(
                text = stringResource(R.string.voice_ai_disabled),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // 落库汇总（`V2-FR-11`「没点确认前库中零新增」的另一半：记了几件要说清）。
        // 队列走完 / 关闭之后才看得到它，所以两句都要有：「已记下」说清结果，
        // **「没记下」必须说** —— 弹窗直接消失会让用户以为全都记下了。
        // 语音通道「一件都没提位置」时也会落在这里（那一件仍会弹一次窗，用户可在窗里补位置）。
        val savedCount = queue.savedCount
        if (savedCount != null) {
            if (savedCount > 0) {
                Text(
                    text = stringResource(R.string.ai_home_saved, savedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
            if (queue.unsavedCount > 0) {
                Text(
                    text = stringResource(R.string.ai_home_unsaved, queue.unsavedCount),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }

        // 逐条确认弹窗：一次只呈现一件（`editing` 非空即显示）。
        // 挂载位置不影响观感：`AlertDialog` 渲染为**独立窗口**，在组合树里的位置不决定层级。
        val editing = queue.editing
        if (editing != null) {
            AiItemDialog(
                item = editing,
                number = queue.number,
                total = queue.total,
                categories = categories,
                // 位置是模型猜的 ⇒ 可改。树数据在这里下传，位置选择态就地在同一个窗口里铺开。
                locationEditable = true,
                locationRows = locationTree,
                onNameChanged = onItemNameChanged,
                onNoteChanged = onItemNoteChanged,
                onCategorySelected = onItemCategorySelected,
                onLocationSelected = onItemLocationSelected,
                onConfirm = onItemConfirm,
                onDiscard = onItemDiscard,
                onClose = onCloseIntake,
            )
        }
    }
}
