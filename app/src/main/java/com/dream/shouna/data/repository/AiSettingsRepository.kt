package com.dream.shouna.data.repository

import kotlinx.coroutines.flow.Flow

/**
 * AI 配置读写（`prd/14` `V2-SEAM-05`、`V2-FR-06` / `V2-FR-08`）：总开关、密钥、本周期用量。
 *
 * **出厂默认关**；关闭开关**不删密钥**（免得每次开启都要重填 —— 守「不增加使用成本」）。
 *
 * 存储分工（**零 DB 变更**，`docs/code-map/02-数据与领域.md`）：
 * - 总开关与用量 → `app_config` 既有 KV（本项目唯一的键值表）；
 * - 密钥密文与 IV → 应用私有 `SharedPreferences`（`EncryptedSharedPreferences` 已废弃，见 [AiKeyCipher]）。
 */
interface AiSettingsRepository {

    /** 总开关的**可观察**形态：开关一关，录入页的 AI 按钮立刻消失（`V2-FR-15`）。 */
    fun observeEnabled(): Flow<Boolean>

    /** 一次读。闸门每次调用都先问它。 */
    suspend fun isEnabled(): Boolean

    suspend fun setEnabled(enabled: Boolean): Boolean

    /**
     * 明文密钥，**仅内存持有**。
     * 未配置 / 解密失败（换机、系统清除密钥）→ null，上层按「未配置」处理。
     */
    suspend fun apiKey(): String?

    /** 已配置：填过密钥且能解出明文。用于决定录入页是否显示 AI 按钮。 */
    suspend fun hasApiKey(): Boolean

    /** 写入 / 更新密钥；传 null 或空白 = 清空。 */
    suspend fun setApiKey(plain: String?): Boolean

    /** 本周期用量（次数）：含真发请求的**成功**调用与**进程内缓存命中**（口径见 [countUsage]）。 */
    fun observeUsageCount(): Flow<Int>

    /**
     * 本周期累计的 token **合计**（`V2-FR-08`，2026-10-09 加）。
     *
     * 只累加供应商**回报了 `usage` 的那些次**；没回报的不凑一个 0（宁缺口径，不放假数）。
     * **含缓存命中的重放** ⇒ 是「AI 产出量」，**高于**供应商账单（见 [countUsage]）。
     */
    fun observeUsageTokens(): Flow<Int>

    /**
     * 记**一次用量**；失败不计（`V2-COST-04`）。
     *
     * 两个调用点、同一口径：①闸门真发了请求且成功；②**命中进程内缓存**（2026-10-09 用户定：命中也要
     * 计 —— 次数 +1，token 按该条**首次**调用回报的量再累加）。故两数都是「AI 产出量」口径、
     * **高于账单**，用于回答「AI 用得多不多」，**不可用于对账**。
     *
     * 次数与 token 在**同一把锁内一并写**（见实现）——两数同源，不会一个 +1 而另一个漏加。
     *
     * @param tokens 该次对应的 token 合计；`null` = 那次没回报 ⇒ 只 +1 次数、token 不动。
     */
    suspend fun countUsage(tokens: Int? = null)

    companion object {
        /** 总开关。`app_config` 既有 KV；值域 `"0"` / `"1"`，缺省 = 关。 */
        const val KEY_ENABLED: String = "ai_enabled"

        /** 用量计数（次数）。 */
        const val KEY_USAGE_COUNT: String = "ai_usage_count"

        /** 用量计数（token 合计）。与次数同源、同锁累加。 */
        const val KEY_USAGE_TOKENS: String = "ai_usage_tokens"

        /** 密钥密文的 `SharedPreferences` 文件名与两个键名。 */
        const val PREFS_NAME: String = "ai_secret"
        const val PREF_KEY_CIPHER: String = "api_key_cipher"
        const val PREF_KEY_IV: String = "api_key_iv"
    }
}
