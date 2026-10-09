package com.dream.shouna.data.remote.ai

import com.dream.shouna.domain.ai.AiDraft
import com.dream.shouna.domain.ai.DraftParser
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.serialization.encodeToString

/**
 * [AiClient] 的 DeepSeek 实现 —— 本工程**唯一**知道厂家报文格式的地方。
 *
 * 降耦合设计：端点 / 模型来自注入的 [AiVendor]（`V2-FR-15` 的配置缝）、提示词来自 [AiPrompt]
 * 的分段组装、传输来自 [AiHttp]、解析来自 [DraftParser]。本类只做「组装请求 → 发一次 → 取出正文」。
 *
 * **支持的两个场景**（`V2-FR-11` 的照片通道已放开）：
 * - [AiScene.TEXT_INPUT]：`content` 是字符串。
 * - [AiScene.PHOTO_INPUT]：`content` 是 `[text, image_url]` 数组，图片以 **data URL** 送出。
 * - [AiScene.QUERY] 连功能段文案都还没有（`V2-AI-PRD` §7），在入口处直接返回 null —— 宁可回落
 *   人工录入，也不发出语义残缺的请求（`V2-FR-07`：失败不阻塞主流程）。
 *
 * 失败口径：**非 2xx 一律返 null**，不区分异常类型、不重试（`docs/code-map/02-数据与领域.md`-12）。
 */
@Singleton
class DeepSeekAiClient @Inject constructor(
    private val http: AiHttp,
    private val vendor: AiVendor,
) : AiClient {

    override suspend fun parse(request: AiRequest, apiKey: String): List<AiDraft>? {
        if (apiKey.isBlank()) return null
        if (request.scene == AiScene.QUERY) return null

        // 场景必需项缺失就**不发请求**（省一次必然失败的往返）：文字要话、照片要图。
        val userMessage = buildUserMessage(request) ?: return null

        val chatRequest = ChatRequest(
            model = vendor.model,
            messages = listOf(
                ChatMessage.text(
                    ChatMessage.ROLE_SYSTEM,
                    AiPrompt.system(request.scene, request.categoryNames, request.locationPaths),
                ),
                userMessage,
            ),
            temperature = AiPrompt.TEMPERATURE,
            responseFormat = ResponseFormat.JSON_OBJECT,
        )
        val body = runCatching { AiJson.encodeToString(chatRequest) }.getOrNull() ?: return null

        val response = http.postJson(
            url = vendor.endpoint,
            headers = mapOf(HEADER_AUTHORIZATION to "$BEARER_PREFIX$apiKey"),
            body = body,
        )
        if (!response.isSuccess) return null

        val payload = response.body ?: return null
        val content = runCatching {
            AiJson.decodeFromString<ChatResponse>(payload).contentOrNull()
        }.getOrNull()
        return DraftParser.parse(content, request.categoryNames)
    }

    /** 按场景造 user 消息；返回 null = 本场景的必需内容缺失，不该发这次请求。 */
    private fun buildUserMessage(request: AiRequest): ChatMessage? {
        val text = request.input.trim()
        return if (request.scene == AiScene.PHOTO_INPUT) {
            val image = request.imageBase64?.trim().orEmpty()
            if (image.isEmpty()) return null
            // 用户没额外说话时给一句默认指令 —— 空 content 部分会被供应商拒（400）。
            ChatMessage.textAndImage(
                role = ChatMessage.ROLE_USER,
                text = text.ifEmpty { PHOTO_INSTRUCTION },
                imageDataUrl = IMAGE_DATA_URL_PREFIX + image,
            )
        } else {
            if (text.isEmpty()) return null
            ChatMessage.text(ChatMessage.ROLE_USER, text)
        }
    }

    private companion object {
        const val HEADER_AUTHORIZATION = "Authorization"
        const val BEARER_PREFIX = "Bearer "

        /**
         * data URL 前缀。**格式固定为 JPEG** —— 编码端（`util/AiImageEncoder`）统一压成 JPEG，
         * 因此这里不必猜 MIME。前缀由本层拼（而不是让调用方拼）：`AiRequest.imageBase64` 的契约
         * 是「裸 base64」，拼前缀属报文格式，归本层。
         */
        const val IMAGE_DATA_URL_PREFIX = "data:image/jpeg;base64,"

        /** 照片通道下用户没额外交代时的默认指令（配在 [AiPrompt.SCENE_PHOTO_INPUT] 的功能段之外）。 */
        const val PHOTO_INSTRUCTION = "请识别这张照片里的物品。"
    }
}
