package com.dream.shouna.data.local.asr

import android.content.Context
import com.dream.shouna.di.IoDispatcher
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.IOException
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService

/**
 * **内置离线**中文语音识别通道（`AsrEngine` 实现之一，Vosk + 随包发布的小模型）。
 *
 * 【它解决的是什么】`docs/V2-AI-PRD.md` 原先写「语音识别只用系统能力」，前提是设备上**有**
 * 那个能力。2026-10-10 用 adb 只读探查定案：国行 ROM（实测 realme RMX8899，Android 16）
 * `queryIntentServices(RecognitionService.SERVICE_INTERFACE)` **返回 0 条**，
 * `ACTION_RECOGNIZE_SPEECH` 也 `No activity found` —— 没有任何系统识别服务、也没有语音输入界面
 * 可回落。于是「录一句话变成字」在这些机器上**没有任何系统路径**，只能自带引擎。
 *
 * 【与 `实现约束.md` §7-1 / §7-3 的关系】本通道**完全离线**：模型随包发布、识别在进程内跑，
 * **不新增任何权限**（尾部的 `RECORD_AUDIO` 是既有的）、**不产生任何网络请求**。
 * 「零网络」这条承诺没有被打破；被改写的是 `V2-AI-PRD.md` §2「只用系统能力」那半句。
 *
 * 【体积代价（实测，非估算）】AAR 13.5 MB（含四架构 `libvosk.so`，未压缩合计约 39.7 MB）
 * + 模型压缩包 41.9 MB（**解包后 66 MB / 14 个文件**，进 APK 时仍会被压缩回约 42 MB）。
 * 故 `app/build.gradle.kts` 配了 ABI 拆包，模型目录不入仓库（根 `.gitignore`）。
 *
 * 【模型怎么进设备】Vosk 的 C++ 层只认**普通文件路径**，读不了 assets ⇒ 首次使用时把
 * `assets/[MODEL_ASSET_DIR]` 复制到 `filesDir/[MODEL_INSTALL_DIR]/`。
 *
 * 为什么不用 Vosk 自带的 `org.vosk.android.StorageService.unpack/sync`，有两个独立原因：
 * 1. 它把模型写到 `getExternalFilesDir()`（外部私有目录），而本工程的文件一律落 `filesDir`
 *    （`PhotoStore` 同此口径）—— **存储位置要么统一、要么就得解释**，这里选统一。
 * 2. 它靠模型目录里的 `uuid` 文件判版本，而**本模型根本没有 `uuid`**（见 [installModelIfNeeded]）
 *    ⇒ 那条路在本模型上直接抛 `FileNotFoundException`，不是「换个写法」而是「根本走不通」。
 *
 * 代价是那三四十行复制代码由我们自己维护 —— 换来的是哨兵能按自己的口径写。
 */
@Singleton
class VoskAsrEngine @Inject constructor(
    @ApplicationContext private val context: Context,
    @param:IoDispatcher private val io: CoroutineDispatcher,
) : AsrEngine {

    /** 模型装载很慢（数百毫秒到数秒），装载后常驻；[modelLock] 防并发重复装载。 */
    private val modelLock = Mutex()

    @Volatile
    private var model: Model? = null

    /** 一次只允许一个会话：`AudioRecord` 是独占资源，第二个会话连构造都会失败。 */
    private val inUse = AtomicBoolean(false)

    /**
     * 恒为 `true`。
     *
     * 【为什么不用「探测模型文件在不在」代替】模型是**随包发布**的 assets，它「在不在」由构建
     * 决定，运行期探测没有增量信息；真正会失败的是「装载」（磁盘满、assets 读失败），
     * 而那一步在 [start] 里、以返回 `null` 表达。让本方法保持廉价是硬要求（组件重组时会被反复调）。
     */
    override fun isAvailable(): Boolean = true

    override suspend fun start(
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (AsrError) -> Unit,
    ): AsrSession? {
        // 先占坑再装载：装载期间用户又按一次，第二次直接得 null（而不是排队等一个录音会话）。
        if (!inUse.compareAndSet(false, true)) return null
        return try {
            val ready = loadOrGetModel()
            if (ready == null) {
                inUse.set(false)
                null
            } else {
                createSession(ready, onPartial, onResult, onError)
            }
        } catch (cancellation: CancellationException) {
            inUse.set(false)
            throw cancellation
        } catch (failure: Throwable) {
            inUse.set(false)
            null
        }
    }

    override fun release() {
        model?.let { runCatching { it.close() } }
        model = null
    }

    /**
     * 取（必要时装载）模型。**唯一会挂起的地方**，且这里不创建任何会话资源 ⇒ 协程此时被取消
     * 不会留下「还在收音」的会话。
     */
    private suspend fun loadOrGetModel(): Model? {
        model?.let { return it }
        return modelLock.withLock {
            model?.let { return@withLock it }
            val loaded = withContext(io) {
                runCatching { Model(installModelIfNeeded()) }.getOrNull()
            }
            loaded?.also { model = it }
        }
    }

    /**
     * **建立会话 —— 刻意不挂起、不切线程**（跑在调用方线程，即 Compose 主线程）。
     *
     * 理由与 `SystemAsrEngine.start` 逐字相同：挂起点一旦落在「`SpeechService` 已开始收音、
     * 尚未交回调用方」之间，协程取消就会留下一个**没人持有、无法停止**的麦克风会话。
     * 三段构造（`Recognizer` / `SpeechService` / `startListening`）都是本地调用，几十毫秒，
     * 放在主线程无碍。
     */
    private fun createSession(
        model: Model,
        onPartial: (String) -> Unit,
        onResult: (String) -> Unit,
        onError: (AsrError) -> Unit,
    ): AsrSession {
        val recognizer = Recognizer(model, SAMPLE_RATE_HZ)
        // 不取词级时间戳（面板用不上，白花内存）。
        recognizer.setWords(false)
        val service = try {
            SpeechService(recognizer, SAMPLE_RATE_HZ)
        } catch (failure: IOException) {
            // 识别器已经建出来了，构造录音器失败时必须自己收掉，否则原生内存没人释放。
            runCatching { recognizer.close() }
            throw failure
        }
        val session = VoskSession(
            service = service,
            recognizer = recognizer,
            onPartial = onPartial,
            onResult = onResult,
            onError = onError,
            onFinished = { inUse.set(false) },
        )
        service.startListening(session.listener)
        return session
    }

    /**
     * 把 `assets/[MODEL_ASSET_DIR]` 复制到 `filesDir/[MODEL_INSTALL_DIR]/[MODEL_ASSET_DIR]`，
     * 返回模型目录的绝对路径。
     *
     * 【哨兵为什么不是 Vosk 那套 `uuid`】Vosk 自己的 `StorageService.sync()` 靠模型目录里的
     * `uuid` 文件判版本。但**随包发布的 `vosk-model-small-cn-0.22` 里没有 `uuid`**
     * （实测：目录只有 `README` / `am/` / `conf/` / `graph/` / `ivector/` 共 14 个文件）——
     * 也就是说官方那条路在本模型上**本来就会抛 `FileNotFoundException`**。
     * 于是改由本工程自己记：复制**完成之后**写一个 [STAMP_FILE_NAME]，内容 = [MODEL_REVISION]。
     *
     * 【为什么要「完成之后」才写】**中途失败（磁盘满 / 进程被杀）不能留下半个模型**：
     * 标记不在 ⇒ 下次重来；而若先写标记再复制，坏掉的副本会被永久当成好的用。
     * 再加一道 [REQUIRED_FILES] 抽样（缺任何一个 `Model(...)` 都必然加载失败），
     * 覆盖「标记写了但文件后来被清掉」这种异常。
     */
    private fun installModelIfNeeded(): String {
        val target = File(context.filesDir, "$MODEL_INSTALL_DIR/$MODEL_ASSET_DIR")
        val installed =
            File(target, STAMP_FILE_NAME).takeIf { it.isFile }?.readText()?.trim() == MODEL_REVISION &&
                REQUIRED_FILES.all { File(target, it).isFile }
        if (installed) return target.absolutePath

        target.deleteRecursively()
        target.mkdirs()
        copyAssetDir(MODEL_ASSET_DIR, target)
        File(target, STAMP_FILE_NAME).writeText(MODEL_REVISION)
        return target.absolutePath
    }

    /**
     * 递归复制一个 assets 目录。
     *
     * 【判据是「`list()` 是否为空」】`AssetManager` 没有「这是文件还是目录」的查询，
     * 只能靠 `list(path)`：返回空 ⇒ 它是文件；非空 ⇒ 它是目录（或含子项）。
     * 实测本模型目录下**没有空文件夹**（`find -type d -empty` 无输出），故该判据成立。
     */
    private fun copyAssetDir(assetPath: String, target: File) {
        val children = context.assets.list(assetPath).orEmpty()
        if (children.isEmpty()) {
            target.parentFile?.mkdirs()
            context.assets.open(assetPath).use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
            return
        }
        target.mkdirs()
        children.forEach { copyAssetDir("$assetPath/$it", File(target, it)) }
    }

    private companion object {
        /** 模型在 `assets` 下的目录名（`app/src/main/assets/` 内）。 */
        const val MODEL_ASSET_DIR = "vosk-model-small-cn-0.22"

        /** 模型在本应用私有目录下的落点前缀。 */
        const val MODEL_INSTALL_DIR = "vosk"

        /** 复制完成的标记文件名（写在模型目录内，Vosk 读取时忽略多余文件）。 */
        const val STAMP_FILE_NAME = ".installed"

        /**
         * 标记的内容 = 模型版本。**换模型时必须一起改**（或换 [MODEL_ASSET_DIR]）——
         * 它是「本机那份副本是不是当前这个模型」的唯一判据。
         */
        const val MODEL_REVISION = "vosk-model-small-cn-0.22"

        /** 完整性抽样：这两个文件缺任何一个，`Model(...)` 都必然加载失败。 */
        val REQUIRED_FILES = listOf("am/final.mdl", "graph/HCLr.fst")

        /** 模型按 16 kHz 训练 ⇒ 采样率必须一致，否则精度明显下降（Vosk 文档明示）。 */
        const val SAMPLE_RATE_HZ = 16_000f
    }
}

/**
 * 一次内置引擎会话。
 *
 * 【为什么必须累加分段 —— 本文件最容易被改错的一处】Vosk 在**检测到停顿**时会回调
 * `onResult` 并**复位解码器**：`getResult()` 之后，识别从零开始。所以「说一句 → 停顿 →
 * 再说一句 → 抬手」这条路径上，抬手拿到的 `onFinalResult` **只含最后那段**。把每段结果
 * 追加进 [committed]，最终交付 [committed] + 末段，才等于用户实际说的整句话。
 *
 * 【回调都在主线程】由 `SpeechService` 内部的 `mainHandler` 投递 —— 本类不需要再切线程。
 *
 * 【`stop()` 会阻塞调用线程约 0.2 秒】`SpeechService.stop()` 内部 `interrupt()` + `join()`
 * 识别线程，而那个线程正阻塞在一次 `AudioRecord.read`（缓冲区 = 0.2 秒音频）上。
 * 这与 Vosk 官方示例的行为一致；相对随后 1–2 秒的识别耗时，这一点延迟不可感知。
 */
private class VoskSession(
    private val service: SpeechService,
    private val recognizer: Recognizer,
    private val onPartial: (String) -> Unit,
    private val onResult: (String) -> Unit,
    private val onError: (AsrError) -> Unit,
    private val onFinished: () -> Unit,
) : AsrSession {

    /** 已经「定稿」的分段（`onResult` 交付过、且解码器已复位的那部分）。 */
    private val committed = StringBuilder()

    private var finished = false

    val listener = object : RecognitionListener {

        override fun onPartialResult(hypothesis: String) {
            if (finished) return
            val partial = jsonField(hypothesis, "partial")
            if (partial.isNotEmpty()) onPartial((committed.toString() + partial).trim())
        }

        override fun onResult(hypothesis: String) {
            if (finished) return
            val text = jsonField(hypothesis, "text")
            if (text.isNotEmpty()) {
                committed.append(text).append(' ')
                // 分段也先上屏：用户看得见「它在听什么」，说错了能立刻松手重来。
                onPartial(committed.toString().trim())
            }
        }

        override fun onFinalResult(hypothesis: String) {
            val text = jsonField(hypothesis, "text")
            if (text.isNotEmpty()) committed.append(text).append(' ')
            deliver(committed.toString().trim())
        }

        override fun onError(exception: Exception) = fail()

        override fun onTimeout() = deliver(committed.toString().trim())
    }

    override fun stop() {
        // `stop()`（不是 `cancel`）：停止收音、**保留**内容，并触发 `onFinalResult`。
        service.stop()
    }

    override fun cancel() {
        if (finished) return
        finished = true
        // `cancel` 会把识别线程标记为 paused 再停 ⇒ 事件不再投递，之后我们自己收摊。
        service.cancel()
        teardown()
    }

    private fun deliver(text: String) {
        if (finished) return
        finished = true
        if (text.isEmpty()) onError(AsrError.NO_SPEECH) else onResult(text)
        teardown()
    }

    private fun fail() {
        if (finished) return
        finished = true
        onError(AsrError.OTHER)
        teardown()
    }

    /** 释放：先放录音器，再释放原生识别器，最后把引擎的「占用中」标记交回去。 */
    private fun teardown() {
        runCatching { service.shutdown() }
        runCatching { recognizer.close() }
        onFinished()
    }
}

/**
 * 取 Vosk 返回的 JSON 里一个字符串字段（`{"text": "…"}` / `{"partial": "…"}`）。
 *
 * 【为什么容忍解析失败】这两个串的键名是 Vosk 的**输出契约**，正常不会变；但一旦变了，
 * 抛异常会把「识别失败」升级成「应用崩溃」——不值得。解析不出来就当没有内容。
 */
private fun jsonField(json: String, key: String): String =
    runCatching { JSONObject(json).optString(key, "") }
        .getOrDefault("")
        .trim()
