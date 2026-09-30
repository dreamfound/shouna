package com.dream.shouna.domain.model

/**
 * 分类。8 条内置由种子写入（[com.dream.shouna.data.memory.BuiltInData.BUILT_IN_CATEGORIES]），
 * 用户自定义分类由 P1-05 的分类管理页增删改。
 *
 * [isBuiltIn] 由 P1-01 起从 `category.is_built_in` 读出（此前 `toDomain` 把它丢掉了）——
 * FR-44 要求列表区分内置 / 自定义，而**内置分类可改名不可删**（`prd/08` §7.4）。
 * 刻意**不给默认值**：默认 true/false 都会让某个构造点悄悄错掉，编译器强制显式声明更安全。
 */
data class Category(
    val id: String,
    val name: String,
    /** 内置（系统预置）标记。 */
    val isBuiltIn: Boolean,
    /** chips 排列顺序。 */
    val sortOrder: Int = 0,
)
