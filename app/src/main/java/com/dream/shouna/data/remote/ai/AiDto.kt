package com.dream.shouna.data.remote.ai

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * 报文序列化配置（`docs/code-map/02-数据与领域.md`）。
 *
 * `ignoreUnknownKeys = true`：供应商往响应里加字段（`logprobs` / 新参数）不影响本工程；
 * **`usage` 是例外** —— 自 2026-10-09 起本工程读它的 `total_tokens`（设置页的消耗显示）；
 * `isLenient = true`：个别网关会回不规范 JSON，宽松一点少一次无谓失败。
 * 不设 `explicitNulls`（默认即「不省略默认值」的组合下不会多写 null 字段）——请求体的可空项一律
 * **不传**，避免把 `"temperature": null` 这种字段丢给服务端。
 */
val AiJson: Json = Json {
    ignoreUnknownKeys = true
    isLenient = true
}

/**
 * 请求侧的对话消息。**`content` 是可变的 JSON 值，不是字符串**（`V2-FR-11` 的多模态）。
 *
 * 【为什么不能省这一层】OpenAI 兼容协议里，纯文本消息的 `content` 是字符串，而图文消息的
 * `content` 必须是**数组**（`[{"type":"text",…},{"type":"image_url",…}]`）。用 `String` 装不下数组，
 * 用两个不同的类又会把「同样的消息」在请求侧裂成两套。故此处用 [JsonElement]：
 * [text] 造纯文本、[textAndImage] 造图文，序列化后天然是各自正确的形态。
 *
 * ⚠️ 响应侧的正文**仍是字符串**，用 [ResponseMessage] 接 —— 不用本类，免得每次取值都要判类型。
 */
@Serializable
data class ChatMessage(val role: String, val content: JsonElement) {
    companion object {
        const val ROLE_SYSTEM = "system"
        const val ROLE_USER = "user"

        /** 纯文本消息（文字 / 语音通道，以及 system 段）。 */
        fun text(role: String, text: String): ChatMessage =
            ChatMessage(role, JsonPrimitive(text))

        /**
         * 图文消息（照片通道）。[imageDataUrl] 须是完整的 data URL
         * （`data:image/jpeg;base64,…`）——供应商按 data URL 解析，只给裸 base64 会被拒。
         */
        fun textAndImage(role: String, text: String, imageDataUrl: String): ChatMessage =
            ChatMessage(
                role,
                buildJsonArray {
                    add(
                        buildJsonObject {
                            put(KEY_TYPE, TYPE_TEXT)
                            put(KEY_TEXT, text)
                        },
                    )
                    add(
                        buildJsonObject {
                            put(KEY_TYPE, TYPE_IMAGE_URL)
                            put(KEY_IMAGE_URL, buildJsonObject { put(KEY_URL, imageDataUrl) })
                        },
                    )
                },
            )

        // 【2026-10-10 停用 · 注释保留】音频消息（`input_audio` 报文形态）—— 平台不受理音频输入
        // （`content` part 只认 `text` / `image_url` / `file`），随整条录音链路一并注释。
        // 恢复时去掉下面各行行首的 `// ` 即可：
        //
        // /**
        //  * 音频消息（语音录音通道，`V2-FR-10` 的录音形态；2026-10-10 新增）。
        //  *
        //  * 【与 [textAndImage] 的关键差别：音频**不带** `data:` 前缀】图片那条走 `image_url.url`
        //  * 且必须是完整 data URL；音频这条走 `input_audio.input_audio.data`，供应商要的是**裸 base64**
        //  * ——加了前缀反而取不出音频。两条形态由供应商定，别互相照抄。
        //  *
        //  * 【`format` 为什么是个参数】目前只有 `wav` 一个取值（Android 无 mp3 编码器，见
        //  * `data/local/audio/PcmWavRecorder`），但它是**报文里的一等字段**，写死在这一行会让
        //  * 「将来换压缩格式」变成「改报文构造」；留成参数则只需在 `DeepSeekAiClient` 换一个字面量。
        //  */
        // fun textAndAudio(
        //     role: String,
        //     text: String,
        //     audioBase64: String,
        //     format: String = AUDIO_FORMAT_WAV,
        // ): ChatMessage =
        //     ChatMessage(
        //         role,
        //         buildJsonArray {
        //             add(
        //                 buildJsonObject {
        //                     put(KEY_TYPE, TYPE_TEXT)
        //                     put(KEY_TEXT, text)
        //                 },
        //             )
        //             add(
        //                 buildJsonObject {
        //                     put(KEY_TYPE, TYPE_INPUT_AUDIO)
        //                     put(
        //                         KEY_INPUT_AUDIO,
        //                         buildJsonObject {
        //                             put(KEY_DATA, audioBase64)
        //                             put(KEY_FORMAT, format)
        //                         },
        //                     )
        //                 },
        //             )
        //         },
        //     )

        private const val KEY_TYPE = "type"
        private const val TYPE_TEXT = "text"
        private const val TYPE_IMAGE_URL = "image_url"
        private const val KEY_TEXT = "text"
        private const val KEY_IMAGE_URL = "image_url"
        private const val KEY_URL = "url"

        // 【2026-10-10 停用 · 注释保留】音频内容（`input_audio`）的类型标记、容器键与格式取值：
        //     private const val TYPE_INPUT_AUDIO = "input_audio"
        //     private const val KEY_INPUT_AUDIO = "input_audio"
        //     private const val KEY_DATA = "data"
        //     private const val KEY_FORMAT = "format"
        //     const val AUDIO_FORMAT_WAV = "wav"   // WAV：编码端自写 44 字节头，见 `PcmWavRecorder`
    }
}

/**
 * 请求体（只覆盖本工程用得到的字段）。
 * [responseFormat] 传 [ResponseFormat.JSON_OBJECT] 时服务端被约束为「只输出 JSON 对象」。
 */
@Serializable
data class ChatRequest(
    val model: String,
    val messages: List<ChatMessage>,
    val temperature: Double? = null,
    @SerialName("response_format") val responseFormat: ResponseFormat? = null,
)

/** 输出格式约束。 */
@Serializable
data class ResponseFormat(val type: String) {
    companion object {
        val JSON_OBJECT = ResponseFormat("json_object")
    }
}

/** 响应体（只取首个候选的正文 + 用量）。 */
@Serializable
data class ChatResponse(
    val choices: List<ChatChoice> = emptyList(),
    val usage: TokenUsage? = null,
) {
    /** 首个候选的正文；被拒答 / 空响应 → null。 */
    fun contentOrNull(): String? =
        choices.firstOrNull()?.message?.content?.takeIf { it.isNotBlank() }
}

/**
 * 用量（设置页的消耗显示，`V2-FR-08`）。
 *
 * **只取合计**（用户 2026-10-09 定：只要「共消耗」）：`prompt_tokens` / `completion_tokens` 被
 * `ignoreUnknownKeys` 忽略掉 —— 多的字段不留，省得将来有人拿它算出与账单对不上的数。
 * 供应商没回 `usage` ⇒ 整个对象为 null ⇒ 调用方**只计次数，不凑一个 0**。
 */
@Serializable
data class TokenUsage(@SerialName("total_tokens") val totalTokens: Int = 0)

/** 响应候选。 */
@Serializable
data class ChatChoice(
    val message: ResponseMessage? = null,
    @SerialName("finish_reason") val finishReason: String? = null,
)

/**
 * 响应侧的消息 —— **与请求侧的 [ChatMessage] 刻意分开**：这里 [content] 恒为字符串
 * （供应商只回文本），用不着 [JsonElement]，取值也就不必判类型。
 */
@Serializable
data class ResponseMessage(
    val role: String? = null,
    val content: String? = null,
)
