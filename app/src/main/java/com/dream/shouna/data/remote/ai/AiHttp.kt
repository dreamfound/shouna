package com.dream.shouna.data.remote.ai

/**
 * 网络传输（**接口**）—— 本工程唯一的出网入口，见 `docs/实现约束.md` §7-5「AI 联网的唯一出口」：
 * 出网代码只允许出现在 `data/remote/ai/` 这一个目录内。
 *
 * 为什么抽成接口（降耦合）：① [DeepSeekAiClient] 的**报文组装与响应解析**可用假传输离线单测
 * （不必触网、不受供应商可用性影响）；② 将来若需代理 / 重试策略，只换实现，上层零改动。
 * 生产环境唯一实现 = [UrlConnectionAiHttp]。
 */
interface AiHttp {

    /**
     * 单次 POST JSON。
     *
     * **永不抛异常**（`CancellationException` 除外，协程取消必须透传）：连接失败 / 超时 / 读取
     * 失败一律折叠为 [AiHttpResponse.failure] —— 降级路径只有一条，调用方拿不到 2xx 就回退人工，
     * 不区分异常类型、不做重试（`docs/code-map/02-数据与领域.md`-12）。
     */
    suspend fun postJson(url: String, headers: Map<String, String>, body: String): AiHttpResponse
}

/**
 * 一次 HTTP 往返的结果。
 *
 * **只带状态码与响应体**，不携带请求头 —— 密钥在 `Authorization` 头里，绝不外泄到日志或上层
 * （`docs/code-map/02-数据与领域.md` 硬边界 1）。
 */
data class AiHttpResponse(
    val code: Int,
    val body: String?,
) {
    val isSuccess: Boolean get() = code in SUCCESS_RANGE && body != null

    companion object {
        private val SUCCESS_RANGE = 200..299

        /** 传输层失败的哨兵码：与任何真实 HTTP 状态码都不冲突。 */
        const val CODE_TRANSPORT_FAILURE: Int = -1

        /** 未拿到状态码（连接失败 / 超时 / 证书问题）。 */
        fun failure(): AiHttpResponse = AiHttpResponse(CODE_TRANSPORT_FAILURE, null)
    }
}
