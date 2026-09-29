package com.dream.shouna.data.repository

import com.dream.shouna.data.local.dao.CategoryDao
import com.dream.shouna.data.local.entity.CategoryEntity
import com.dream.shouna.domain.model.Category
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * P0-01：只读常量 → `category` 表（`CategoryDao`）。**接口形态不变**，
 * 8 条内置分类改由 [com.dream.shouna.data.local.SeedCallback] 在建库时写入。
 *
 * 本页仍无分类写入路径（分类管理属 P1 的 FR-44）。
 */
@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
) : CategoryRepository {

    override fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { rows -> rows.map { it.toDomain() } }
}

/** 持久化行 → 领域模型。 */
internal fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    sortOrder = sortOrder,
)
