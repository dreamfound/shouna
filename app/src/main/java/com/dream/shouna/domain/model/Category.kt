package com.dream.shouna.domain.model

/**
 * 分类。F1 为 8 条只读内置常量（[com.dream.shouna.data.memory.BuiltInData.BUILT_IN_CATEGORIES]），
 * 无分类管理页（ARCHITECTURE §3.3 / §1.3）。
 */
data class Category(
    val id: String,
    val name: String,
    /** chips 排列顺序。 */
    val sortOrder: Int = 0,
)
