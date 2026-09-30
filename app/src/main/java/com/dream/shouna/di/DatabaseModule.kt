package com.dream.shouna.di

import android.content.Context
import androidx.room.Room
import com.dream.shouna.data.local.Migrations
import com.dream.shouna.data.local.RoomTransactionRunner
import com.dream.shouna.data.local.SeedCallback
import com.dream.shouna.data.local.ShounaDatabase
import com.dream.shouna.data.local.TransactionRunner
import com.dream.shouna.data.local.dao.CategoryDao
import com.dream.shouna.data.local.dao.ConfigDao
import com.dream.shouna.data.local.dao.ItemDao
import com.dream.shouna.data.local.dao.LocationDao
import com.dream.shouna.data.local.dao.RecentSearchDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * 持久化装配（ARCHITECTURE-P0 §2 + P1 §3.2）：唯一 `ShounaDatabase` 实例 + 5 个 DAO。
 *
 * `addCallback(SeedCallback())` 只在**数据库首次创建**时触发（`onCreate`），
 * 已存在的库不会重复写入种子。
 *
 * `addMigrations(Migrations.MIGRATION_1_2)` 是 P1-01 新增的迁移挂载点：存量库（`version = 1`）
 * 升级时加 `location.path` 并回填。**不调用** `fallbackToDestructiveMigration()` ——
 * 那会让升级用户数据全丢（P1 §8.1-1）。
 */
@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideShounaDatabase(@ApplicationContext context: Context): ShounaDatabase =
        Room.databaseBuilder(
            context = context,
            klass = ShounaDatabase::class.java,
            name = ShounaDatabase.NAME,
        )
            .addCallback(SeedCallback())
            .addMigrations(Migrations.MIGRATION_1_2)
            .build()

    @Provides
    @Singleton
    fun provideItemDao(database: ShounaDatabase): ItemDao = database.itemDao()

    @Provides
    @Singleton
    fun provideLocationDao(database: ShounaDatabase): LocationDao = database.locationDao()

    @Provides
    @Singleton
    fun provideCategoryDao(database: ShounaDatabase): CategoryDao = database.categoryDao()

    @Provides
    @Singleton
    fun provideRecentSearchDao(database: ShounaDatabase): RecentSearchDao = database.recentSearchDao()

    @Provides
    @Singleton
    fun provideConfigDao(database: ShounaDatabase): ConfigDao = database.configDao()

    /** 跨表写的事务边界（P0-02 的删除子树、迁移物品都需要原子）。 */
    @Provides
    @Singleton
    fun provideTransactionRunner(database: ShounaDatabase): TransactionRunner =
        RoomTransactionRunner(database)
}
