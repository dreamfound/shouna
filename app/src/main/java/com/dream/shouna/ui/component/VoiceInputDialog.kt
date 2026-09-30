package com.dream.shouna.ui.component

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import java.util.Locale
import kotlinx.coroutines.delay

/**
 * 「识别中」的看门狗时长。
 *
 * 抬手之后系统通常 1–2 秒回调 `onResults`；慢一点的识别到 5 秒也还算正常，故护栏取 8 秒 ——
 * 只有**系统彻底不回**（个别 ROM 的识别服务会这样）才会触发。取值取向是「宁可多等，也不误伤慢识别」：
 * 看门狗本身只解 UI 的锁、不打断识别（见 [VoiceInputDialog] 内注释），多等几秒的代价仅在真卡死时兑现。
 */
private const val PROCESSING_TIMEOUT_MILLIS = 8_000L

/**
 * 语音输入弹窗（FR-18 的**简化形态**）：长按话筒说话、松手出文字，识别用系统语音服务。
 *
 * 【与 V1 既有承诺的关系】本弹窗是 V1 里**唯一**会触碰敏感权限的功能：
 * - 需要 `RECORD_AUDIO`（运行时申请一次；拒绝后仍可打字，弹窗只提示不给话筒）；
 * - **不需要 `INTERNET`** —— 识别跑在系统识别服务进程里，本应用自身不联网，NFR-01 / 02 有效。
 *
 * 【为什么只调系统识别、不自己录 PCM】自建录音 + 本地 ASR 要引第三方模型（体积、依赖、
 * 效果都不可控），而系统识别在多数机型上「能用」的概率最高；`EXTRA_PREFER_OFFLINE` 让
 * 装了本地语音包的设备走本地、避开出机（`prd/14` §14.4 的「本地优先」）。
 *
 * 【手势】长按开始、抬手结束 —— 由 [detectTapGestures] 的 `onLongPress` + `onPress` 组合实现：
 * `onPress` 的 lambda 生命周期天然覆盖「按下 → 抬手」，抬手落点不需要自己写事件循环。
 * **该手势协程的 `pointerInput` key 必须是常量** —— 详见 [MicHoldPad] 的注释：key 一变化
 * 协程就被取消重启，抬手那一段会断，表现为「松手停不下来」。
 *
 * 【三个必须知道的行为事实】
 * 1. `stopListening()` 是「停止收音、**保留**已识别内容」，`cancel()` 才会丢弃结果 ——
 *    抬手要出字，只能用前者。
 * 2. 抬手之后系统还要 1–2 秒才回调 `onResults`，所以 [VoicePhase.PROCESSING] 是正常状态，不是卡死。
 * 3. 但**「正常」不能当保证用**：个别 ROM 的识别服务两个回调都不给，该态下话筒又按不动，
 *    页面会永久卡住 —— 故本弹窗给它配了看门狗（`PROCESSING_TIMEOUT_MILLIS`，只解 UI 的锁、
 *    不取消识别）。改动这一带时别把看门狗当成多余代码删掉。
 *
 * 被调用方：`ui/screen/add/QuickAddScreen.kt` 的 `QuickAddVoiceDialog`。本弹窗与「填哪个字段」
 * **解耦**：结果通过 [onResult] 抛给调用方，由调用方决定写进名称还是备注。
 */
@Composable
fun VoiceInputDialog(
    onResult: (String) -> Unit,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current

    // 识别服务是否可用：极少数机型 / 精简系统没有识别服务。此时**不给**长按区、直接说明原因
    // —— 让用户对着一个按不动的按钮猜是最差的处理。
    val serviceAvailable = remember { SpeechRecognizer.isRecognitionAvailable(context) }

    // 首次组合就把状态定下来（不做副作用式赋值）：权限可能在上次使用时就已授予。
    val phase = remember {
        mutableStateOf(
            when {
                !serviceAvailable -> VoicePhase.UNAVAILABLE
                hasRecordPermission(context) -> VoicePhase.READY
                else -> VoicePhase.NEED_PERMISSION
            },
        )
    }
    val partial = remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        phase.value = if (granted) VoicePhase.READY else VoicePhase.NEED_PERMISSION
    }

    val listener = remember {
        object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                phase.value = VoicePhase.LISTENING
            }

            override fun onBeginningOfSpeech() = Unit

            override fun onRmsChanged(rmsdB: Float) = Unit

            override fun onBufferReceived(buffer: ByteArray?) = Unit

            override fun onEndOfSpeech() {
                // 系统判定「说完了」（静音超时）也会走到这里 —— 手指还没抬，也先进入识别中。
                phase.value = VoicePhase.PROCESSING
            }

            override fun onError(error: Int) {
                // 抬手太快 / 环境太吵 / 无有效语音都落在这里。弹窗保持打开让用户重试，
                // 不弹第二个对话框（`实现约束.md` §4-1 零弹窗）。
                phase.value = VoicePhase.FAILED
            }

            override fun onResults(results: Bundle?) {
                val text = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
                    .trim()
                if (text.isEmpty()) {
                    phase.value = VoicePhase.FAILED
                } else {
                    onResult(text)
                }
            }

            override fun onPartialResults(partialResults: Bundle?) {
                // 实时上屏：让用户看得见「它在听什么」，说错了能立刻松手重来。
                partial.value = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty()
            }

            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        }
    }

    // 识别器只在「服务可用 + 权限已授予」之后创建；构造与销毁都必须在主线程，
    // 而组合与 DisposableEffect 回调本来就在主线程，正合适。
    val recognizerNeeded = phase.value.needsRecognizer
    val recognizer: SpeechRecognizer? = remember(serviceAvailable, recognizerNeeded) {
        if (serviceAvailable && recognizerNeeded) {
            SpeechRecognizer.createSpeechRecognizer(context)
        } else {
            null
        }
    }

    DisposableEffect(recognizer) {
        recognizer?.setRecognitionListener(listener)
        onDispose { recognizer?.destroy() }
    }

    val intent = remember {
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toLanguageTag())
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            // 有本地语音包就走本地，没有才回落系统服务（prd/14 §14.4 的「本地优先」）。
            putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
    }

    // 录音中 / 识别中不接受新的长按：一次只说一句，避免两次识别结果互相覆盖。
    val canHold = recognizer != null &&
        phase.value != VoicePhase.LISTENING &&
        phase.value != VoicePhase.PROCESSING

    // 【识别中的看门狗 —— 「系统永不回调」的唯一出路】
    //
    // 抬手后系统**本该**在 1–2 秒内回调 `onResults` / `onError`；但个别 ROM 的识别服务两个都不回，
    // 页面就永久停在「正在识别…」。而该态下 `canHold` 为 false ⇒ 话筒按不动、无法重试，
    // 用户只剩「关闭」一条路（数据不受影响，但这个功能等于废了）。
    //
    // 【计时为什么不单调累加】`partial.value` 也进 key ⇒ 每来一次实时结果就重新计时。
    // 「还在往外吐字」说明识别确实在推进，不该被超时打断；计时只在**彻底没动静**时累加。
    //
    // 【看门狗不取消识别】它只把 UI 解锁，**不调 `cancel()`** —— 万一超时之后 `onResults`
    // 才姗姗来迟，文字照样经 [onResult] 交出去。「迟到的结果」也比「丢掉的结果」好。
    //
    // 【为什么落 [VoicePhase.FAILED] 而不是回 READY】卡死这件事要**留下痕迹**：回 READY 等于让
    // 它看起来像「从未发生」，用户抬手后走开再回来，会以为自己没按过。给「长按再试一次」才是可解释的。
    val isProcessing = phase.value == VoicePhase.PROCESSING
    LaunchedEffect(isProcessing, partial.value) {
        if (!isProcessing) return@LaunchedEffect
        delay(PROCESSING_TIMEOUT_MILLIS)
        if (phase.value == VoicePhase.PROCESSING) phase.value = VoicePhase.FAILED
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = modifier,
        title = { Text(text = "语音输入") },
        text = {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(
                    text = phase.value.hint,
                    style = MaterialTheme.typography.bodyMedium,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (phase.value.needsRecognizer) {
                    MicHoldPad(
                        listening = phase.value == VoicePhase.LISTENING,
                        enabled = canHold,
                        onStart = {
                            partial.value = ""
                            phase.value = VoicePhase.LISTENING
                            recognizer?.startListening(intent)
                        },
                        onStop = {
                            // stopListening（不是 cancel）：停止收音但保留已识别内容。
                            recognizer?.stopListening()
                            phase.value = VoicePhase.PROCESSING
                        },
                    )

                    if (partial.value.isNotEmpty()) {
                        Text(
                            text = partial.value,
                            style = MaterialTheme.typography.bodyLarge,
                            textAlign = TextAlign.Center,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
        },
        confirmButton = {
            if (phase.value == VoicePhase.NEED_PERMISSION) {
                TextButton(
                    onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
                ) {
                    Text(text = "授予录音权限")
                }
            }
            TextButton(onClick = onDismiss) {
                Text(text = "关闭")
            }
        },
    )
}

/**
 * 「按住说话」的话筒圆钮。**独立成 composable** 是为了把 `phase` 的读取限制在这一层：
 * 弹窗在录音中会不断刷新 partial 文本，圆钮不该被那些刷新牵连（重组作用域最小化）。
 */
@Composable
private fun MicHoldPad(
    listening: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    // 「这次按下究竟录了没有」的标记。**必须在长按真的触发后才置真** —— 否则一次普通点击
    // 也会在抬手时调 stopListening()，把状态推进到「正在识别…」再落一个「没听清」，
    // 用户点一下话筒就无端收到一个错误提示。
    // 用 `mutableStateOf` 只是因为它是现成的单元格；组合期**不读**它，故不引发重组。
    val recording = remember { mutableStateOf(false) }

    // 三个「最新值」引用（而不是把参数直接捕进 lambda）。
    //
    // 【为什么 key 必须是常量 `Unit` —— 本件最容易踩的坑】`pointerInput` 的 key 一旦变化，
    // 手势协程会被**取消并重启**。这里曾写成 `pointerInput(enabled)`：长按触发 → 上层
    // `onStart()` 把 `phase` 置为 LISTENING → `canHold` 翻 `false` → key 变化 → 整个
    // `detectTapGestures` 连同 `onPress` 里的 `tryAwaitRelease()` 一起被取消 ⇒
    // **抬手事件再也到不了 `onStop()`，`stopListening()` 从不被调用**，弹窗卡在「正在聆听…」，
    // 只能等系统静音超时自己推进（环境音持续时更久）。
    // 修法：key 固定，`enabled` 与两个回调改用 `rememberUpdatedState` 取最新值，把
    // 「录音中不接受新的长按」这条门控从 key **挪进 `onLongPress` 体内**。
    // 这样手势协程跨重组存活 —— 「按下开始 → 抬手结束」是一条不会被截断的线。
    val latestEnabled = rememberUpdatedState(enabled)
    val latestOnStart = rememberUpdatedState(onStart)
    val latestOnStop = rememberUpdatedState(onStop)

    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .size(96.dp)
            .clip(CircleShape)
            .background(
                if (listening) {
                    MaterialTheme.colorScheme.primary
                } else {
                    MaterialTheme.colorScheme.surfaceVariant
                },
            )
            .pointerInput(Unit) {
                detectTapGestures(
                    // 长按（系统长按时长）才开录 —— 短按不触发，避免误触。
                    onLongPress = {
                        if (latestEnabled.value) {
                            recording.value = true
                            latestOnStart.value()
                        }
                    },
                    // 这个 lambda 的生命周期正好覆盖「按下 → 抬手」，抬手即停。
                    onPress = {
                        tryAwaitRelease()
                        if (recording.value) {
                            recording.value = false
                            latestOnStop.value()
                        }
                    },
                )
            },
    ) {
        Text(text = "🎤", style = MaterialTheme.typography.headlineMedium)
    }
}

/**
 * 弹窗的六个可见状态。
 *
 * [needsRecognizer] 决定「是否创建识别器 / 是否显示话筒」：无权限与不可用两态都不创建，
 * 于是**不可能**出现「没权限却调了 startListening」的路径。
 */
private enum class VoicePhase(val hint: String, val needsRecognizer: Boolean) {
    NEED_PERMISSION("语音输入需要「录音」权限；识别在本机完成，不会上传你的声音", false),
    UNAVAILABLE("这台设备没有可用的语音识别服务，请用键盘输入", false),
    READY("长按下方话筒开始说话，松手结束", true),
    LISTENING("正在聆听…松手结束", true),
    PROCESSING("正在识别…", true),
    FAILED("没听清，长按再试一次", true),
}

/** 一个地方的权限自查，避免在组合里散开写 `checkSelfPermission(...) == PERMISSION_GRANTED`。 */
private fun hasRecordPermission(context: android.content.Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED
