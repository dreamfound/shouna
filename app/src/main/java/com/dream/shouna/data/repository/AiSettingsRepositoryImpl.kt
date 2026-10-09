package com.dream.shouna.data.repository

import com.dream.shouna.data.local.dao.ConfigDao
import com.dream.shouna.data.local.entity.AppConfigEntity
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * [AiSettingsRepository] 实现。
 *
 * 依赖三件**都已接口化**（降耦合）：`ConfigDao`（键值真源）、[AiSecretStore]（密文落点）、
 * [AiKeyCipher]（加解密）。因此本类的策略可在 JVM 单测全量覆盖，Android 框架只留在两个实现里。
 *
 * 记法：开关值域 `"0"` / `"1"`，**缺省与脏值一律按「关」**（读不到就是没开 —— 这是隐私取向上的
 * 安全默认：宁可按钮不出现，也不能凭空发请求）。
 */
@Singleton
class AiSettingsRepositoryImpl @Inject constructor(
    private val configDao: ConfigDao,
    private val secretStore: AiSecretStore,
    private val cipher: AiKeyCipher,
) : AiSettingsRepository {

    /** 用量是「读-改-写」的累加，加锁避免并发调用漏计。 */
    private val usageMutex = Mutex()

    override fun observeEnabled(): Flow<Boolean> =
        configDao.observe(AiSettingsRepository.KEY_ENABLED).map { it.toFlag() }

    override suspend fun isEnabled(): Boolean = configDao.get(AiSettingsRepository.KEY_ENABLED).toFlag()

    override suspend fun setEnabled(enabled: Boolean): Boolean = runCatching {
        configDao.put(
            AppConfigEntity(
                key = AiSettingsRepository.KEY_ENABLED,
                value = if (enabled) FLAG_ON else FLAG_OFF,
            ),
        )
        true
    }.getOrDefault(false)

    override suspend fun apiKey(): String? {
        val stored = secretStore.read() ?: return null
        return cipher.decrypt(stored)?.trim()?.takeIf { it.isNotEmpty() }
    }

    override suspend fun hasApiKey(): Boolean = apiKey() != null

    override suspend fun setApiKey(plain: String?): Boolean {
        val trimmed = plain?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            // 显式清空：与「关闭开关」不同，这是用户要求丢掉密钥。
            secretStore.clear()
            return true
        }
        val encrypted = cipher.encrypt(trimmed) ?: return false
        secretStore.write(encrypted)
        return true
    }

    override fun observeUsageCount(): Flow<Int> =
        configDao.observe(AiSettingsRepository.KEY_USAGE_COUNT).map { it?.toIntOrNull() ?: 0 }

    override suspend fun countUsage() = usageMutex.withLock {
        val next = (configDao.get(AiSettingsRepository.KEY_USAGE_COUNT)?.toIntOrNull() ?: 0) + 1
        configDao.put(
            AppConfigEntity(key = AiSettingsRepository.KEY_USAGE_COUNT, value = next.toString()),
        )
    }

    private fun String?.toFlag(): Boolean = this == FLAG_ON

    private companion object {
        const val FLAG_ON = "1"
        const val FLAG_OFF = "0"
    }
}
