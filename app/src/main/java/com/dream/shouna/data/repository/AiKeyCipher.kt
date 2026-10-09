package com.dream.shouna.data.repository

/** 密文 + IV：两者都是 Base64 文本，正是落 `SharedPreferences` 的形态。 */
data class EncryptedSecret(val cipherText: String, val iv: String)

/**
 * API Key 的对称加解密（**接口**）。
 *
 * 为什么接口化（降耦合）：把「用平台 Keystore」这一**实现细节**从密钥仓库的策略里摘出来 ——
 * ① [AiSettingsRepositoryImpl] 的策略（存 / 取 / 清除、解密失败按未配置处理）因此可在 JVM 单测
 * 用假替身覆盖，而 Keystore 本身只能在真机 / 模拟器的仪器测试里验；② 换算法或换存储方案时
 * 只改实现。
 *
 * **三条硬边界**（`docs/code-map/02-数据与领域.md`）：明文不进日志、不进崩溃上报、不上传；
 * 解密失败按「未配置」处理（不崩溃、不弹窗）；开关关闭**不删密钥**。
 */
interface AiKeyCipher {
    /** 加密失败（Keystore 不可用）→ null，调用方据此判定「没能保存」。 */
    fun encrypt(plain: String): EncryptedSecret?

    /** 解密失败（系统清除密钥、换机后 Keystore 失效）→ null，上层按「未配置」处理。 */
    fun decrypt(secret: EncryptedSecret): String?
}
