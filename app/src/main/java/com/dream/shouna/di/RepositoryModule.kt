package com.dream.shouna.di

import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.data.repository.CategoryRepositoryImpl
import com.dream.shouna.data.repository.ConfigRepository
import com.dream.shouna.data.repository.ConfigRepositoryImpl
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.ItemRepositoryImpl
import com.dream.shouna.data.repository.LocationRepository
import com.dream.shouna.data.repository.LocationRepositoryImpl
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {
    @Binds
    @Singleton
    abstract fun bindItemRepository(impl: ItemRepositoryImpl): ItemRepository

    @Binds
    @Singleton
    abstract fun bindCategoryRepository(impl: CategoryRepositoryImpl): CategoryRepository

    /** P0-02：位置树仓库（本页新增）。 */
    @Binds
    @Singleton
    abstract fun bindLocationRepository(impl: LocationRepositoryImpl): LocationRepository

    /** P0-03：配置只读（FR-27 超期阈值）。 */
    @Binds
    @Singleton
    abstract fun bindConfigRepository(impl: ConfigRepositoryImpl): ConfigRepository
}
