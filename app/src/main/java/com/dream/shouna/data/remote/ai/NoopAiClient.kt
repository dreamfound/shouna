package com.dream.shouna.data.remote.ai

import com.dream.shouna.domain.ai.AiDraft
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 「未配置」态的替身：恒返 null、**不触网**。
 *
 * ⚠️ 与 `docs/code-map/02-数据与领域.md` 的一处偏差（降耦合，2026-10-08 实现时定）：文档写「按是否
 * 已配置绑定 `DeepSeekAiClient` / `NoopAiClient`」——DI 图在**构建期**读不到 `suspend` 配置，
 * 动态绑定不可实现。改为**静态绑定 DeepSeek**，放行与否交由闸门
 * [com.dream.shouna.data.repository.AiGate] 逐次判定（开关 → 密钥 → 缓存 → 调用）。
 * 本类因此只作单测替身与将来「按配置切实现」的现成落点。
 */
@Singleton
class NoopAiClient @Inject constructor() : AiClient {

    override suspend fun parse(
        request: AiRequest,
        apiKey: String,
        onUsage: (Int) -> Unit,
    ): List<AiDraft>? = null
}
