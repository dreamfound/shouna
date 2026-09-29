package com.dream.shouna.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * `category` 表（ARCHITECTURE-P0 §3.1）：8 条内置分类由种子写入，本页只读
 * （分类管理属 P1 的 FR-44，本页无写入路径）。
 */
@Entity(tableName = "category")
data class CategoryEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    /** chips 排列顺序。 */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    /** 内置标记。 */
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean,
)
