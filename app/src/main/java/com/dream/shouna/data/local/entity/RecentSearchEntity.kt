package com.dream.shouna.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `recent_search` 表（ARCHITECTURE-P0 §3.1）：FR-23 最近搜索词，**仅搜索页空态**展示。
 *
 * 去重口径 = `normalized_query` 唯一索引（归一化后同词只留一条，取最近时间）；
 * 上限 20 条由 DAO 的 `trim` 维护，不在表上做约束。
 */
@Entity(
    tableName = "recent_search",
    indices = [Index(value = ["normalized_query"], unique = true)],
)
data class RecentSearchEntity(
    @PrimaryKey
    @ColumnInfo(name = "query")
    val query: String,
    /** 归一化检索词，唯一索引的去重键。 */
    @ColumnInfo(name = "normalized_query")
    val normalizedQuery: String,
    /** epoch millis UTC，倒序取最近。 */
    @ColumnInfo(name = "searched_at")
    val searchedAt: Long,
)
