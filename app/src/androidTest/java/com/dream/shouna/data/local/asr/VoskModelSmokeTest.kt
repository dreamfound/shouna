package com.dream.shouna.data.local.asr

import android.content.Context
import android.os.Build
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.util.Locale
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.vosk.Model
import org.vosk.Recognizer

/**
 * 内置离线引擎（Vosk）的**可行性验证**：把真实中文音频**直接喂给 Vosk**，看能不能出字、出得准不准。
 *
 * 【它不测什么，先说清楚】本测试**不经过** [AsrEngine] / [VoskAsrEngine] —— 那两者是
 * 麦克风绑定的（`SpeechService` 自己开 `AudioRecord`，没有音频文件入口）。所以本测试证明的是
 * 「**随包发布的模型在 Android 上能装载、Vosk 能把它解成中文**」，证明不了
 * 「引擎的会话胶水（分段累加 / 回调交付）也对」——那段只有真人对着麦克风说才能覆盖。
 * 也正因为不走引擎，它**不需要麦克风权限**、**不产生任何网络请求**。
 *
 * 【为什么要有它】`docs/V2-AI-PRD.md` §2 原写「语音识别只用系统能力」，而实测（realme
 * RMX8899，Android 16）该设备 `RecognitionService` 为 0 条、`ACTION_RECOGNIZE_SPEECH`
 * 无落点 ⇒ 国行 ROM 上不存在任何系统路径。换成自带引擎是一次**体积换能力**的赌注
 * （debug 包从 14.6 MB 涨到 66.6 MB），所以在真机复测之前，先用音频把「引擎到底转不转得动、
 * 准到什么程度」钉死 —— 若这段都出不了字，后面所有装机复测都没有意义。
 *
 * 【两段音频，两种问法】
 * 1. `zh_reference_16k.wav`（5.6 s）—— **有公开的参考转录**
 *    「对我做了介绍啊那么我想说的是呢大家如果对我的研究感兴趣呢」，
 *    故可以算出字符错误率（CER），回答「**准不准**」；
 * 2. `cn_wild_16k.wav`（8.4 s）—— 野外录音（CN-Celeb，采访/视频背景音），**没有转录**，
 *    只回答「**通道通不通**」（出了非空汉字即可）。
 */
@RunWith(AndroidJUnit4::class)
class VoskModelSmokeTest {

    @Test
    fun referenceWaveFile_producesTextCloseToKnownTranscript() {
        val outcome = recognize(REFERENCE_ASSET)
        val cer = editDistance(outcome.text.replace(" ", ""), REFERENCE_TEXT) / REFERENCE_TEXT.length.toDouble()
        Log.i(TAG, "参考=[$REFERENCE_TEXT]")
        Log.i(TAG, "识别=[${outcome.text}]")
        Log.i(TAG, "字符错误率 CER=${String.format(Locale.US, "%.2f", cer)}（参考 ${REFERENCE_TEXT.length} 字）")

        assertTrue("Vosk 一个字都没出 —— 引擎或模型有问题", outcome.text.isNotBlank())
        assertTrue("出的字不含汉字（模型可能没被正确装载）[${outcome.text}]", outcome.text.hasChinese())
        // 门槛刻意留得很松：这段音频是给小模型判「能不能用」，不是判「够不够好」。
        // 真要好坏结论，看 logcat 里那两行原文。
        assertTrue("错得离谱（CER=${cer}）—— 引擎可能装载了错误的模型", cer < 0.9)
    }

    @Test
    fun wildRecording_stillProducesChineseText() {
        val outcome = recognize(WILD_ASSET)
        Log.i(TAG, "野外录音识别=[${outcome.text}]")
        assertTrue("Vosk 一个字都没出 —— 引擎或模型有问题", outcome.text.isNotBlank())
        assertTrue("出的字不含汉字（模型可能没被正确装载）[${outcome.text}]", outcome.text.hasChinese())
    }

    // ---- 识别主流程 ----

    private class Outcome(val text: String, val decodeMs: Long)

    /** 把一段 wav 从测试资产里喂给 Vosk，返回识别文字。 */
    private fun recognize(assetName: String): Outcome {
        val target = InstrumentationRegistry.getInstrumentation().targetContext
        val testAssets = InstrumentationRegistry.getInstrumentation().context.assets

        // 1) 模型：从**随包的 assets** 抄一份到私有目录（Vosk 的 C++ 层只认普通文件路径）。
        //    同一轮里多个测试共用一份，所以先在则不再抄。
        val modelDir = File(target.filesDir, MODEL_INSTALL_DIR)
        val copyMs = elapsed { installModel(target, modelDir) }

        // 2) 音频：解 WAV 头，取出裸 PCM。
        val wav = parseWav(testAssets.open(assetName).use { it.readBytes() })
        assertEquals("音频必须是 16 kHz（与模型训练采样率一致）", 16_000, wav.sampleRate)
        assertEquals("音频必须是单声道", 1, wav.channels)
        assertEquals("音频必须是 16-bit PCM", 16, wav.bitsPerSample)

        // 3) 喂给 Vosk：按 0.1 秒一刀，并把每段「端点静音」的结果攒起来。
        val loadStart = System.currentTimeMillis()
        val model = Model(modelDir.absolutePath)
        val loadMs = System.currentTimeMillis() - loadStart
        val sizeMb = modelDir.walkTopDown().filter { it.isFile }.sumOf { it.length() } / 1024.0 / 1024.0
        Log.i(TAG, "$assetName：模型装载 ${loadMs}ms / assets 复制 ${copyMs}ms / ${sizeMb.round1()} MB / 设备 ${Build.MODEL} API ${Build.VERSION.SDK_INT}")

        val recognizer = Recognizer(model, SAMPLE_RATE_HZ)
        recognizer.setWords(false)
        val committed = StringBuilder()
        var decodeMs = 0L
        try {
            val decodeStart = System.currentTimeMillis()
            var offset = 0
            while (offset < wav.pcm.size) {
                val length = minOf(CHUNK_BYTES, wav.pcm.size - offset)
                // javap 实测：只有 (byte[], int) 两参重载，没有 (byte[], int, int) ⇒ 自己切片。
                val slice = wav.pcm.copyOfRange(offset, offset + length)
                if (recognizer.acceptWaveForm(slice, length)) {
                    // 端点静音：Vosk 在此**复位解码器** ⇒ 每段必须自己攒起来（生产同此口径）。
                    val segment = textOf(recognizer.result)
                    if (segment.isNotEmpty()) committed.append(segment).append(' ')
                }
                offset += length
            }
            val tail = textOf(recognizer.finalResult)
            if (tail.isNotEmpty()) committed.append(tail)
            decodeMs = System.currentTimeMillis() - decodeStart
        } finally {
            runCatching { recognizer.close() }
            runCatching { model.close() }
        }

        val text = committed.toString().trim()
        val rtf = decodeMs / 1000.0 / wav.durationSec
        Log.i(TAG, "$assetName：${wav.durationSec.round1()} 秒音频，解码 ${decodeMs}ms，RTF=${String.format(Locale.US, "%.2f", rtf)}")
        return Outcome(text, decodeMs)
    }

    // ---- 下面是本测试自用的最小工具（不引任何新依赖）----

    /**
     * 从 app 的 `assets/` 里把模型目录抄到 [target]（已在则跳过）。
     *
     * 【判据是「`list()` 是否为空」】`AssetManager` 没有「这是文件还是目录」的查询，只能靠
     * `list(path)`：空 ⇒ 文件；非空 ⇒ 目录。实测本模型目录下没有空文件夹。
     *
     * 【为什么不复用生产那份】[VoskAsrEngine] 的 `installModelIfNeeded` 是 `private`，且带
     * `.installed` 哨兵与完整性抽样。本测试**刻意**不去动它：这次要验的是「Vosk 本身」，
     * 把生产的装载代码一起搅进来，失败了分不清是谁的问题。
     */
    private fun installModel(app: Context, target: File) {
        if (REQUIRED_MODEL_FILES.all { File(target, it).isFile }) return
        target.deleteRecursively()
        copyAssetDir(app, MODEL_ASSET_DIR, target)
    }

    private fun copyAssetDir(app: Context, assetPath: String, target: File) {
        val children = app.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            app.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { copyAssetDir(app, "$assetPath/$it", File(target, it)) }
    }

    /** 取 Vosk 返回的 JSON 里 `text` 字段（与生产同一手法，容忍解析失败）。 */
    private fun textOf(json: String): String =
        runCatching { JSONObject(json).optString("text", "") }.getOrDefault("").trim()

    private fun String.hasChinese(): Boolean = any { it.code in 0x4E00..0x9FFF }

    /** 字符级编辑距离（CER 的分子）。两行 DP，不值得为它引依赖。 */
    private fun editDistance(a: String, b: String): Int {
        var previous = IntArray(b.length + 1) { it }
        var current = IntArray(b.length + 1)
        for (i in 1..a.length) {
            current[0] = i
            for (j in 1..b.length) {
                val substitution = previous[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1
                current[j] = minOf(current[j - 1] + 1, previous[j] + 1, substitution)
            }
            val recycled = previous
            previous = current
            current = recycled
        }
        return previous[b.length]
    }

    private inline fun elapsed(block: () -> Unit): Long {
        val start = System.currentTimeMillis()
        block()
        return System.currentTimeMillis() - start
    }

    private fun Double.round1(): String = String.format(Locale.US, "%.1f", this)

    private class Wav(val sampleRate: Int, val channels: Int, val bitsPerSample: Int, val pcm: ByteArray) {
        /** 16-bit 单声道 ⇒ 帧数 = 字节数 / 2。 */
        val durationSec: Double get() = pcm.size / 2.0 / sampleRate
    }

    /** 手解 RIFF/WAVE：只认 `fmt ` 与 `data` 两个块，够用（不引第三方）。 */
    private fun parseWav(bytes: ByteArray): Wav {
        require(bytes.size > 44) { "WAV 太短" }
        require(String(bytes, 0, 4, Charsets.US_ASCII) == "RIFF") { "不是 RIFF" }
        require(String(bytes, 8, 4, Charsets.US_ASCII) == "WAVE") { "不是 WAVE" }
        var pos = 12
        var rate = 0
        var channels = 0
        var bits = 0
        var pcm: ByteArray? = null
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4, Charsets.US_ASCII)
            val size = leInt(bytes, pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> {
                    channels = leShort(bytes, body + 2)
                    rate = leInt(bytes, body + 4)
                    bits = leShort(bytes, body + 14)
                }

                "data" -> pcm = bytes.copyOfRange(body, minOf(body + size, bytes.size))
            }
            pos = body + size + (size and 1)
        }
        val payload = requireNotNull(pcm) { "WAV 里没有 data 块" }
        return Wav(rate, channels, bits, payload)
    }

    private fun leInt(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8) or
            ((b[at + 2].toInt() and 0xFF) shl 16) or ((b[at + 3].toInt() and 0xFF) shl 24)

    private fun leShort(b: ByteArray, at: Int): Int =
        (b[at].toInt() and 0xFF) or ((b[at + 1].toInt() and 0xFF) shl 8)

    private companion object {
        const val TAG = "VoskSmoke"

        /** 有公开参考转录的样本（sherpa-onnx 官方文档里 `test_wavs/0.wav` 的期望输出）。 */
        const val REFERENCE_ASSET = "zh_reference_16k.wav"

        /** 上面那段音频的参考转录（官方文档给出，未经改写）。 */
        const val REFERENCE_TEXT = "对我做了介绍啊那么我想说的是呢大家如果对我的研究感兴趣呢"

        /** 野外录音样本（CN-Celeb，无转录，只验通道）。 */
        const val WILD_ASSET = "cn_wild_16k.wav"

        /** 模型在 `assets/` 下的目录名（与生产 [VoskAsrEngine] 同源）。 */
        const val MODEL_ASSET_DIR = "vosk-model-small-cn-0.22"

        /** 本测试自己的落点，**刻意**与生产分开，免得互相污染。 */
        const val MODEL_INSTALL_DIR = "vosk-smoke/vosk-model-small-cn-0.22"

        /** 「模型已抄好」的判据（缺任一个 [Model] 都必然加载失败）。 */
        val REQUIRED_MODEL_FILES = listOf("am/final.mdl", "graph/HCLr.fst")

        /** 模型按 16 kHz 训练 ⇒ 采样率必须一致。 */
        const val SAMPLE_RATE_HZ = 16_000f

        /** 0.1 秒 @ 16 kHz / 单声道 / 16-bit = 3200 字节。 */
        const val CHUNK_BYTES = 3_200
    }
}
