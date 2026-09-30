package com.dream.shouna.data.repository

import com.dream.shouna.data.local.dao.CategoryDao
import com.dream.shouna.data.local.entity.CategoryEntity
import com.dream.shouna.domain.model.Category
import com.dream.shouna.util.IdGenerator
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
    private val idGenerator: IdGenerator,
) : CategoryRepository {

    override fun observeCategories(): Flow<List<Category>> =
        categoryDao.observeAll().map { rows -> rows.map { it.toDomain() } }

    override suspend fun create(name: String): Category {
        val trimmed = name.trim()
        // 名称是唯一可辨识信息，空白/空串分类在 chips 上是个点不到的东西 —— 仓库层直接拒绝。
        require(trimmed.isNotEmpty()) { "分类名称不能为空" }
        val category = Category(
            id = idGenerator.newId(),
            name = trimmed,
            // 自定义：`is_built_in = 0` ⇒ 可删（FR-44）。
            isBuiltIn = false,
            // 排在同级末尾：chips 的顺序 = sort_order，新建的不应插到内置分类前面。
            sortOrder = categoryDao.maxSortOrder() + 1,
        )
        categoryDao.insert(category.toEntity())
        return category
    }

    override suspend fun rename(id: String, name: String): Boolean {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) return false
        // 内置分类也允许改名（`prd/08` §7.4）；改名不动物品记录（物品只存 category_id）。
        return categoryDao.rename(id, trimmed) > 0
    }

    override suspend fun delete(id: String): Boolean =
        // 「内置不可删」由 SQL 条件兜住（`deleteCustom` 带 `is_built_in = 0`），
        // 调用方不必先查一次 —— 受影响行数 0 即「没删成」。
        categoryDao.deleteCustom(id) > 0
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

/** 领域模型 → 持久化行（P1-05 的写路径用）。 */
internal fun Category.toEntity(): CategoryEntity = CategoryEntity(
    id = id,
    name = name,
    sortOrder = sortOrder,
    isBuiltIn = isBuiltIn,
)
