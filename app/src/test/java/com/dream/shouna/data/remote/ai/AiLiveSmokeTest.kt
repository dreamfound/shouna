package com.dream.shouna.data.remote.ai

import com.google.common.truth.Truth.assertThat
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.util.Base64
import java.util.zip.CRC32
import java.util.zip.Deflater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assume
import org.junit.Test

/**
 * **真调 DeepSeek 的冒烟测试** —— AI-01 的联网验收项：能调通 API，并拿到「你好」的返回；
 * 本轮加上**照片通道**（`V2-FR-11`）。
 *
 * 与其他单测的区别：其余用例一律不触网（假传输），本文件是**唯一**会真的发 HTTPS 请求的地方。
 * 因此它**默认跳过**：只有拿到密钥时才跑（`Assume` 不成立 → JUnit 记为 skipped，不会让常规
 * `./gradlew testDebugUnitTest` 变红，也不需要 CI 提供网络与凭据）。
 *
 * 密钥来源（按优先级，**任何一个都不打印、不落库**）：
 * 1. JVM 系统属性 `-Dshouna.ai.key=...`
 * 2. 环境变量 `SHOUNA_AI_KEY`
 * 3. 环境变量 `SHOUNA_AI_KEY_FILE` 指向的文件
 * 4. 仓库根 `key/dafeiyu.txt`（已 `.gitignore`）
 *
 * 跑法：`./gradlew :app:testDebugUnitTest --tests '*AiLiveSmokeTest*' -i`
 */
class AiLiveSmokeTest {

    @Test
    fun smoke_liveDeepSeek_repliesToNiHao() {
        val apiKey: String = resolveApiKey() ?: run {
            Assume.assumeTrue(KEY_HINT, false)
            return
        }
        val vendor = AiVendor.DEFAULT
        val http = UrlConnectionAiHttp(Dispatchers.IO)
        val body = AiJson.encodeToString(
            ChatRequest(
                model = vendor.model,
                messages = listOf(ChatMessage.text(ChatMessage.ROLE_USER, "你好")),
            ),
        )

        val startedAt = System.currentTimeMillis()
        val response = runBlocking {
            http.postJson(vendor.endpoint, mapOf("Authorization" to "Bearer $apiKey"), body)
        }
        val elapsedMs = System.currentTimeMillis() - startedAt

        val reply = response.body
            ?.let { runCatching { AiJson.decodeFromString<ChatResponse>(it).contentOrNull() }.getOrNull() }
        println("[AI 冒烟] POST ${vendor.endpoint} model=${vendor.model}")
        println("[AI 冒烟] HTTP ${response.code} 耗时 ${elapsedMs}ms")
        println("[AI 冒烟] 发送「你好」→ 返回「$reply」")

        assertThat(response.code).isEqualTo(200)
        assertThat(reply).isNotNull()
        assertThat(reply).isNotEmpty()
    }

    @Test
    fun smoke_liveDeepSeek_parsesOneSentenceIntoDraft() {
        val apiKey: String = resolveApiKey() ?: run {
            Assume.assumeTrue(KEY_HINT, false)
            return
        }
        val client = DeepSeekAiClient(UrlConnectionAiHttp(Dispatchers.IO), AiVendor.DEFAULT)
        val categories = listOf("电子配件", "厨房用品", "杂物")
        // V2-FR-12：调模型时附位置清单（此处取两条，看模型是否真的复用清单里的路径）。
        val locations = listOf("家 › 客厅 › 抽屉", "家 › 卧室 › 衣柜")
        val request = AiRequest(
            input = "客厅抽屉里的充电器，白色的",
            scene = AiScene.TEXT_INPUT,
            categoryNames = categories,
            locationPaths = locations,
        )

        // 打印**实际发出的 system prompt**：位置清单的缩进树形态一眼可核（人工复核用，不含密钥）。
        println(
            "[AI 冒烟] 实际发出的 system prompt：\n" +
                AiPrompt.system(request.scene, request.categoryNames, request.locationPaths),
        )

        val drafts = runBlocking { client.parse(request, apiKey) }

        println("[AI 冒烟] 分类名单：$categories")
        println("[AI 冒烟] 位置清单（调用方原文）：$locations")
        println("[AI 冒烟] 结构化结果：$drafts")

        assertThat(drafts).isNotNull()
        assertThat(drafts).isNotEmpty()
        val draft = drafts!!.first()
        assertThat(draft.name).isNotNull()
        // 附了清单 ⇒ 最深一层应与清单里「客厅 › 抽屉」的末层一致（V2-FR-12 的预期效果）。
        assertThat(draft.locationPath?.last()).isEqualTo("抽屉")
    }

    /**
     * 照片通道真调（`V2-FR-11`）：**一张图 → 多件草稿**。
     *
     * 测试图由 [buildTestPhotoPng] **现场生成**（纯 JDK：`Deflater` 手写 PNG），而不是塞一张
     * 二进制资源进 test 目录 —— 后者既难审阅也难改动，且会让人怀疑「这张图是不是特调的」。
     * 图里画 4 个可辨认的几何物体（红圆带把手 / 蓝矩形带书脊 / 绿长条带灰尖 / 黄方块）。
     *
     * 断言的取舍：**只钉「链路通 + 契约被遵守」，不钉识别件数**（模型表现属外部变量，写死数字
     * 会让冒烟随时变红）。件数只打印出来供人工判读。
     */
    @Test
    fun smoke_liveDeepSeek_parsesPhotoIntoDrafts() {
        val apiKey: String = resolveApiKey() ?: run {
            Assume.assumeTrue(KEY_HINT, false)
            return
        }
        val client = DeepSeekAiClient(UrlConnectionAiHttp(Dispatchers.IO), AiVendor.DEFAULT)
        val categories = listOf("电器", "衣物", "工具", "文具", "药品", "食品", "日用", "其他")
        val image = Base64.getEncoder().encodeToString(buildTestPhotoPng())
        val request = AiRequest(
            input = "",
            scene = AiScene.PHOTO_INPUT,
            categoryNames = categories,
            // 照片场景即便调用方给了位置清单，也不该附出去（V2-FR-13）——这里刻意传上，看报文。
            locationPaths = listOf("家 › 客厅 › 抽屉"),
            imageBase64 = image,
        )

        println(
            "[AI 冒烟·照片] 实际发出的 system prompt：\n" +
                AiPrompt.system(request.scene, request.categoryNames, request.locationPaths),
        )
        println("[AI 冒烟·照片] 图片 base64 长度 = ${image.length}")

        val startedAt = System.currentTimeMillis()
        val drafts = runBlocking { client.parse(request, apiKey) }
        val elapsedMs = System.currentTimeMillis() - startedAt

        println("[AI 冒烟·照片] 耗时 ${elapsedMs}ms，识别出 ${drafts?.size ?: -1} 件")
        drafts?.forEach { println("[AI 冒烟·照片]   $it") }

        assertThat(drafts).isNotNull()
        // V2-FR-13：位置由用户自己设 ⇒ 模型返回的每一条都不得带位置。
        assertThat(drafts!!.all { it.locationPath == null }).isTrue()
    }

    // --- 密钥来源 ---------------------------------------------------------------

    private fun resolveApiKey(): String? {
        systemProperty(KEY_SYSTEM_PROPERTY)?.let { return it }
        systemProperty(KEY_ENV)?.let { return it }

        val candidates = buildList {
            systemProperty(KEY_FILE_ENV)?.let { add(File(it)) }
            // Gradle 单测的工作目录随调用方式而异（模块目录 / 仓库根），逐个探一遍。
            add(File("key/dafeiyu.txt"))
            add(File("../key/dafeiyu.txt"))
            add(File("../../key/dafeiyu.txt"))
        }
        return candidates.firstOrNull { it.isFile }
            ?.readText()
            ?.trim()
            ?.takeIf { it.isNotEmpty() }
    }

    private fun systemProperty(name: String): String? =
        (System.getProperty(name) ?: System.getenv(name))?.trim()?.takeIf { it.isNotEmpty() }

    private companion object {
        const val KEY_SYSTEM_PROPERTY = "shouna.ai.key"
        const val KEY_ENV = "SHOUNA_AI_KEY"
        const val KEY_FILE_ENV = "SHOUNA_AI_KEY_FILE"
        const val KEY_HINT =
            "未找到 API Key（-Dshouna.ai.key / SHOUNA_AI_KEY / SHOUNA_AI_KEY_FILE / key/dafeiyu.txt），跳过真调冒烟"
    }
}

// --- 测试图生成（纯 JDK，不依赖 Android Bitmap / java.awt） --------------------------

/** 画一张 320×240、含 4 个可辨认几何物体的 PNG。 */
private fun buildTestPhotoPng(): ByteArray {
    val w = 320
    val h = 240
    val rgb = ByteArray(w * h * 3) { 0xF0.toByte() }

    fun put(x: Int, y: Int, r: Int, g: Int, b: Int) {
        if (x < 0 || y < 0 || x >= w || y >= h) return
        val i = (y * w + x) * 3
        rgb[i] = r.toByte()
        rgb[i + 1] = g.toByte()
        rgb[i + 2] = b.toByte()
    }

    fun rect(x0: Int, y0: Int, x1: Int, y1: Int, r: Int, g: Int, b: Int) {
        for (y in y0 until y1) for (x in x0 until x1) put(x, y, r, g, b)
    }

    fun disc(cx: Int, cy: Int, radius: Int, r: Int, g: Int, b: Int) {
        for (y in cy - radius..cy + radius) for (x in cx - radius..cx + radius) {
            if ((x - cx) * (x - cx) + (y - cy) * (y - cy) <= radius * radius) put(x, y, r, g, b)
        }
    }

    disc(70, 70, 42, 198, 40, 40)
    disc(118, 70, 18, 198, 40, 40)
    rect(142, 52, 136, 88, 240, 240, 240)
    rect(150, 34, 262, 108, 40, 80, 200)
    rect(150, 34, 160, 108, 20, 45, 130)
    rect(36, 152, 208, 172, 40, 170, 70)
    rect(200, 158, 216, 178, 150, 150, 150)
    rect(232, 148, 302, 220, 232, 200, 40)

    return encodePng(w, h, rgb)
}

/** 手写 PNG：签名 + IHDR + IDAT + IEND（真彩色、8 位、无隔行）。 */
private fun encodePng(width: Int, height: Int, rgb: ByteArray): ByteArray {
    val stride = width * 3
    val raw = ByteArray(height * (1 + stride))
    var offset = 0
    repeat(height) { y ->
        raw[offset++] = 0
        System.arraycopy(rgb, y * stride, raw, offset, stride)
        offset += stride
    }

    val deflater = Deflater(Deflater.BEST_COMPRESSION)
    deflater.setInput(raw)
    deflater.finish()
    val compressed = ByteArrayOutputStream().use { out ->
        val buffer = ByteArray(8192)
        while (!deflater.finished()) {
            out.write(buffer, 0, deflater.deflate(buffer))
        }
        deflater.end()
        out.toByteArray()
    }

    val ihdr = ByteBuffer.allocate(13).apply {
        putInt(width)
        putInt(height)
        put(8)
        put(2)
        put(0)
        put(0)
        put(0)
    }.array()

    return ByteArrayOutputStream().use { out ->
        out.write(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A))
        writePngChunk(out, "IHDR", ihdr)
        writePngChunk(out, "IDAT", compressed)
        writePngChunk(out, "IEND", ByteArray(0))
        out.toByteArray()
    }
}

private fun writePngChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
    val typeBytes = type.toByteArray(Charsets.US_ASCII)
    out.write(ByteBuffer.allocate(4).putInt(data.size).array())
    out.write(typeBytes)
    out.write(data)
    val crc = CRC32().apply {
        update(typeBytes)
        update(data)
    }.value
    out.write(ByteBuffer.allocate(4).putInt(crc.toInt()).array())
}
