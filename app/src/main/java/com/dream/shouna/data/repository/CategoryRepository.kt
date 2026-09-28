package com.dream.shouna.data.repository

import com.dream.shouna.domain.model.Category
import kotlinx.coroutines.flow.Flow

/**
 * 分类仓库（ARCHITECTURE §2）：F1 只读，数据源 = 内置 8 条常量。
 */
interface CategoryRepository {
    /** FR-12：录入页 chips 的数据来源。 */
    fun observeCategories(): Flow<List<Category>>
}
