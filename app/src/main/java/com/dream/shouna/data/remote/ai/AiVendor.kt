package com.dream.shouna.data.remote.ai

/**
 * 厂家配置：**端点 + 模型 + 展示名**，即出网时唯一会变的一组常量。
 *
 * 为什么做成可注入的值对象而不是 `const val`（降耦合）：`docs/V2-AI-PRD.md` 的 `V2-FR-15`
 * 要求「厂家 / 文本模型 / 视觉模型进设置页」，它取代了 `docs/code-map/02-数据与领域.md`-7 的
 * 「厂家与模型硬编码」裁决。做成值对象后，那一天只需改 `di/AiModule` 的**提供方式**
 * （从常量改为读配置），[DeepSeekAiClient] 一行不动。
 */
data class AiVendor(
    /** 对话补全端点（**必须 HTTPS**：`usesCleartextTraffic` 保持默认不允许明文）。 */
    val endpoint: String,
    /** 模型名。 */
    val model: String,
    /** 设置页展示名（`V2-FR-15` 落地后使用）。 */
    val displayName: String,
) {
    companion object {
        val DEEPSEEK = AiVendor(
            endpoint = "https://api.deepseek.com/chat/completions",
            // 取**官方规范模型 ID**（2026-10-08 实测）：`GET /models` 只列 `deepseek-flash`
            // 与 `deepseek-v4-pro`，且非法模型名的 400 报错枚举同于此二者。遗留别名
            // `deepseek-chat` / `deepseek-reasoner` 虽仍被接受，但服务端一律回落到 flash
            // （回执 `model` 字段可证）——属「可用但不受支持」，故直接用规范 ID。
            model = "deepseek-flash",
            displayName = "DeepSeek",
        )

        /** 当前默认厂家（`V2-FR-15` 落地前唯一取值）。 */
        val DEFAULT = DEEPSEEK
    }
}
