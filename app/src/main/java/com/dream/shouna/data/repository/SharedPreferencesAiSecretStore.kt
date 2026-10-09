package com.dream.shouna.data.repository

import android.content.SharedPreferences
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [AiSecretStore] 的实现：应用私有 `SharedPreferences`（文件名见 [AiSettingsRepository.PREFS_NAME]）。
 *
 * 只落**密文 + IV** 两串 Base64，明文从不落盘（明文只在 [AiKeyCipher.decrypt] 的返回值里、仅在内存
 * 里活到本次调用结束）。`allowBackup="false"` 已定案 ⇒ 该文件不进系统备份。
 *
 * 写用 `commit()` 而非 `apply()`：调用方要据此返回「保存成功与否」，异步写会让返回值变成猜测。
 * 这里只在用户点保存时写一次，主线程代价可忽略。
 */
@Singleton
class SharedPreferencesAiSecretStore @Inject constructor(
    private val preferences: SharedPreferences,
) : AiSecretStore {

    override fun read(): EncryptedSecret? {
        val cipherText = preferences.getString(AiSettingsRepository.PREF_KEY_CIPHER, null) ?: return null
        val iv = preferences.getString(AiSettingsRepository.PREF_KEY_IV, null) ?: return null
        return EncryptedSecret(cipherText = cipherText, iv = iv)
    }

    override fun write(secret: EncryptedSecret) {
        preferences.edit()
            .putString(AiSettingsRepository.PREF_KEY_CIPHER, secret.cipherText)
            .putString(AiSettingsRepository.PREF_KEY_IV, secret.iv)
            .commit()
    }

    override fun clear() {
        preferences.edit()
            .remove(AiSettingsRepository.PREF_KEY_CIPHER)
            .remove(AiSettingsRepository.PREF_KEY_IV)
            .commit()
    }
}
