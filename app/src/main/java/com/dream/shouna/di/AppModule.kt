package com.dream.shouna.di

import com.dream.shouna.domain.search.SearchConfig
import com.dream.shouna.util.IdGenerator
import com.dream.shouna.util.TimeUtil
import com.dream.shouna.util.UuidIdGenerator
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Qualifier
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** 索引构建 / IO 用的调度器。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** 与应用同生命周期的协程作用域。 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    /** 唯一 id 生成实现：[UuidIdGenerator]（§3.2）。 */
    @Provides
    @Singleton
    fun provideIdGenerator(impl: UuidIdGenerator): IdGenerator = impl

    /** 时间工具：无状态，直接构造（§0 `java.time` + desugaring）。 */
    @Provides
    @Singleton
    fun provideTimeUtil(): TimeUtil = TimeUtil()

    /** 搜索配置：只有 limit 与权重常量，用默认值兜底（§4 F1-04 ④）。 */
    @Provides
    @Singleton
    fun provideSearchConfig(): SearchConfig = SearchConfig()

    /** 索引构建 / IO 调度器（§4 F1-04 ③）。 */
    @Provides
    @Singleton
    @IoDispatcher
    fun provideIoDispatcher(): CoroutineDispatcher = Dispatchers.IO

    /** 与应用同生命周期的作用域：`SupervisorJob` 隔离失败，不因单个子协程异常取消整体。 */
    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(@IoDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
