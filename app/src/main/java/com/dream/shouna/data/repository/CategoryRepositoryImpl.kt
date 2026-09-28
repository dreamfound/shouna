package com.dream.shouna.data.repository

import com.dream.shouna.data.memory.BuiltInData
import com.dream.shouna.domain.model.Category
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * F1 只读实现：直接暴露 [BuiltInData.BUILT_IN_CATEGORIES]，无落盘、无分类管理写入路径。
 */
@Singleton
class CategoryRepositoryImpl @Inject constructor() : CategoryRepository {
    override fun observeCategories(): Flow<List<Category>> =
        // 只读常量，无写入路径（§3.3）：每次订阅拿到同一份内置 8 条。
        flowOf(BuiltInData.BUILT_IN_CATEGORIES)
}
