package com.dream.shouna.data.repository

/**
 * 密钥密文的落点（**接口**）。
 *
 * 为什么不让仓库直接依赖 `SharedPreferences`（降耦合）：`SharedPreferences` 是 Android 框架类型，
 * 直接依赖会把 [AiSettingsRepositoryImpl] 锁死在仪器测试里；抽出这一层后，仓库的**策略**
 * （存了没 / 解密失败怎么办 / 清空）可以在 JVM 单测用假替身跑完，框架只留在实现里。
 *
 * 实现 = [SharedPreferencesAiSecretStore]（应用私有、`allowBackup=false`，不进备份）。
 */
interface AiSecretStore {
    /** 读取密文与 IV；任一缺失 → null（视为未配置）。 */
    fun read(): EncryptedSecret?

    fun write(secret: EncryptedSecret)

    /** 清除（仅在用户显式清空密钥时使用；**关闭总开关不调它**）。 */
    fun clear()
}
