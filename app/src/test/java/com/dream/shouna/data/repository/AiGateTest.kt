package com.dream.shouna.data.repository

import com.dream.shouna.data.remote.ai.AiClient
import com.dream.shouna.data.remote.ai.AiRequest
import com.dream.shouna.data.remote.ai.AiScene
import com.dream.shouna.domain.ai.AiDraft
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * AI 单测（纯 JVM，不触网）：唯一闸门的放行判定 + **缓存键口径**。
 *
 * 闸门是「成本可控」与「隐私」共用的那一道口子，因此每条拦截都要钉住：**拦下时必须零调用**。
 * 缓存键单列一组用例 —— 键里漏掉场景、位置清单或**图片**属**静默错误**：结果看着正常，实际张冠李戴。
 * 图片这一项尤其隐蔽：两张不同的照片若文字输入都为空，只按输入建键必然撞键，用户会拿到上一张图的
 * 识别结果。
 */
class AiGateTest {

    private val drafts = listOf(AiDraft(name = "充电器", category = "电子配件"))

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
    fun run_disabledSwitch_doesNotCallClient() = runTest {
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = false, apiKey = "sk-test")

        assertThat(AiGate(client, settings).run(request("客厅抽屉里的充电器"))).isNull()
        assertThat(client.calls).isEqualTo(0)
        assertThat(settings.usage).isEqualTo(0)
    }

    @Test
    fun run_withoutApiKey_doesNotCallClient() = runTest {
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = null)

        assertThat(AiGate(client, settings).run(request("客厅抽屉里的充电器"))).isNull()
        assertThat(client.calls).isEqualTo(0)
    }

    @Test
    fun run_enabledAndConfigured_callsOnceAndCountsUsage() = runTest {
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")

        val result = AiGate(client, settings).run(
            request("客厅抽屉里的充电器", categories = listOf("电子配件")),
        )

        assertThat(result).isEqualTo(drafts)
        assertThat(client.calls).isEqualTo(1)
        assertThat(settings.usage).isEqualTo(1)
        assertThat(client.lastApiKey).isEqualTo("sk-test")
        assertThat(client.lastRequest?.categoryNames).containsExactly("电子配件")
    }

    @Test
    fun run_sameInputTwice_hitsCacheAndBillsOnce() = runTest {
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")
        val gate = AiGate(client, settings)

        assertThat(gate.run(request("充电器", categories = listOf("电子配件", "杂物")))).isEqualTo(drafts)
        assertThat(gate.run(request("充电器", categories = listOf("杂物", "电子配件")))).isEqualTo(drafts)

        // 名单顺序变了也算同一次（键先归一化再排序）。
        assertThat(client.calls).isEqualTo(1)
        assertThat(settings.usage).isEqualTo(1)
    }

    @Test
    fun run_differentInput_callsAgain() = runTest {
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")
        val gate = AiGate(client, settings)

        gate.run(request("充电器"))
        gate.run(request("电风扇"))

        assertThat(client.calls).isEqualTo(2)
        assertThat(settings.usage).isEqualTo(2)
    }

    @Test
    fun run_differentScene_callsAgain() = runTest {
        // 缓存键必须含场景：否则「同一句提示语在另一个功能场景下」会命中上一个场景的结果。
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")
        val gate = AiGate(client, settings)

        gate.run(request("台灯", scene = AiScene.TEXT_INPUT))
        gate.run(request("", scene = AiScene.PHOTO_INPUT, image = "IMG-A"))

        assertThat(client.calls).isEqualTo(2)
    }

    @Test
    fun run_differentLocationPaths_callsAgain() = runTest {
        // 缓存键必须含位置清单：用户换了位置再说同一句话，位置上下文不同，结果可能不同。
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")
        val gate = AiGate(client, settings)

        gate.run(request("充电器", locations = listOf("家 › 客厅 › 抽屉")))
        gate.run(request("充电器", locations = listOf("家 › 卧室 › 床头柜")))

        assertThat(client.calls).isEqualTo(2)
    }

    @Test
    fun run_differentPhotos_callAgain() = runTest {
        // 【本轮新增的关键回归点】两张不同的图，文字输入都为空 ——
        // 键里若不含图片指纹，第二次必然命中第一次的缓存，用户拿到上一张图的识别结果。
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")
        val gate = AiGate(client, settings)

        gate.run(request("", scene = AiScene.PHOTO_INPUT, image = "IMG-A"))
        gate.run(request("", scene = AiScene.PHOTO_INPUT, image = "IMG-B"))

        assertThat(client.calls).isEqualTo(2)
        assertThat(settings.usage).isEqualTo(2)
    }

    @Test
    fun run_samePhotoTwice_hitsCache() = runTest {
        // 反面：**同一张**图重试不该重复计费（用户点两次按钮是常态）。
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")
        val gate = AiGate(client, settings)

        gate.run(request("", scene = AiScene.PHOTO_INPUT, image = "IMG-A"))
        gate.run(request("", scene = AiScene.PHOTO_INPUT, image = "IMG-A"))

        assertThat(client.calls).isEqualTo(1)
    }

    @Test
    fun run_photoWithoutImage_doesNotCallClient() = runTest {
        // 照片场景没有图 = 缺必需内容，拦在闸门（零调用），不浪费一次必然失败的往返。
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")

        assertThat(
            AiGate(client, settings).run(request("", scene = AiScene.PHOTO_INPUT)),
        ).isNull()
        assertThat(client.calls).isEqualTo(0)
    }

    @Test
    fun run_sameListsInDifferentOrder_hitsCache() = runTest {
        // 两个清单都先归一化再排序 ⇒ 顺序不同不算新请求（否则清单顺序一变就重复计费）。
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")
        val gate = AiGate(client, settings)

        gate.run(
            request(
                "充电器",
                categories = listOf("电子配件", "杂物"),
                locations = listOf("家 › 客厅 › 抽屉", "家 › 卧室"),
            ),
        )
        gate.run(
            request(
                "充电器",
                categories = listOf("杂物", "电子配件"),
                locations = listOf("家 › 卧室", "家 › 客厅 › 抽屉"),
            ),
        )

        assertThat(client.calls).isEqualTo(1)
    }

    @Test
    fun run_clientFails_returnsNullAndDoesNotCountUsage() = runTest {
        val client = CountingAiClient(null)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")

        assertThat(AiGate(client, settings).run(request("充电器"))).isNull()
        assertThat(client.calls).isEqualTo(1)
        assertThat(settings.usage).isEqualTo(0)
    }

    @Test
    fun run_clientReturnsEmptyList_isSuccessAndCountsUsage() = runTest {
        // 空列表 = 调用成功但没识别出东西（与 null「没成功」是两件事），照样计一次用量。
        val client = CountingAiClient(emptyList())
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")

        assertThat(AiGate(client, settings).run(request("充电器"))).isEmpty()
        assertThat(settings.usage).isEqualTo(1)
    }

    @Test
    fun run_blankInput_doesNotCallClient() = runTest {
        val client = CountingAiClient(drafts)
        val settings = FakeAiSettings(enabled = true, apiKey = "sk-test")

        assertThat(AiGate(client, settings).run(request("   "))).isNull()
        assertThat(client.calls).isEqualTo(0)
    }

    /** 假 client：只记调用次数与入参，不出网。 */
    private class CountingAiClient(private val result: List<AiDraft>?) : AiClient {
        var calls: Int = 0
        var lastApiKey: String? = null
        var lastRequest: AiRequest? = null

        override suspend fun parse(request: AiRequest, apiKey: String): List<AiDraft>? {
            calls++
            lastApiKey = apiKey
            lastRequest = request
            return result
        }
    }

    /** 假配置：开关 / 密钥 / 用量在内存里。 */
    private class FakeAiSettings(
        var enabled: Boolean,
        var apiKey: String?,
    ) : AiSettingsRepository {
        var usage: Int = 0

        override fun observeEnabled(): Flow<Boolean> = flowOf(enabled)
        override suspend fun isEnabled(): Boolean = enabled
        override suspend fun setEnabled(enabled: Boolean): Boolean {
            this.enabled = enabled
            return true
        }

        override suspend fun apiKey(): String? = apiKey
        override suspend fun hasApiKey(): Boolean = apiKey != null
        override suspend fun setApiKey(plain: String?): Boolean {
            apiKey = plain
            return true
        }

        override fun observeUsageCount(): Flow<Int> = flowOf(usage)
        override suspend fun countUsage() {
            usage++
        }
    }
}
