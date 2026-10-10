// ============================================================================================
// 【2026-10-10 停用 · 整体注释保留】本件属于「语音 AI 录入页录音直送」这条已回退的链路。
//
// 停用原因：DeepSeek 的 chat/completions 只受理 `text` / `image_url` / `file` 三种 content
// part —— `input_audio` 被 422 拒收（unknown variant），而 `file` 槽也只吃图片（400：格式白名单
// 为 webp / png / jpeg / gif）；官网能力表仅有 Vision，没有任何音频输入项（2026-10-10 实测两次）。
// 语音 AI 录入页已改回 `VoiceCapturePad`（说话变字、音频不出设备）。
// 口径与理由见 docs/code-map/05-改动须知.md 第 34 条。
//
// 恢复方式：删掉本段头注释，再把下面各行行首多出来的 `//`（及其后一个空格）去掉即可。
// ============================================================================================
// package com.dream.shouna.ui.component
//
// import android.Manifest
// import android.content.Context
// import android.content.pm.PackageManager
// import android.util.Base64
// import androidx.activity.compose.rememberLauncherForActivityResult
// import androidx.activity.result.contract.ActivityResultContracts
// import androidx.compose.foundation.background
// import androidx.compose.foundation.gestures.detectTapGestures
// import androidx.compose.foundation.layout.Arrangement
// import androidx.compose.foundation.layout.Box
// import androidx.compose.foundation.layout.Column
// import androidx.compose.foundation.layout.fillMaxWidth
// import androidx.compose.foundation.layout.size
// import androidx.compose.foundation.shape.CircleShape
// import androidx.compose.material3.MaterialTheme
// import androidx.compose.material3.Text
// import androidx.compose.material3.TextButton
// import androidx.compose.runtime.Composable
// import androidx.compose.runtime.DisposableEffect
// import androidx.compose.runtime.LaunchedEffect
// import androidx.compose.runtime.getValue
// import androidx.compose.runtime.mutableStateOf
// import androidx.compose.runtime.remember
// import androidx.compose.runtime.rememberCoroutineScope
// import androidx.compose.runtime.rememberUpdatedState
// import androidx.compose.runtime.setValue
// import androidx.compose.ui.Alignment
// import androidx.compose.ui.Modifier
// import androidx.compose.ui.draw.clip
// import androidx.compose.ui.input.pointer.pointerInput
// import androidx.compose.ui.platform.LocalContext
// import androidx.compose.ui.res.stringResource
// import androidx.compose.ui.text.style.TextAlign
// import androidx.compose.ui.unit.dp
// import androidx.core.content.ContextCompat
// import com.dream.shouna.R
// import com.dream.shouna.data.local.audio.AudioRecorder
// import com.dream.shouna.data.local.audio.RecordingSession
// import com.dream.shouna.di.AudioRecorderEntryPoint
// import dagger.hilt.android.EntryPointAccessors
// import kotlinx.coroutines.Dispatchers
// import kotlinx.coroutines.delay
// import kotlinx.coroutines.launch
// import kotlinx.coroutines.withContext
//
// /**
//  * 单次录音的**时长上限**。
//  *
//  * 【为什么必须有上限】音频是**整段上行**的：16 kHz 单声道 WAV ≈ 32 KB/s，base64 后 ≈ 43 KB/s。
//  * 没有上限时用户按着不放，请求体会一路涨到几 MB，而上行慢的网络上必然撞 `UrlConnectionAiHttp`
//  * 的 60 秒读超时（`READ_TIMEOUT_MS`）。30 秒 ≈ 1.3 MB base64，是「够说十来件东西」与
//  * 「上行不至于超时」之间的折中。
//  *
//  * 【到点即当「抬手」处理】上限不是「拒绝」，是**自动收尾**：到点走的是与抬手**同一个**
//  * [AudioCapturePad] 内的收尾路径，用户看到的是「录音结束、开始识别」，不需要再抬一次手。
//  */
// private const val MAX_RECORD_MILLIS = 30_000L
//
// /**
//  * **录音话筒**：长按录一段、松手把这段音频交出去（不管时长上限，见 [MAX_RECORD_MILLIS]）。
//  *
//  * 【与 `VoiceCapturePad` 的关系：形似而神不同】两者外观相同（提示行 + 长按圆钮），作用却是
//  * 两件事：`VoiceCapturePad` 的产物是**文字**（走 `data/local/asr/` 的识别引擎、音频不出设备），
//  * 本组件的产物是**一段 WAV 的裸 base64**（交给 AI 上行）。
//  *
//  * 【为什么另起一件而不是给 `VoiceCapturePad` 加模式开关】两个宿主要的东西不同（一个要字、
//  * 一个要音频），而**录入页那条 🎤 必须继续要字** —— 它把结果填进名称框，没有 AI 在中间。
//  * 2026-10-10 的裁决是「**新增一件、只换语音 AI 页**」：`VoiceCapturePad` 与 `data/local/asr/`
//  * 因此**一行不动**，仍然活着、仍在被录入页使用。代价是本文件里的圆钮（[AudioHoldPad]）与
//  * `VoiceCapturePad.kt` 的 `MicHoldPad` 是**同源的两份**：那份是 `private`，本轮口径要求
//  * `VoiceCapturePad` 一字不改，故不抽共用件。**将来若真到了「两个宿主都要音频」那天**，
//  * 合并它们的时机才成熟；现在合并会连带改到一条不该动的链路。
//  *
//  * 【手势的坑与那份完全相同】`pointerInput` 的 key 必须是常量 `Unit`：key 一变手势协程就被
//  * 取消重启，`onPress` 里的 `tryAwaitRelease()` 会**随取消一起断掉**，表现为「按下有反应、
//  * 松手停不下来」。完整因果见 `MicHoldPad` 的注释与 `docs/code-map/05-改动须知.md` §1-21。
//  *
//  * 【文案为什么硬编码在本件】用户 2026-10-10 口径：**现有 `strings.xml` 一个字符串都不碰**。
//  * 本件的五句提示是新增语义（录音），与现有那批（识别）并不同义，因此自带文案。
//  *
//  * ⚠️ **本件不得复用「识别在本机完成，不会上传你的声音」那句承诺** —— 录音是**要上行**的
//  * （这正是本组件存在的意义）。文案里只讲权限，不对上行与否作任何承诺。
//  *
//  * 无状态（除录音过程本身）：音频经 [onRecorded] 抛给调用方，调用方决定送去哪里 ——
//  * 与「交给哪个接口」解耦。
//  *
//  * 被调用方：`ui/screen/voiceai/VoiceAiIntakeScreen.kt`（语音 AI 录入页，页内直挂）
//  */
// @Composable
// fun AudioCapturePad(
//     /** 一段录好的 WAV 的**裸 base64**（不含 `data:` 前缀；前缀属报文格式，由网络层拼）。 */
//     onRecorded: (String) -> Unit,
//     modifier: Modifier = Modifier,
//     /** 调用方是否允许此刻开口（AI 总开关关掉时传 `false` —— 那一侧不允许发起任何请求）。 */
//     enabled: Boolean = true,
//     /** 录音内核。默认取应用级单例；显式传入是为测试 / 预览留的口子。 */
//     recorder: AudioRecorder = rememberAudioRecorder(),
// ) {
//     val context = LocalContext.current
//     val scope = rememberCoroutineScope()
//
//     // 首次组合就把状态定下来（不做副作用式赋值）：权限可能在上次使用时就已授予。
//     val phase = remember {
//         mutableStateOf(
//             if (hasRecordPermission(context)) AudioPhase.READY else AudioPhase.NEED_PERMISSION,
//         )
//     }
//
//     // 进行中的会话；null = 没有在录。
//     var session by remember { mutableStateOf<RecordingSession?>(null) }
//
//     // 「抬手」是否已经发生而会话还没建好 —— 录制的建会话阶段可能有几十毫秒，这个标记保证
//     // 那一次不会「松了手却还在录」。
//     var released by remember { mutableStateOf(false) }
//
//     val onRecordedLatest = rememberUpdatedState(onRecorded)
//
//     val permissionLauncher = rememberLauncherForActivityResult(
//         contract = ActivityResultContracts.RequestPermission(),
//     ) { granted ->
//         phase.value = if (granted) AudioPhase.READY else AudioPhase.NEED_PERMISSION
//     }
//
//     /**
//      * 收尾：停止收音 → 取 WAV 字节 → base64 → 交付。
//      *
//      * 【抬手与到点共用它】两条路径都调这里，**幂等由会话自己保证**（`RecordingSession.stop`
//      * 先到先得，第二次返 null 但不影响第一次的结果）。
//      */
//     fun finish() {
//         released = true
//         val current = session ?: return // 会话还没建好：start 那条协程发现 released 后自行收摊
//         session = null
//         phase.value = AudioPhase.ENCODING
//         scope.launch {
//             // stop() 内部要 join 读取线程（会阻塞）⇒ 放 IO。base64 也在这一跳里做完。
//             val wav = withContext(Dispatchers.IO) { current.stop() }
//             val base64 = wav?.let { Base64.encodeToString(it, Base64.NO_WRAP) }
//             if (base64.isNullOrEmpty()) {
//                 phase.value = AudioPhase.FAILED
//             } else {
//                 // 先回位再交付：本件是**页内宿主**，不回位就永远停在 ENCODING、按不动第二次。
//                 phase.value = AudioPhase.READY
//                 onRecordedLatest.value(base64)
//             }
//         }
//     }
//
//     /** 开录。会话起不来（被占用 / 设备不支持）与「一个字节都没录到」是同一处置：一行提示、重试。 */
//     fun start() {
//         released = false
//         phase.value = AudioPhase.RECORDING
//         scope.launch {
//             val started = recorder.start()
//             if (started == null) {
//                 phase.value = AudioPhase.FAILED
//                 return@launch
//             }
//             if (released) {
//                 // 建会话期间就抬手了：把这一次收掉，不留一个还在收音的会话（并回到可按状态）。
//                 withContext(Dispatchers.IO) { started.cancel() }
//                 phase.value = AudioPhase.READY
//                 return@launch
//             }
//             session = started
//         }
//     }
//
//     // 离开组合就把在途会话收掉：否则麦克风会一直被占（引擎是应用级单例，不会随本组件消失）。
//     DisposableEffect(Unit) {
//         onDispose {
//             session?.cancel()
//             session = null
//         }
//     }
//
//     // 录音中 / 收尾中不接受新的长按：一次只录一段，避免两段音频互相覆盖。
//     val canHold = enabled &&
//         phase.value != AudioPhase.RECORDING &&
//         phase.value != AudioPhase.ENCODING
//
//     // 【时长上限的看门狗】到点自动收尾（见 MAX_RECORD_MILLIS）。key 用布尔而不是 phase 本身：
//     // 只关心「进入 / 离开录音态」这一件事，收尾态的变化不该重启计时。
//     val isRecording = phase.value == AudioPhase.RECORDING
//     LaunchedEffect(isRecording) {
//         if (!isRecording) return@LaunchedEffect
//         delay(MAX_RECORD_MILLIS)
//         if (phase.value == AudioPhase.RECORDING) finish()
//     }
//
//     Column(
//         horizontalAlignment = Alignment.CenterHorizontally,
//         verticalArrangement = Arrangement.spacedBy(12.dp),
//         modifier = modifier.fillMaxWidth(),
//     ) {
//         Text(
//             text = phase.value.hint,
//             style = MaterialTheme.typography.bodyMedium,
//             textAlign = TextAlign.Center,
//             modifier = Modifier.fillMaxWidth(),
//         )
//
//         if (phase.value.showsMic) {
//             AudioHoldPad(
//                 listening = isRecording,
//                 enabled = canHold,
//                 onStart = { start() },
//                 onStop = { finish() },
//             )
//         } else {
//             // 权限按钮复用既有字符串（不改 `strings.xml`，只是引用它）。
//             TextButton(
//                 onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
//             ) {
//                 Text(text = stringResource(R.string.voice_grant_permission))
//             }
//         }
//     }
// }
//
// /**
//  * 取应用级单例录音器（`di/AudioModule.kt` 的 [AudioRecorderEntryPoint]）。
//  * 理由与 `rememberAsrEngine` 逐字相同：**取依赖留在唯一消费它的组件里**，不给 VM 添死字段。
//  */
// @Composable
// private fun rememberAudioRecorder(): AudioRecorder {
//     val context = LocalContext.current
//     return remember(context) {
//         EntryPointAccessors
//             .fromApplication(context.applicationContext, AudioRecorderEntryPoint::class.java)
//             .audioRecorder()
//     }
// }
//
// /**
//  * 「按住录音」的圆钮。**独立成 composable** 是为了把 [AudioPhase] 的读取限制在这一层：
//  * 录音中界面会不断重建提示行，圆钮不该被那些刷新牵连（重组作用域最小化）。
//  *
//  * 手势实现与 `VoiceCapturePad.kt` 的 `MicHoldPad` **逐行同源**（那份是 `private`，本轮不得改动
//  * 其所在文件），坑与理由见 [AudioCapturePad] 的类注释。
//  */
// @Composable
// private fun AudioHoldPad(
//     listening: Boolean,
//     enabled: Boolean,
//     onStart: () -> Unit,
//     onStop: () -> Unit,
// ) {
//     // 「这次按下究竟录了没有」。必须在长按真的触发后才置真 —— 否则一次普通点击也会在抬手时
//     // 调 finish()，把状态推进到「正在处理」再落一个「录音失败」，用户点一下话筒就无端收到错误。
//     // 用 `mutableStateOf` 只是因为它是现成的单元格；组合期**不读**它，故不引发重组。
//     val recording = remember { mutableStateOf(false) }
//
//     // 【key 必须是常量 `Unit`】见 [AudioCapturePad] 类注释：key 一变化，`onPress` 里的
//     // `tryAwaitRelease()` 会随协程取消一起断掉，抬手事件再也到不了 `onStop()`。
//     // 修法与 `MicHoldPad` 同：key 固定，`enabled` 与两个回调改用 `rememberUpdatedState` 取最新值，
//     // 把「录音中不接受新的长按」这条门控从 key **挪进 `onLongPress` 体内**。
//     val latestEnabled = rememberUpdatedState(enabled)
//     val latestOnStart = rememberUpdatedState(onStart)
//     val latestOnStop = rememberUpdatedState(onStop)
//
//     Box(
//         contentAlignment = Alignment.Center,
//         modifier = Modifier
//             .size(96.dp)
//             .clip(CircleShape)
//             .background(
//                 if (listening) {
//                     MaterialTheme.colorScheme.primary
//                 } else {
//                     MaterialTheme.colorScheme.surfaceVariant
//                 },
//             )
//             .pointerInput(Unit) {
//                 detectTapGestures(
//                     onLongPress = {
//                         if (latestEnabled.value) {
//                             recording.value = true
//                             latestOnStart.value()
//                         }
//                     },
//                     onPress = {
//                         tryAwaitRelease()
//                         if (recording.value) {
//                             recording.value = false
//                             latestOnStop.value()
//                         }
//                     },
//                 )
//             },
//     ) {
//         Text(text = "🎤", style = MaterialTheme.typography.headlineMedium)
//     }
// }
//
// /**
//  * 可见的五个状态。
//  *
//  * 【文案为什么留在组件层】它是**纯 UI 提示**（「长按开始录音」），不属于任何业务口径，
//  * 与 `AiFailReason` 那类需要按场景映射的失败原因不同（那一类由页面持资源）。
//  * 语言本次锁定中文，且**不进 `strings.xml`**（用户 2026-10-10 口径：现有字符串一个不碰）。
//  *
//  * 【为什么只有 NEED_PERMISSION 不画面话筒】与 `VoicePhase` 同一条理由：
//  * 于是**不可能**出现「没权限却发起录音」的路径。
//  */
// private enum class AudioPhase(val hint: String, val showsMic: Boolean) {
//     NEED_PERMISSION("录音需要「录音」权限", false),
//     READY("长按下方话筒开始录音，松手结束", true),
//     RECORDING("正在录音…（最长 ${MAX_RECORD_MILLIS / 1000} 秒）松手结束", true),
//     ENCODING("正在处理录音…", true),
//     FAILED("录音失败，长按再试一次", true),
// }
//
// /** 一个地方的权限自查，避免在组合里散开写 `checkSelfPermission(...) == PERMISSION_GRANTED`。 */
// private fun hasRecordPermission(context: Context): Boolean =
//     ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
//         PackageManager.PERMISSION_GRANTED
