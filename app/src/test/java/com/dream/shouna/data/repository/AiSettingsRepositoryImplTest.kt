package com.dream.shouna.data.repository

import com.dream.shouna.data.local.FakeConfigDao
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.Test

/**
 * AI-01 单测（纯 JVM）：[AiSettingsRepositoryImpl] 的策略口径。
 *
 * 能在这里跑，正是「密钥落点（[AiSecretStore]）与加解密（[AiKeyCipher]）都接口化」的收益
 * ——`SharedPreferences` 与 Android Keystore 两个框架实现留在仪器测试里，策略先在这层钉死。
 */
class AiSettingsRepositoryImplTest {

    private val dao = FakeConfigDao()
    private val store = FakeSecretStore()
    private val cipher = ReversibleCipher(storable = true)
    private val repository = AiSettingsRepositoryImpl(dao, store, cipher)

    @Test
    fun isEnabled_defaultsToFalseIncludingCorruptValues() = runTest {
        assertThat(repository.isEnabled()).isFalse()

        // 脏值一律按「关」：宁可按钮不出现，也不能凭空发请求。
        val dirty = FakeConfigDao(mapOf(AiSettingsRepository.KEY_ENABLED to "yes"))
        assertThat(AiSettingsRepositoryImpl(dirty, FakeSecretStore(), cipher).isEnabled()).isFalse()
    }

    @Test
    fun setEnabled_togglesAndIsObservable() = runTest {
        assertThat(repository.setEnabled(true)).isTrue()
        assertThat(repository.isEnabled()).isTrue()
        assertThat(repository.observeEnabled().first()).isTrue()

        assertThat(repository.setEnabled(false)).isTrue()
        assertThat(repository.observeEnabled().first()).isFalse()
    }

    @Test
    fun setApiKey_storesCipherTextOnlyAndReadsBackPlainText() = runTest {
        assertThat(repository.hasApiKey()).isFalse()

        assertThat(repository.setApiKey("sk-secret")).isTrue()
        assertThat(repository.hasApiKey()).isTrue()
        assertThat(repository.apiKey()).isEqualTo("sk-secret")

        // 落盘的只有密文 + IV，明文不落库、不进 app_config。
        assertThat(store.stored?.cipherText).isEqualTo("enc:sk-secret")
        assertThat(dao.snapshot()).isEmpty()
    }

    @Test
    fun setApiKey_blankOrNull_clearsStoredSecret() = runTest {
        repository.setApiKey("sk-secret")

        assertThat(repository.setApiKey(null)).isTrue()
        assertThat(repository.hasApiKey()).isFalse()
        assertThat(store.stored).isNull()
    }

    @Test
    fun setApiKey_whenCipherUnavailable_returnsFalseAndStoresNothing() = runTest {
        val failing = AiSettingsRepositoryImpl(dao, store, ReversibleCipher(storable = false))

        assertThat(failing.setApiKey("sk-secret")).isFalse()
        assertThat(store.stored).isNull()
    }

    @Test
    fun apiKey_whenDecryptionFails_isTreatedAsNotConfigured() = runTest {
        repository.setApiKey("sk-secret")
        // 换机 / 系统清除 Keystore 后旧密文解不开：不崩溃，按「未配置」处理。
        val broken = AiSettingsRepositoryImpl(dao, store, ReversibleCipher(storable = true, readable = false))

        assertThat(broken.apiKey()).isNull()
        assertThat(broken.hasApiKey()).isFalse()
    }

    @Test
    fun countUsage_accumulatesAndIsObservable() = runTest {
        assertThat(repository.observeUsageCount().first()).isEqualTo(0)

        repeat(3) { repository.countUsage() }

        assertThat(repository.observeUsageCount().first()).isEqualTo(3)
        assertThat(dao.snapshot()[AiSettingsRepository.KEY_USAGE_COUNT]).isEqualTo("3")
    }

    @Test
    fun disablingSwitch_keepsStoredKey() = runTest {
        repository.setApiKey("sk-secret")
        repository.setEnabled(true)
        repository.setEnabled(false)

        // 关闭开关不删密钥：再开启时不必重填（不增加使用成本）。
        assertThat(repository.apiKey()).isEqualTo("sk-secret")
    }

    /** 假落点：密文存内存。 */
    private class FakeSecretStore : AiSecretStore {
        var stored: EncryptedSecret? = null

        override fun read(): EncryptedSecret? = stored
        override fun write(secret: EncryptedSecret) {
            stored = secret
        }

        override fun clear() {
            stored = null
        }
    }

    /** 可逆的假加解密：`enc:` 前缀即「密文」，用于断言明文不落盘。 */
    private class ReversibleCipher(
        private val storable: Boolean,
        private val readable: Boolean = true,
    ) : AiKeyCipher {
        override fun encrypt(plain: String): EncryptedSecret? =
            if (storable) EncryptedSecret(cipherText = "enc:$plain", iv = "iv") else null

        override fun decrypt(secret: EncryptedSecret): String? =
            if (readable) secret.cipherText.removePrefix("enc:") else null
    }
}
