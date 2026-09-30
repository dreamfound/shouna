package com.dream.shouna.data.repository

import com.dream.shouna.domain.model.Category
import kotlinx.coroutines.flow.Flow

/**
 * 分类仓库（ARCHITECTURE §2）：P0 只读（数据源 = `category` 表，8 条内置由种子写入）；
 * **P1-05 由只读扩为可写**（FR-44 分类管理）。
 *
 * 内置 / 自定义的判据是 [Category.isBuiltIn]（读 `category.is_built_in`），
 * **内置分类可改名不可删**（`prd/08` §7.4）。
 */
interface CategoryRepository {

    /** FR-12：录入页 chips 的数据来源（也供分类管理页与物品编辑页消费）。 */
    fun observeCategories(): Flow<List<Category>>

    /** FR-44：新建自定义分类（`is_built_in = 0`，排在同级末尾）。 */
    suspend fun create(name: String): Category

    /** FR-44：改名。内置分类也允许改名；返回是否命中。 */
    suspend fun rename(id: String, name: String): Boolean

    /**
     * FR-44：删除分类。其下物品经 FK `ON DELETE SET NULL` 回落「未分类」，**物品不删**。
     * 内置分类不可删，返回 false。
     */
    suspend fun delete(id: String): Boolean
}
