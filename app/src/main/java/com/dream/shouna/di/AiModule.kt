package com.dream.shouna.di

import android.content.Context
import android.content.SharedPreferences
import com.dream.shouna.data.remote.ai.AiClient
import com.dream.shouna.data.remote.ai.AiHttp
import com.dream.shouna.data.remote.ai.AiVendor
import com.dream.shouna.data.remote.ai.DeepSeekAiClient
import com.dream.shouna.data.remote.ai.UrlConnectionAiHttp
import com.dream.shouna.data.repository.AiKeyCipher
import com.dream.shouna.data.repository.AiSecretStore
import com.dream.shouna.data.repository.AiSettingsRepository
import com.dream.shouna.data.repository.AiSettingsRepositoryImpl
import com.dream.shouna.data.repository.AndroidKeystoreKeyCipher
import com.dream.shouna.data.repository.SharedPreferencesAiSecretStore
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * AI 装配（`docs/code-map/02-数据与领域.md`）。
 *
 * **接口绑定的意义（降耦合）**：上层只认 [AiClient] / [AiSettingsRepository] / [AiHttp] /
 * [AiSecretStore] / [AiKeyCipher] 五个接口 —— 换传输实现、换厂家、换密钥存储都不动调用方，
 * 且每个实现都能在单测里被假替身替换。
 *
 * ⚠️ 与 `docs/code-map/02-数据与领域.md` 的偏差：文档写「按是否已配置绑定 `DeepSeekAiClient` /
 * `NoopAiClient`」，但 DI 图在构建期读不到 `suspend` 配置 ⇒ 改为**静态绑定 DeepSeek**，
 * 逐次放行判定全部归 [com.dream.shouna.data.repository.AiGate]（`NoopAiClient` 留给单测）。
 */
@Module
@InstallIn(SingletonComponent::class)
abstract class AiModule {

    @Binds
    @Singleton
    abstract fun bindAiHttp(impl: UrlConnectionAiHttp): AiHttp

    @Binds
    @Singleton
    abstract fun bindAiClient(impl: DeepSeekAiClient): AiClient

    @Binds
    @Singleton
    abstract fun bindAiSettingsRepository(impl: AiSettingsRepositoryImpl): AiSettingsRepository

    @Binds
    @Singleton
    abstract fun bindAiSecretStore(impl: SharedPreferencesAiSecretStore): AiSecretStore

    @Binds
    @Singleton
    abstract fun bindAiKeyCipher(impl: AndroidKeystoreKeyCipher): AiKeyCipher

    companion object {
        /**
         * 厂家 / 模型（`V2-FR-15` 落地前为常量，见 [AiVendor]）。
         * 那一天要做的只有一处：把本方法的返回值改成「读设置页配置」。
         */
        @Provides
        @Singleton
        fun provideAiVendor(): AiVendor = AiVendor.DEFAULT

        /** 密钥密文与 IV 的落点：应用私有、`allowBackup=false` ⇒ 不进备份。 */
        @Provides
        @Singleton
        fun provideAiPreferences(@ApplicationContext context: Context): SharedPreferences =
            context.getSharedPreferences(AiSettingsRepository.PREFS_NAME, Context.MODE_PRIVATE)
    }
}
