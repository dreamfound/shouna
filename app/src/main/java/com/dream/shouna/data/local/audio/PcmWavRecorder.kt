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
// package com.dream.shouna.data.local.audio
//
// import android.media.AudioFormat
// import android.media.AudioRecord
// import android.media.MediaRecorder
// import android.util.Log
// import java.io.ByteArrayOutputStream
// import java.util.concurrent.atomic.AtomicBoolean
// import javax.inject.Inject
// import javax.inject.Singleton
// import kotlin.concurrent.thread
//
// /**
//  * [AudioRecorder] 的默认实现：`AudioRecord` 采 **PCM16 / 16 kHz / 单声道**，收尾时自写
//  * **44 字节 WAV 头**，交出一段可直接上行的 WAV 字节。
//  *
//  * 【为什么是 WAV 而不是 mp3】上行报文（OpenAI 兼容的 `input_audio`）只认 `wav` / `mp3`，
//  * 而 Android 平台**没有** mp3 编码器（`MediaCodec` 只有 AAC，AOSP 不带 mp3 encoder）。
//  * 走 mp3 就得引入外部编码器 —— 与「依赖零新增」正面冲突。故取 WAV：体积换零依赖
//  * （16 kHz 单声道 ≈ 32 KB/s，30 秒 ≈ 0.96 MB，base64 后 ≈ 1.3 MB）。
//  * 时长上限不在这里，在组件层（`AudioCapturePad` 的 `MAX_RECORD_MILLIS`）。
//  *
//  * 【与内置识别引擎同一档的采样参数】16 kHz 单声道与 `VoskAsrEngine` 的 `SAMPLE_RATE_HZ` 同值 ——
//  * 那是语音输入最常用的一档，也让两套通道的音频参数不必各记一套。
//  *
//  * 【三个刻意为之的实现细节】
//  * 1. **`start()` 里不引入挂起点**（与 `AsrEngine` 同一条硬口径）：挂起点一旦落在
//  *    「麦克风已打开、尚未交回调用方」之间，协程取消会留下一个**无人持有、无法停止**的会话。
//  * 2. **读取在独立线程里**：`AudioRecord.read` 是阻塞调用，放主线程等于卡 UI。
//  * 3. **`stop()` 不收摊就不返回**：先停收音、再 `join` 读取线程、最后 `release` —— 顺序反了
//  *    会读到已被释放的 `AudioRecord`（真机上是 `IllegalStateException`）。
//  *
//  * 【一次只允许一个会话】`AudioRecord` 是独占资源（与 `VoskAsrEngine` 同一条理由）：
//  * 第二个会话连构造都会失败。用 [busy] 把这条约束显式化，第二个调用者拿到的就是 `null`。
//  */
// @Singleton
// class PcmWavRecorder @Inject constructor() : AudioRecorder {
//
//     /** 已有一个会话在跑；会话收摊（不管哪种收法）即释放。 */
//     private val busy = AtomicBoolean(false)
//
//     override suspend fun start(): RecordingSession? {
//         if (!busy.compareAndSet(false, true)) return null
//
//         val minBuffer = AudioRecord.getMinBufferSize(
//             AUDIO_SAMPLE_RATE_HZ,
//             AudioFormat.CHANNEL_IN_MONO,
//             AudioFormat.ENCODING_PCM_16BIT,
//         )
//         if (minBuffer <= 0) {
//             // 设备不支持这组参数（极少见，但 `getMinBufferSize` 就是靠返回值报这个的）。
//             Log.w(TAG, "AudioRecord.getMinBufferSize 返回 $minBuffer，本设备不支持 16 kHz 单声道 PCM16")
//             busy.set(false)
//             return null
//         }
//         // 取「最小缓冲」与「100 毫秒」中的较大者：前者是硬底线，后者让 read 的阻塞粒度不至于太碎。
//         val bufferSize = maxOf(minBuffer, AUDIO_SAMPLE_RATE_HZ / 10 * BYTES_PER_SAMPLE)
//
//         val record = try {
//             AudioRecord.Builder()
//                 .setAudioSource(MediaRecorder.AudioSource.MIC)
//                 .setAudioFormat(
//                     AudioFormat.Builder()
//                         .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
//                         .setSampleRate(AUDIO_SAMPLE_RATE_HZ)
//                         .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
//                         .build(),
//                 )
//                 .setBufferSizeInBytes(bufferSize)
//                 .build()
//         } catch (t: Throwable) {
//             Log.w(TAG, "AudioRecord 构造失败", t)
//             busy.set(false)
//             return null
//         }
//
//         if (record.state != AudioRecord.STATE_INITIALIZED) {
//             Log.w(TAG, "AudioRecord 未初始化成功（state = ${record.state}）")
//             runCatching { record.release() }
//             busy.set(false)
//             return null
//         }
//
//         return try {
//             record.startRecording()
//             PcmRecordingSession(record, bufferSize) { busy.set(false) }
//         } catch (t: Throwable) {
//             Log.w(TAG, "AudioRecord.startRecording 失败", t)
//             runCatching { record.release() }
//             busy.set(false)
//             null
//         }
//     }
//
//     private companion object {
//         const val TAG = "PcmWavRecorder"
//         const val BYTES_PER_SAMPLE = AUDIO_BITS_PER_SAMPLE / 8
//     }
// }
//
// /**
//  * 一次 PCM 录音会话。
//  *
//  * 【收摊顺序】`stop()` / `cancel()` 共用 [release]：`record.stop()` → `join` 读取线程 →
//  * `record.release()` → 释放单例的占用标记。**幂等由 CAS 保证**（不是 `if (closed) return`）——
//  * 「抬手」与「到时长上限自动收尾」会同时到达，两次调用必须只有一次真的收摊。
//  */
// private class PcmRecordingSession(
//     private val record: AudioRecord,
//     bufferSize: Int,
//     private val onClosed: () -> Unit,
// ) : RecordingSession {
//
//     private val pcm = ByteArrayOutputStream()
//     private val closed = AtomicBoolean(false)
//
//     private val reader = thread(name = READER_THREAD_NAME) {
//         val buffer = ByteArray(bufferSize)
//         try {
//             while (!closed.get()) {
//                 val read = record.read(buffer, 0, buffer.size)
//                 when {
//                     // `read` 只在拿到数据时为正；0 是「还没满」，继续下一轮。
//                     read > 0 -> synchronized(pcm) { pcm.write(buffer, 0, read) }
//                     read < 0 -> return@thread
//                 }
//             }
//         } catch (t: Throwable) {
//             // 与 stop()/cancel() 并发时 read 会抛（AudioRecord 已停止）—— 正常退出路径，不记日志。
//         }
//     }
//
//     override fun stop(): ByteArray? {
//         if (!closed.compareAndSet(false, true)) return null
//         release()
//         val data = synchronized(pcm) { pcm.toByteArray() }
//         // 一个字节都没录到（抬手太快 / 麦克风被占）：交 null，让组件层给「再试一次」。
//         return if (data.isEmpty()) null else wrapWav(data)
//     }
//
//     override fun cancel() {
//         if (!closed.compareAndSet(false, true)) return
//         release()
//     }
//
//     private fun release() {
//         runCatching { record.stop() }
//         runCatching { reader.join(JOIN_TIMEOUT_MILLIS) }
//         runCatching { record.release() }
//         onClosed()
//     }
// }
//
// /** 读取线程名。只用于排查（崩溃栈里能认出是谁）。 */
// private const val READER_THREAD_NAME = "shouna-audio-read"
//
// /** `join` 的上限：读取循环最坏阻塞一次 `read`（约 100 毫秒），1 秒足够；超时就不等了。 */
// private const val JOIN_TIMEOUT_MILLIS = 1_000L
//
// /** WAV 头固定 44 字节（PCM 的规范长度）。 */
// internal const val WAV_HEADER_BYTES = 44
//
// /** 采样率：16 kHz —— 语音输入最常用的一档，与内置识别引擎同值。 */
// internal const val AUDIO_SAMPLE_RATE_HZ = 16_000
//
// /** 声道数：单声道（语音只需要一路）。 */
// internal const val AUDIO_CHANNEL_COUNT = 1
//
// /** 位深：16 bit —— `AudioFormat.ENCODING_PCM_16BIT` 的字面值。 */
// internal const val AUDIO_BITS_PER_SAMPLE = 16
//
// /**
//  * PCM 裸数据 → 完整 WAV 字节（44 字节头 + 数据）。
//  *
//  * 【为什么要自己拼头】`AudioRecord` 交出的只有裸 PCM，**没有容器**。上行报文要的是「一段 WAV」，
//  * 而平台没有「PCM → WAV」的现成 API（`MediaMuxer` 只认编码后的轨）。头本身是定长 44 字节的
//  * 固定布局，拼一次就够 —— 属纯函数，无状态、无 Android 依赖（只在 `ByteArray` 上操作）。
//  *
//  * 布局（全部小端）：`RIFF` / 块长 / `WAVE` / `fmt ` / 子块长 16 / 格式 1(PCM) / 声道数 /
//  * 采样率 / 字节率 / 块对齐 / 位深 / `data` / 数据长。
//  */
// internal fun wrapWav(pcm: ByteArray): ByteArray {
//     val dataSize = pcm.size
//     val blockAlign = AUDIO_CHANNEL_COUNT * AUDIO_BITS_PER_SAMPLE / 8
//     val byteRate = AUDIO_SAMPLE_RATE_HZ * blockAlign
//
//     val header = ByteArray(WAV_HEADER_BYTES)
//     header.putAscii(0, "RIFF")
//     header.putIntLe(4, WAV_HEADER_BYTES - 8 + dataSize)
//     header.putAscii(8, "WAVE")
//     header.putAscii(12, "fmt ")
//     header.putIntLe(16, 16)
//     header.putShortLe(20, 1)
//     header.putShortLe(22, AUDIO_CHANNEL_COUNT)
//     header.putIntLe(24, AUDIO_SAMPLE_RATE_HZ)
//     header.putIntLe(28, byteRate)
//     header.putShortLe(32, blockAlign)
//     header.putShortLe(34, AUDIO_BITS_PER_SAMPLE)
//     header.putAscii(36, "data")
//     header.putIntLe(40, dataSize)
//
//     val out = ByteArray(WAV_HEADER_BYTES + dataSize)
//     header.copyInto(out, 0)
//     pcm.copyInto(out, WAV_HEADER_BYTES)
//     return out
// }
//
// private fun ByteArray.putAscii(offset: Int, value: String) {
//     for (i in value.indices) this[offset + i] = value[i].code.toByte()
// }
//
// private fun ByteArray.putIntLe(offset: Int, value: Int) {
//     this[offset] = (value and 0xFF).toByte()
//     this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
//     this[offset + 2] = ((value ushr 16) and 0xFF).toByte()
//     this[offset + 3] = ((value ushr 24) and 0xFF).toByte()
// }
//
// private fun ByteArray.putShortLe(offset: Int, value: Int) {
//     this[offset] = (value and 0xFF).toByte()
//     this[offset + 1] = ((value ushr 8) and 0xFF).toByte()
// }
