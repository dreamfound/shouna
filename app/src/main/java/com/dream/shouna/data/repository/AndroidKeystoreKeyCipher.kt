package com.dream.shouna.data.repository

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AiKeyCipher] 的唯一生产实现：**Android Keystore + AES-256-GCM**（`docs/code-map/02-数据与领域.md`）。
 *
 * 为什么不用 `EncryptedSharedPreferences`：`androidx.security:security-crypto` 的该 API 已随
 * `1.1.0-alpha07`（2025-04）被官方**废弃且无后续版本计划**，建议改用 Keystore + 显式 AES/GCM。
 * 引一个停止维护的加密库，安全性反而不如直接用平台 API —— 且此处是**零新增依赖**。
 *
 * 口径：密钥别名固定、Keystore 中的密钥**不可导出**；`setUserAuthenticationRequired(false)`
 * ——AI 解析是用户点一下按钮就走的路径，不能要求用户先解锁（否则每次都要指纹）。
 * IV 由 `Cipher` 随机生成、随密文一起落盘（GCM 的 IV 不要求保密，但**绝不复用**）。
 *
 * 全部异常折叠为 null：Keystore 不可用（部分定制 ROM / 系统清除密钥）时按「未配置」处理，
 * 不崩溃（§5 硬边界 2）。
 */
@Singleton
class AndroidKeystoreKeyCipher @Inject constructor() : AiKeyCipher {

    override fun encrypt(plain: String): EncryptedSecret? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.ENCRYPT_MODE, loadOrCreateKey())
        val encrypted = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        EncryptedSecret(
            cipherText = Base64.encodeToString(encrypted, Base64.NO_WRAP),
            iv = Base64.encodeToString(cipher.iv, Base64.NO_WRAP),
        )
    }.getOrNull()

    override fun decrypt(secret: EncryptedSecret): String? = runCatching {
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(
            Cipher.DECRYPT_MODE,
            loadOrCreateKey(),
            GCMParameterSpec(TAG_LENGTH_BITS, Base64.decode(secret.iv, Base64.NO_WRAP)),
        )
        String(cipher.doFinal(Base64.decode(secret.cipherText, Base64.NO_WRAP)), Charsets.UTF_8)
    }.getOrNull()

    /** 别名存在即复用（**不重建**，重建等于把已存密文作废）；不存在则生成。 */
    private fun loadOrCreateKey(): SecretKey {
        val keyStore = KeyStore.getInstance(PROVIDER).apply { load(null) }
        (keyStore.getEntry(KEY_ALIAS, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }

        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, PROVIDER)
        generator.init(
            KeyGenParameterSpec.Builder(
                KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
            )
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(KEY_SIZE_BITS)
                .setUserAuthenticationRequired(false)
                .build(),
        )
        return generator.generateKey()
    }

    private companion object {
        const val PROVIDER = "AndroidKeyStore"
        const val KEY_ALIAS = "shouna_ai_api_key"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val TAG_LENGTH_BITS = 128
        const val KEY_SIZE_BITS = 256
    }
}
