package com.dream.shouna.data.remote.ai

import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * AI 单测（纯 JVM，**不触网**）：[DeepSeekAiClient] 的请求组装与响应折叠。
 *
 * 这就是「传输接口化」换来的可测性 —— 真网络只留给 `AiLiveSmokeTest` 的冒烟用例。
 * 断言五件：密钥进 `Authorization`、**提示词四段按固定顺序拼出**、分类与位置清单进上下文段、
 * `response_format` 被要求为 json_object、**照片通道发的是多模态 content 数组且不附位置段**
 * （`V2-FR-11` / `V2-FR-13`）；以及失败路径（非 2xx / 非 JSON / 空正文 / 空密钥 / 缺必需内容 /
 * 本版不支持的场景）一律返 null 且不发请求。
 */
class DeepSeekAiClientTest {

    private val categories = listOf("电子配件", "杂物")
    private val vendor = AiVendor.DEFAULT

    private fun request(
        input: String,
        scene: AiScene = AiScene.TEXT_INPUT,
        categories: List<String> = emptyList(),
        locations: List<String> = emptyList(),
        image: String? = null,
    ) = AiRequest(
        input = input,
        scene = scene,
        categoryNames = categories,
        locationPaths = locations,
        imageBase64 = image,
    )

    @Test
    fun parse_success_returnsDraftAndSendsExpectedRequest() = runTest {
        val http = RecordingAiHttp(AiHttpResponse(code = 200, body = SUCCESS_BODY))
        val client = DeepSeekAiClient(http, vendor)

        val drafts = client.parse(request("客厅抽屉里的充电器", categories = categories), "sk-test")

        assertThat(drafts).hasSize(1)
        val draft = drafts!!.first()
        assertThat(draft.name).isEqualTo("充电器")
        assertThat(draft.locationPath).containsExactly("客厅", "抽屉").inOrder()
        assertThat(draft.category).isEqualTo("电子配件")
        assertThat(draft.note).isEqualTo("白色")

        assertThat(http.calls).isEqualTo(1)
        assertThat(http.lastUrl).isEqualTo(vendor.endpoint)
        // 密钥只在请求头里，不拼进请求体（不落日志、不进埋点）。
        assertThat(http.lastHeaders["Authorization"]).isEqualTo("Bearer sk-test")
        assertThat(http.lastBody).doesNotContain("sk-test")
        assertThat(http.lastBody).contains(vendor.model)
        assertThat(http.lastBody).contains("""{"type":"json_object"}""")
        assertThat(http.lastBody).contains("电子配件")
    }

    @Test
    fun parse_systemPrompt_keepsStableSectionsBeforeVolatileOnes() = runTest {
        // 段序是**有讲究的**：稳定段（角色 → 功能 → 契约）在前，易变段（分类 → 位置）在后。
        // 供应商按前缀复用提示词，把易变内容插在中间会让它之后的内容一起失去复用资格。
        val http = RecordingAiHttp(AiHttpResponse(200, SUCCESS_BODY))

        DeepSeekAiClient(http, vendor).parse(
            request(
                "客厅抽屉里的充电器",
                categories = categories,
                locations = listOf("家 › 客厅 › 抽屉"),
            ),
            "sk-test",
        )

        val body = http.lastBody!!
        val role = body.indexOf("物品收纳软件的录入助手")
        val scene = body.indexOf("只补充你")
        val schema = body.indexOf("输出结构")
        val category = body.indexOf("现有分类")
        val location = body.indexOf("已有位置")

        assertThat(role).isAtLeast(0)
        assertThat(scene).isGreaterThan(role)
        assertThat(schema).isGreaterThan(scene)
        assertThat(category).isGreaterThan(schema)
        assertThat(location).isGreaterThan(category)
    }

    @Test
    fun parse_systemPrompt_declaresItemsStructure() = runTest {
        // 结构骨架必须写死（2026-10-08 实测：不写死时模型会在「单对象」与「items 数组」之间随机跳，
        // 而下游只认一种就会静默丢数据）。
        val http = RecordingAiHttp(AiHttpResponse(200, SUCCESS_BODY))

        DeepSeekAiClient(http, vendor).parse(request("充电器"), "sk-test")

        val body = http.lastBody!!
        // 断言的是**请求体**（已序列化），故系统提示词里的双引号是 **JSON 转义后的 `\"`** ——
        // 直接找 "items"（真引号）必然落空，这不是提示词没写结构，而是断言没跟上序列化形态。
        assertThat(body).contains("\\\"items\\\"")
        assertThat(body).contains("长度 1 的数组")
    }

    @Test
    fun parse_emptyLists_omitsBothContextSections() = runTest {
        // 两个清单都为空时**整段不出现** —— 而不是输出「（无）」，那会让模型以为有个叫「无」的分类。
        val http = RecordingAiHttp(AiHttpResponse(200, SUCCESS_BODY))

        DeepSeekAiClient(http, vendor).parse(request("充电器"), "sk-test")

        assertThat(http.lastBody).doesNotContain("现有分类")
        assertThat(http.lastBody).doesNotContain("已有位置")
    }

    @Test
    fun parse_photoScene_sendsMultimodalContentAndNoLocationSection() = runTest {
        // 照片通道（V2-FR-11）：content 必须是数组，图片以 data URL 送出；
        // 且**不附位置段**（V2-FR-13「位置先定后用」），契约段另要求 location_path 一律 null。
        val http = RecordingAiHttp(AiHttpResponse(200, PHOTO_BODY))
        val client = DeepSeekAiClient(http, vendor)

        val drafts = client.parse(
            request(
                input = "",
                scene = AiScene.PHOTO_INPUT,
                categories = categories,
                locations = listOf("家 › 客厅"),
                image = "AAAA",
            ),
            "sk-test",
        )

        assertThat(http.calls).isEqualTo(1)
        val body = http.lastBody!!
        assertThat(body).contains("\"image_url\"")
        assertThat(body).contains("data:image/jpeg;base64,AAAA")
        // 用户没额外交代时补一句默认指令（空 content 会被供应商拒）。
        assertThat(body).contains("请识别这张照片里的物品")
        assertThat(body).contains("现有分类")
        assertThat(body).doesNotContain("已有位置")
        assertThat(body).contains("一律填 null")

        // 一张图可识别出多件：解析出列表，不是单份。
        assertThat(drafts).hasSize(2)
        assertThat(drafts!!.map { it.name }).containsExactly("杯子", "书").inOrder()
    }

    @Test
    fun parse_photoSceneWithoutImageOrQueryScene_doesNotCallNetwork() = runTest {
        // 照片场景没给图 → 不发必然失败的请求；QUERY 连功能段文案都没有（V2-AI-PRD §7）。
        val http = RecordingAiHttp(AiHttpResponse(200, SUCCESS_BODY))
        val client = DeepSeekAiClient(http, vendor)

        assertThat(client.parse(request("台灯", scene = AiScene.PHOTO_INPUT), "sk-test")).isNull()
        assertThat(client.parse(request("我的充电器在哪", scene = AiScene.QUERY), "sk-test")).isNull()
        assertThat(http.calls).isEqualTo(0)
    }

    @Test
    fun parse_nonSuccessStatus_returnsNull() = runTest {
        val http = RecordingAiHttp(AiHttpResponse(code = 401, body = "Authentication Fails"))
        val client = DeepSeekAiClient(http, vendor)

        assertThat(client.parse(request("你好", categories = categories), "sk-bad")).isNull()
        assertThat(http.calls).isEqualTo(1)
    }

    @Test
    fun parse_transportFailure_returnsNull() = runTest {
        val client = DeepSeekAiClient(RecordingAiHttp(AiHttpResponse.failure()), vendor)

        assertThat(client.parse(request("你好", categories = categories), "sk-test")).isNull()
    }

    @Test
    fun parse_nonJsonAssistantContent_returnsNull() = runTest {
        val http = RecordingAiHttp(
            AiHttpResponse(200, """{"choices":[{"message":{"role":"assistant","content":"我不太明白"}}]}"""),
        )

        val client = DeepSeekAiClient(http, vendor)
        assertThat(client.parse(request("??", categories = categories), "sk-test")).isNull()
    }

    @Test
    fun parse_emptyChoices_returnsNull() = runTest {
        val http = RecordingAiHttp(AiHttpResponse(200, """{"choices":[]}"""))

        val client = DeepSeekAiClient(http, vendor)
        assertThat(client.parse(request("你好", categories = categories), "sk-test")).isNull()
    }

    @Test
    fun parse_responseWithUnknownFields_isStillParsed() = runTest {
        // 供应商加字段（usage 等）不得影响解析。
        val http = RecordingAiHttp(
            AiHttpResponse(
                200,
                """{"id":"x","usage":{"total_tokens":9},"choices":[{"message":{"role":"assistant","content":"{\"name\":\"雨伞\"}"},"finish_reason":"stop"}]}""",
            ),
        )

        val client = DeepSeekAiClient(http, vendor)
        assertThat(client.parse(request("雨伞", categories = categories), "sk-test")?.first()?.name)
            .isEqualTo("雨伞")
    }

    @Test
    fun parse_blankInputOrKey_doesNotCallNetwork() = runTest {
        val http = RecordingAiHttp(AiHttpResponse(200, "{}"))
        val client = DeepSeekAiClient(http, vendor)

        assertThat(client.parse(request("   ", categories = categories), "sk-test")).isNull()
        assertThat(client.parse(request("你好", categories = categories), "")).isNull()
        assertThat(http.calls).isEqualTo(0)
    }

    @Test
    fun noopClient_isSilentAndReturnsNull() = runTest {
        assertThat(NoopAiClient().parse(request("你好", categories = categories), "sk-test")).isNull()
    }

    /** 记录一次调用的假传输：断言请求、不回真网。 */
    private class RecordingAiHttp(private val response: AiHttpResponse) : AiHttp {
        var calls: Int = 0
        var lastUrl: String? = null
        var lastHeaders: Map<String, String> = emptyMap()
        var lastBody: String? = null

        override suspend fun postJson(url: String, headers: Map<String, String>, body: String): AiHttpResponse {
            calls++
            lastUrl = url
            lastHeaders = headers
            lastBody = body
            return response
        }
    }

    private companion object {
        /** 文字通道：模型回**单个对象**（仍要能解析成长度 1 的列表）。 */
        const val SUCCESS_BODY =
            """{"choices":[{"message":{"role":"assistant","content":"{\"name\":\"充电器\",\"location_path\":[\"客厅\",\"抽屉\"],\"category\":\"电子配件\",\"note\":\"白色\"}"}}]}"""

        /** 照片通道：模型回 `{"items":[…]}`（契约约定的形态）。 */
        const val PHOTO_BODY =
            """{"choices":[{"message":{"role":"assistant","content":"{\"items\":[{\"name\":\"杯子\",\"location_path\":null,\"category\":\"日用\",\"note\":\"红色\"},{\"name\":\"书\",\"location_path\":null,\"category\":\"文具\",\"note\":\"蓝色\"}]}"}}]}"""
    }
}
