package com.dream.shouna.data.remote.ai

import com.dream.shouna.di.IoDispatcher
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

/**
 * [AiHttp] 的唯一生产实现：`HttpURLConnection`（`docs/code-map/02-数据与领域.md`）。
 *
 * 为什么不用 OkHttp / Retrofit / Ktor：本次只需**一次 POST + 一次 JSON 反序列化**，没有连接池复用、
 * 拦截器、多路复用需求；引入即新依赖，与「零新增依赖」（`实现约束.md` §5-3）冲突。
 * `kotlinx-serialization-json` 已在依赖里（F1-01 引入），直接复用。
 *
 * 超时口径：连接 5s / 读取 **60s**。
 *
 * 【读取超时为什么从 15s 放宽到 60s】（2026-10-08 实测归因，`code-map/05` 口径 31）
 * 非流式响应要等**整个**结果生成完才开始回数据，而实测同一张图的端到端耗时在
 * **4.7 ~ 48.8 秒**之间波动（7 次），且与**推理 token 数**几乎完全线性相关
 * （拟合 `耗时 ≈ 4.72 ms × 推理token`，皮尔逊 r = 0.9974；关推理那次只有 1.1 秒）。
 * 也就是说这段耗时**全部来自模型侧**，参数侧压不下来 —— `effort` 被服务端忽略、
 * `thinking.budget_tokens` 也限制不住、而 `thinking:{type:disabled}` 虽能降到 1 秒却
 * **识别结果全为 null**。15s 在这条实测分布下会**随机失败**（7 次里 5 次触顶），
 * 故按用户 2026-10-08 的决定放宽到 60s。连接超时保持 5s：握手不受模型影响。
 *
 * `minSdk 24` 上 `HttpURLConnection` 默认启用 TLS 1.2；
 * `usesCleartextTraffic` 保持默认（不允许明文）⇒ 端点必须是 HTTPS。
 *
 * **不打印任何东西**：请求头含密钥、响应体可能含用户输入，两者都不进日志（§5 硬边界 1）。
 */
@Singleton
class UrlConnectionAiHttp @Inject constructor(
    // `@param:` 显式指定注解目标：Kotlin 2.2 起构造参数上的注解默认将同时作用于属性，
    // 而这里要的只是「限定符作用在构造参数上」（Dagger 读参数注解），避免将来语义漂移。
    @param:IoDispatcher private val dispatcher: CoroutineDispatcher,
) : AiHttp {

    override suspend fun postJson(
        url: String,
        headers: Map<String, String>,
        body: String,
    ): AiHttpResponse = withContext(dispatcher) {
        var connection: HttpURLConnection? = null
        try {
            connection = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = CONNECT_TIMEOUT_MS
                readTimeout = READ_TIMEOUT_MS
                doOutput = true
                setRequestProperty(HEADER_CONTENT_TYPE, CONTENT_TYPE_JSON)
                setRequestProperty(HEADER_ACCEPT, CONTENT_TYPE_JSON)
                headers.forEach { (name, value) -> setRequestProperty(name, value) }
            }
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = connection.responseCode
            // 非 2xx 的正文在 errorStream 上；两条流都要读干净，否则连接无法复用。
            val stream = if (code in SUCCESS_RANGE) connection.inputStream else connection.errorStream
            AiHttpResponse(code, stream?.use { String(it.readBytes(), Charsets.UTF_8) })
        } catch (cancellation: CancellationException) {
            // 协程取消必须透传，不能被折叠成「调不通」——否则调用方无法区分「用户离开页面」与「网络失败」。
            throw cancellation
        } catch (_: Throwable) {
            AiHttpResponse.failure()
        } finally {
            connection?.disconnect()
        }
    }

    private companion object {
        val SUCCESS_RANGE = 200..299

        const val CONNECT_TIMEOUT_MS = 5_000

        /**
         * 见文件头的「读取超时为什么从 15s 放宽到 60s」。**不要再调小** —— 实测分布的上界
         * 是 48.8s，60s 只留了约 23% 余量。
         */
        const val READ_TIMEOUT_MS = 60_000
        const val HEADER_CONTENT_TYPE = "Content-Type"
        const val HEADER_ACCEPT = "Accept"
        const val CONTENT_TYPE_JSON = "application/json; charset=utf-8"
    }
}
