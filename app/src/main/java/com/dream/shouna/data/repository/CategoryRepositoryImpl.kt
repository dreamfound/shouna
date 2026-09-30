package com.dream.shouna.data.repository

import com.dream.shouna.data.local.dao.CategoryDao
import com.dream.shouna.data.local.entity.CategoryEntity
import com.dream.shouna.domain.model.Category
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

/**
 * P0-01：只读常量 → `category` 表（`CategoryDao`）。8 条内置分类由
 * [com.dream.shouna.data.local.SeedCallback] 在建库时写入。
 *
 * **P1-05（FR-44）把本类由只读扩为可写**：[create] / [rename] / [delete] 三个写路径。
 * 删分类的「其下物品转未分类」由 FK `ON DELETE SET NULL` 在 DB 层保证，仓库层不逐条改物品。
 *
 * 被调用方：`QuickAddViewModel` / `ItemEditViewModel`（chips 数据源）、`CategoryManageViewModel`（增删改）
 */
@Singleton
class CategoryRepositoryImpl @Inject constructor(
    private val categoryDao: CategoryDao,
) : CategoryRepository {

    override fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun create(name: String): Category =
        TODO("P1-05 ①: 新建自定义分类（is_built_in = 0，sort_order 排在同级末尾）")

    override suspend fun rename(id: String, name: String): Boolean =
        TODO("P1-05 ②: 改名（内置分类也允许改名；改名不动物品记录）")

    override suspend fun delete(id: String): Boolean =
        TODO("P1-05 ③: 删分类（内置分类拒绝；其下物品经 FK SET NULL 回落「未分类」）")
}

/**
 * 持久化行 → 领域模型。
 *
 * P1-01 起**带出 `isBuiltIn`**（此前丢弃）—— FR-44 需要用它区分「内置可改名不可删」与「自定义可增删改」。
 */
internal fun CategoryEntity.toDomain(): Category = Category(
    id = id,
    name = name,
    isBuiltIn = isBuiltIn,
    sortOrder = sortOrder,
)
