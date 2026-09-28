package com.dream.shouna.di

import com.dream.shouna.data.repository.CategoryRepository
import com.dream.shouna.data.repository.CategoryRepositoryImpl
import com.dream.shouna.data.repository.ItemRepository
import com.dream.shouna.data.repository.ItemRepositoryImpl
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
}
