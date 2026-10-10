package com.dream.shouna.data.remote.ai

import com.dream.shouna.domain.ai.AiDraft

/**
 * 「一句话 / 一张照片 → 结构化草稿」的**唯一能力接口**（`prd/14` `V2-SEAM-02`）。
 *
 * 入参是 [AiRequest] 值对象：功能场景（[AiScene]）、分类名单、位置清单、照片内容都在其中 ——
 * **新增上下文项不再改签名**（此前 `apiKey` 的追加就已迫使全部替身与测试跟着改一遍）。
 *
 * 为什么 [apiKey] 仍走独立参数、而不是并进请求对象（降耦合 + 安全）：网络层因此**不知道密钥
 * 存在哪** ——读密钥归仓库、传密钥归闸门；同时避免「打印整个 request 就把密钥带出去」。将来换
 * 密钥存储方案（Keystore → DataStore + Tink）本层零改动。
 *
 * 实现**不得抛异常**：未配置 / 非 2xx / JSON 非法 / 该场景暂不支持一律返回 null，调用方只有一条
 * 人工回退路径（`V2-FR-07`：失败不阻塞主流程、不弹窗）。
 */
interface AiClient {

    /**
     * @param request 业务入参（用户输入 / 照片、功能场景、分类名单、位置清单）
     * @param apiKey 用户自备密钥（BYO Key，`V2-COST-05`）
     * @return **多份草稿**（`V2-FR-11`：一张照片可识别出多件物品，故返回列表）；
     *   `null` = 本次调用**没成功**（未配置 / 网络失败 / 响应不可解析）；
     *   **空列表** = 调用成功但**一件都没识别出**。两者都走人工回退，但语义不同，调用方
     *   若要区分「AI 没开」与「AI 没看出东西」，靠的就是这个差别。
     *
     * @param onUsage 供应商回报的 token **合计**（`usage.total_tokens`）；没回报就不调。只在
     *   **真的发出去并拿到响应**的那一次触发 —— 缓存命中根本不经过本方法。用「顺带回报」而不改
     *   返回类型：既有调用方与既有单测**零改动**（2026-10-09，取最简形态）。
     */
    suspend fun parse(
        request: AiRequest,
        apiKey: String,
        onUsage: (Int) -> Unit = {},
    ): List<AiDraft>?
}
