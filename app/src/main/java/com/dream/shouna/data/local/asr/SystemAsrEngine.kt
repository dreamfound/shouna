package com.dream.shouna.data.local.asr

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * **系统识别服务**通道（`AsrEngine` 实现之一）。
 *
 * 【本文件是「搬家」而不是「重写」】识别参数、回调处理、空文本判失败这些，逐条对应当前
 * `ui/component/VoiceCapturePad.kt` 里原来那段 `SpeechRecognizer` 代码 —— 搬迁只改了两处：
 * 1. 语言**固定 `zh-CN`**（原来是 `Locale.getDefault().toLanguageTag()`，见 [systemRecognizerIntent]）；
 * 2. 识别器改成**按会话创建、终局即销毁**（原来在组合期建一次、离开组合才销毁）。
 *
 * 第 2 点顺带消掉了原来的一个隐患：那版把识别器与「组件是否还在组合里」绑死；现在每次长按
 * 都是干净的一份。
 *
 * 【它是「优先」的那一条】[RoutingAsrEngine] 先问本引擎；只有它 `isAvailable() == false`
 * 时才落到内置离线引擎。理由是零体积、零额外延迟，且多数机型的精度更好。
 *
 * 【零网络】识别跑在系统识别服务进程里，本应用自身不出网，也没有新增任何权限
 * （`实现约束.md` §7-1 / §7-3 的承诺未被本通道改变）。
 */
@Singleton
class SystemAsrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : AsrEngine {

    /**
     * `queryIntentServices(RecognitionService.SERVICE_INTERFACE)` 是否非空。
     *
     * ⚠️ 它受 **Android 11+ 包可见性过滤**约束 ⇒ 清单 `<queries>` 必须声明
     * `android.speech.RecognitionService`，否则本方法恒为 false（2026-10-10 实测根因，
     * 见 `docs/实现约束.md` §7-4）。这不是权限，只是「让本应用看得见系统里已有的识别服务」。
     */
    override fun isAvailable(): Boolean = SpeechRecognizer.isRecognitionAvailable(context)

    override suspend fun start(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (AsrError) -> Unit,
    ): AsrSession? {
        // 【刻意不做 withContext 切线程】`SpeechRecognizer` 的构造与销毁都必须在主线程，
        // 而本方法的唯一调用方是 Compose 层（本来就在主线程）。更要紧的是：**不要引入挂起点** ——
        // 挂起点一旦落在「识别器已创建、尚未交回调用方」之间，协程取消就会留下一个还在收听的
        // 识别器（无人持有、无法销毁）。`VoskAsrEngine` 出于同一理由也是同一写法。
        if (Looper.myLooper() != Looper.getMainLooper()) return null
        if (!isAvailable()) return null
        return SystemAsrSession(context, onPartial, onResult, onError).also { it.begin() }
    }

    /** 系统识别器按会话创建、终局销毁 ⇒ 引擎自身不持有任何长期资源。 */
    override fun release() = Unit
}

/**
 * 识别参数。与搬迁前逐字一致，只有语言被固定下来。
 *
 * 【语言为什么固定中文】原来跟随 `Locale.getDefault()`：界面与提示词只认中文，而手机语言
 * 设成英文时，中文语音会被识别成乱码或直接识别不出（用户 2026-10-10 裁定只修这一条）。
 */
private fun systemRecognizerIntent(): Intent =
    Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
        putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
        putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
        putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
        putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        // 有本地语音包就走本地，没有才回落系统服务（`prd/14` §14.4 的「本地优先」）。
        putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
    }

/**
 * 一次系统识别。
 *
 * 【终局只许发生一次】真机上「`onError` 与 `onResults` 先后都来」很常见（个别 ROM 更是两个
 * 都给）。[finished] 保证只回调调用方一次，且回调之后识别器一定被销毁。
 *
 * 【为什么在回调里直接 `destroy()`】`onResults` / `onError` 是「本次识别已经结束」的官方信号，
 * 此刻销毁是安全的；拖到下一次长按再销毁，反而会让两次会话的识别器叠在一起。
 *
 * 【生命周期】构造与 [begin] / [stop] / [cancel] 都必须在主线程（`SpeechRecognizer` 的硬要求），
 * 而调用方是 Compose 层，天然满足。
 */
private class SystemAsrSession(
    context: Context,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (AsrError) -> Unit,
) : AsrSession {

    private val recognizer: SpeechRecognizer = SpeechRecognizer.createSpeechRecognizer(context)

    private var finished = false

    private val listener = object : RecognitionListener {
        override fun onReadyForSpeech(params: Bundle?) = Unit

        override fun onBeginningOfSpeech() = Unit

        override fun onRmsChanged(rmsdB: Float) = Unit

        override fun onBufferReceived(buffer: ByteArray?) = Unit

        override fun onEndOfSpeech() = Unit

        override fun onEvent(eventType: Int, params: Bundle?) = Unit

        override fun onError(error: Int) {
            // 抬手太快 / 环境太吵 / 无有效语音都落在这里（错误码按 2026-10-10 的裁定不再细分）。
            fail(AsrError.OTHER)
        }

        override fun onResults(results: Bundle?) {
            val text = results
                ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()
                .orEmpty()
                .trim()
            if (text.isEmpty()) fail(AsrError.NO_SPEECH) else deliver(text)
        }

        override fun onPartialResults(partialResults: Bundle?) {
            if (finished) return
            onPartial(
                partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    ?.firstOrNull()
                    .orEmpty(),
            )
        }
    }

    fun begin() {
        recognizer.setRecognitionListener(listener)
        recognizer.startListening(systemRecognizerIntent())
    }

    override fun stop() {
        // `stopListening`（不是 `cancel`）：停止收音但保留已识别内容 —— 抬手要出字。
        recognizer.stopListening()
    }

    override fun cancel() {
        if (finished) return
        finished = true
        recognizer.cancel()
        recognizer.destroy()
    }

    private fun deliver(text: String) {
        if (finished) return
        finished = true
        onResult(text)
        recognizer.destroy()
    }

    private fun fail(reason: AsrError) {
        if (finished) return
        finished = true
        onError(reason)
        recognizer.destroy()
    }
}
