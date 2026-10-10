package com.dream.shouna.ui.component.ai

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.dream.shouna.R
import com.dream.shouna.data.local.asr.AsrEngine
import com.dream.shouna.data.local.asr.AsrSession
import com.dream.shouna.di.AsrEngineEntryPoint
import dagger.hilt.android.EntryPointAccessors
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 「识别中」的看门狗时长。
 *
 * 抬手之后识别通常 1–2 秒回调；慢一点的到 5 秒也还算正常，故护栏取 8 秒 ——
 * 只有**彻底不回**（个别 ROM 的识别服务会这样）才会触发。取值取向是「宁可多等，也不误伤慢识别」：
 * 看门狗本身只解 UI 的锁、不打断识别（见 [VoiceCapturePad] 内注释），多等几秒的代价仅在真卡死时兑现。
 */
private const val PROCESSING_TIMEOUT_MILLIS = 8_000L

/**
 * **语音识别内核**：长按话筒说话、松手出文字。
 *
 * 【它不含任何窗口】本组件只画「说明 + 话筒 + 实时上屏（+ 权限按钮）」；识别引擎不可用时
 * 额外画一段**诊断快照**（见 [recognizerDiagnostics]）—— 那一态本来就是死路，摊开判据比留一句话强。
 * 两个宿主：
 * - 录入页的 `VoiceInputDialog`（`AlertDialog` 里包一层壳，行为与 V1 逐字一致）；
 * - 语音 AI 录入页（P-VOICE-AI）**页内直接用** —— 那一页整块就是这个话筒，不该再套一层弹窗。
 *
 * 【识别内核已解耦（2026-10-10）】本组件**不再直接使用** `SpeechRecognizer`：它只认
 * [AsrEngine] 这个接口，由 [engine] 参数注入（默认从 Hilt 取应用级单例）。于是
 * 「系统识别」→「内置离线」的切换发生在这层之外，本文件对那次改动**零感知**。
 * 接口与三个实现见 `data/local/asr/`，绑定见 `di/AsrModule.kt`。
 *
 * 【与 V1 既有承诺的关系】这是全工程**唯一**会触碰敏感权限的功能：
 * - 需要 `RECORD_AUDIO`（运行时申请一次；拒绝后界面给出「授予录音权限」按钮，不给话筒）；
 * - **不需要 `INTERNET`** —— 无论走哪个引擎，识别都在**本机**完成：系统通道跑在系统识别服务
 *   进程里，内置通道跑在本进程内（模型随包发布）。NFR-01 / 02 有效，`实现约束.md` §7-1 / §7-3 未被改变。
 *
 * 【手势】长按开始、抬手结束 —— 由 [detectTapGestures] 的 `onLongPress` + `onPress` 组合实现：
 * `onPress` 的 lambda 生命周期天然覆盖「按下 → 抬手」，抬手落点不需要自己写事件循环。
 * **该手势协程的 `pointerInput` key 必须是常量** —— 详见 [MicHoldPad] 的注释：key 一变化
 * 协程就被取消重启，抬手那一段会断，表现为「松手停不下来」。
 *
 * 【三个必须知道的行为事实】
 * 1. 会话的 `stop()` 是「停止收音、**保留**已识别内容」，`cancel()` 才会丢弃结果 ——
 *    抬手要出字，只能用前者。
 * 2. 抬手之后还要 1–2 秒才出结果，所以 [VoicePhase.PROCESSING] 是正常状态，不是卡死。
 * 3. 但**「正常」不能当保证用**：个别 ROM 的两个回调都不给，该态下话筒又按不动，
 *    界面会永久卡住 —— 故本组件给它配了看门狗（`PROCESSING_TIMEOUT_MILLIS`，只解 UI 的锁、
 *    不取消识别）。改动这一带时别把看门狗当成多余代码删掉。
 *
 * 【出结果后必须回到 READY（页内宿主的前提）】交付一次结果就把相位置回 [VoicePhase.READY]。
 * 弹窗形态下这一步看不出差别（回调一来宿主就关窗），但**页内形态下它是必需的** ——
 * 不回位就永远停在 `PROCESSING`、`canHold` 恒为 false，用户连第二句都说不出来。
 * 于是「一次长按只出一句」的不变量仍然成立，而「说完还能再说」也成立。
 *
 * 无状态（除识别过程本身）：结果通过 [onResult] 抛给调用方，调用方决定写进哪个字段 / 送到哪里 ——
 * 本组件与「填哪个字段」**解耦**。
 *
 * 被调用方：`ui/component/ai/VoiceInputDialog.kt`（录入页）、`ui/screen/voiceai/VoiceAiIntakeScreen.kt`
 */
@Composable
fun VoiceCapturePad(
    onResult: (String) -> Unit,
    modifier: Modifier = Modifier,
    /** 调用方是否允许此刻开口（语音 AI 录入页在 AI 总开关关掉时传 `false`）。 */
    enabled: Boolean = true,
    /** 识别引擎。默认取应用级单例；显式传入是为测试 / 预览留的口子。 */
    engine: AsrEngine = rememberAsrEngine(),
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    // 识别引擎是否可用。双通道下它恒为 true（内置引擎随包发布），保留这个判断是为了
    // 「将来两个引擎都不可用」这一天：那时该显示原因，而不是让用户对着按不动的话筒猜。
    //
    // [probeKey] 是「重新检测」的触发计数 —— 「先看到这句话 → 去装上识别服务 → 回来再看」
    // 是条很现实的路径，故留一个手动重探的入口（重探会连带把 [phase] 一起归位）。
    val probeKey = remember { mutableIntStateOf(0) }
    val engineAvailable = remember(probeKey.intValue, engine) { engine.isAvailable() }

    // 首次组合就把状态定下来（不做副作用式赋值）：权限可能在上次使用时就已授予。
    val phase = remember(probeKey.intValue, engineAvailable) {
        mutableStateOf(
            when {
                !engineAvailable -> VoicePhase.UNAVAILABLE
                hasRecordPermission(context) -> VoicePhase.READY
                else -> VoicePhase.NEED_PERMISSION
            },
        )
    }
    val partial = remember { mutableStateOf("") }

    // 进行中的会话；null = 没有在录。
    var session by remember { mutableStateOf<AsrSession?>(null) }

    // 「抬手」是否已经发生而会话还没建好 —— 内置引擎首次要装载模型（可能比一次长按还久），
    // 这个标记保证那一次不会「松了手却还在录」。
    var released by remember { mutableStateOf(false) }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission(),
    ) { granted ->
        phase.value = if (granted) VoicePhase.READY else VoicePhase.NEED_PERMISSION
    }

    // 离开组合就把在途会话收掉：否则会话会一直收音（引擎是应用级单例，不会随本组件消失）。
    DisposableEffect(Unit) {
        onDispose {
            session?.cancel()
            session = null
        }
    }

    // 录音中 / 识别中不接受新的长按：一次只说一句，避免两次识别结果互相覆盖。
    // [enabled] 由调用方控制（AI 总开关关掉时必须禁掉 —— 那一侧不允许发起任何请求）。
    val canHold = enabled &&
        engineAvailable &&
        phase.value != VoicePhase.LISTENING &&
        phase.value != VoicePhase.PROCESSING

    // 【识别中的看门狗 —— 「永不回调」的唯一出路】
    //
    // 抬手后**本该**在 1–2 秒内出结果；但个别 ROM 的识别服务两个回调都不回，界面就永久停在
    // 「正在识别…」。而该态下 `canHold` 为 false ⇒ 话筒按不动、无法重试。
    //
    // 【计时为什么不单调累加】`partial.value` 也进 key ⇒ 每来一次实时结果就重新计时。
    // 「还在往外吐字」说明识别确实在推进，不该被超时打断；计时只在**彻底没动静**时累加。
    //
    // 【看门狗不取消识别】它只把 UI 解锁，**不调 `cancel()`** —— 万一超时之后结果才姗姗来迟，
    // 文字照样经 [onResult] 交出去。「迟到的结果」也比「丢掉的结果」好。
    //
    // 【为什么落 [VoicePhase.FAILED] 而不是回 READY】卡死这件事要**留下痕迹**：回 READY 等于让
    // 它看起来像「从未发生」，用户抬手后走开再回来，会以为自己没按过。给「长按再试一次」才是可解释的。
    val isProcessing = phase.value == VoicePhase.PROCESSING
    LaunchedEffect(isProcessing, partial.value) {
        if (!isProcessing) return@LaunchedEffect
        delay(PROCESSING_TIMEOUT_MILLIS)
        if (phase.value == VoicePhase.PROCESSING) phase.value = VoicePhase.FAILED
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth(),
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
                    released = false
                    phase.value = VoicePhase.LISTENING
                    scope.launch {
                        val started = engine.start(
                            onPartial = { partial.value = it },
                            onResult = { text ->
                                // 先回位再交付（顺序不重要，但要保证两件都发生）：见类注释
                                // 「出结果后必须回到 READY」。
                                partial.value = ""
                                session = null
                                phase.value = VoicePhase.READY
                                onResult(text)
                            },
                            onError = {
                                partial.value = ""
                                session = null
                                phase.value = VoicePhase.FAILED
                            },
                        )
                        if (started == null) {
                            // 引擎起不来（装载失败 / 上一个会话没收摊）：与「没听清」同一处置 ——
                            // 就一行提示、不弹窗、不阻塞（`实现约束.md` §4-1）。
                            phase.value = VoicePhase.FAILED
                        } else {
                            session = started
                            // 建会话期间就抬手了：补一次 stop，否则这一次会一直录下去。
                            if (released) started.stop()
                        }
                    }
                },
                onStop = {
                    released = true
                    phase.value = VoicePhase.PROCESSING
                    // stopListening（不是 cancel）：停止收音但保留已识别内容。
                    session?.stop()
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
        } else if (phase.value == VoicePhase.NEED_PERMISSION) {
            // 权限按钮从弹窗底栏挪进本组件：页内宿主没有「底栏」可放（行为等价，位置变了）。
            TextButton(
                onClick = { permissionLauncher.launch(Manifest.permission.RECORD_AUDIO) },
            ) {
                Text(text = stringResource(R.string.voice_grant_permission))
            }
        } else if (phase.value == VoicePhase.UNAVAILABLE) {
            // 【诊断快照 —— 临时件】「没有可用的语音识别服务」这句话本身不可行动：它同时对应
            // ①清单没声明（本工程 2026-10-10 已证实并修复）、②设备上真没有识别服务、
            // ③手上装的根本不是这个构建。三者修法完全不同，而远程无法区分 ⇒ 把判据摊开。
            // TODO(临时诊断：定案后连同本段一起移除；口径与判据见 recognizerDiagnostics 的 KDoc)
            Text(
                text = remember(probeKey.intValue) { recognizerDiagnostics(context) },
                style = MaterialTheme.typography.bodySmall,
                textAlign = TextAlign.Start,
                modifier = Modifier.fillMaxWidth(),
            )
            TextButton(onClick = { probeKey.intValue++ }) {
                Text(text = "重新检测")
            }
        }
    }
}

/**
 * 取应用级单例识别引擎（`di/AsrModule.kt` 的 [AsrEngineEntryPoint]）。
 *
 * 【为什么在这里取、而不是让宿主传进来】本工程的口径是「Android 侧的东西留在 UI 层」：VM 不持
 * `Context`、文件 IO 全在 Compose 层。引擎的实现持有 `Context` 并操作 `AudioRecord` ⇒ 若为了
 * 传给本组件而先注入 VM，只会给 VM 添一个从不调用的死字段。取依赖留在唯一消费它的组件里，
 * 与「Route 用 `hiltViewModel()` 取 VM」是同一性质的动作。
 */
@Composable
private fun rememberAsrEngine(): AsrEngine {
    val context = LocalContext.current
    return remember(context) {
        EntryPointAccessors
            .fromApplication(context.applicationContext, AsrEngineEntryPoint::class.java)
            .asrEngine()
    }
}

/**
 * 「按住说话」的话筒圆钮。**独立成 composable** 是为了把 `phase` 的读取限制在这一层：
 * 识别中界面会不断刷新 partial 文本，圆钮不该被那些刷新牵连（重组作用域最小化）。
 */
@Composable
private fun MicHoldPad(
    listening: Boolean,
    enabled: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
) {
    // 「这次按下究竟录了没有」的标记。**必须在长按真的触发后才置真** —— 否则一次普通点击
    // 也会在抬手时调 stop()，把状态推进到「正在识别…」再落一个「没听清」，
    // 用户点一下话筒就无端收到一个错误提示。
    // 用 `mutableStateOf` 只是因为它是现成的单元格；组合期**不读**它，故不引发重组。
    val recording = remember { mutableStateOf(false) }

    // 三个「最新值」引用（而不是把参数直接捕进 lambda）。
    //
    // 【为什么 key 必须是常量 `Unit` —— 本件最容易踩的坑】`pointerInput` 的 key 一旦变化，
    // 手势协程会被**取消并重启**。这里曾写成 `pointerInput(enabled)`：长按触发 → 上层
    // `onStart()` 把 `phase` 置为 LISTENING → `canHold` 翻 `false` → key 变化 → 整个
    // `detectTapGestures` 连同 `onPress` 里的 `tryAwaitRelease()` 一起被取消 ⇒
    // **抬手事件再也到不了 `onStop()`，`stop()` 从不被调用**，界面卡在「正在聆听…」，
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
 * 可见的六个状态。
 *
 * 【文案为什么留在组件层】它是**纯 UI 提示**（「长按说话」「正在聆听…」），不属于任何业务口径，
 * 与 `AiFailReason` 那类需要按场景映射的失败原因不同（那一类由页面持资源）。语言本次锁定中文。
 *
 * [needsRecognizer] 决定「是否显示话筒」：无权限与不可用两态都不显示，
 * 于是**不可能**出现「没权限却发起识别」的路径。
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
private fun hasRecordPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
        PackageManager.PERMISSION_GRANTED

/**
 * **语音识别环境的一次性诊断快照**（临时件，只画在 `UNAVAILABLE` 态下）。
 *
 * 【为什么需要它】「这台设备没有可用的语音识别服务」是一句**无法据以行动**的话 —— 它至少同时对应：
 * 1. 清单没声明包可见性（本工程 2026-10-10 已证实并修复，见 `AndroidManifest.xml` 的 `<queries>`）；
 * 2. 设备上确实没有任何 `RecognitionService`（国行 ROM 常见 —— realme RMX8899 实测 0 条）；
 * 3. 手上装的根本不是这个构建（`versionCode` 恒为 `1`、`versionName` 恒为 `1.0.0`，版本号区分不了）。
 * 三者的修法完全不同，而远程无法区分 ⇒ 把判据直接摊在屏幕上，一次装机即可定案。
 *
 * 【2026-10-10 起它更少见了】内置离线引擎接入后，[AsrEngine.isAvailable] 基本恒为 true
 * （模型随包发布）⇒ 本段只在「两个引擎都起不来」时才出现。保留它是因为那时更缺信息。
 *
 * 【各行的判据】
 * - `app <versionName> @ <最后更新时间>` —— **安装时间戳才是**「装的是不是刚才那个包」的判据。
 * - `设备 <厂商> <型号> / API <n>` —— ROM 差异的第一手信息。
 * - `isRecognitionAvailable = …` —— 「系统通道」能否用的唯一判据（与 `SystemAsrEngine` 同源）。
 * - `识别服务 N 个 […]` —— `queryIntentServices` 的**实测**结果。这一行同时验证清单声明：
 *   若设备上确实装了识别服务而这里仍是 0，那就是包可见性还没放开。
 * - `语音输入界面 <包名|none>` —— `ACTION_RECOGNIZE_SPEECH` 落到哪个 activity。
 *   这是「回落系统语音输入界面」这条备选路线**是否可行**的唯一判据。
 */
@Suppress("DEPRECATION")
private fun recognizerDiagnostics(context: Context): String {
    val pm = context.packageManager

    val services = pm.queryIntentServices(
        Intent(android.speech.RecognitionService.SERVICE_INTERFACE),
        0,
    ).orEmpty()
    val serviceNames = services.take(3).joinToString(" ") { it.serviceInfo.packageName }

    val voiceInput = pm.resolveActivity(
        Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).putExtra(
            RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
        ),
        0,
    )?.activityInfo?.packageName ?: "none"

    val info = pm.getPackageInfo(context.packageName, 0)
    val installedAt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())
        .format(Date(info.lastUpdateTime))

    return buildString {
        appendLine("app ${info.versionName} @ $installedAt")
        appendLine("设备 ${Build.MANUFACTURER} ${Build.MODEL} / API ${Build.VERSION.SDK_INT}")
        appendLine("isRecognitionAvailable = ${SpeechRecognizer.isRecognitionAvailable(context)}")
        appendLine("识别服务 ${services.size} 个${if (serviceNames.isEmpty()) "" else " [$serviceNames]"}")
        append("语音输入界面 $voiceInput")
    }
}
