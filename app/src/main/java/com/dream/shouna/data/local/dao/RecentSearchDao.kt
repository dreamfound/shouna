package com.dream.shouna.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dream.shouna.data.local.entity.RecentSearchEntity
import kotlinx.coroutines.flow.Flow

/**
 * `recent_search` 表读写（ARCHITECTURE-P0 §3.1）：FR-23 最近搜索词，仅搜索页空态展示。
 *
 * 上限 20 条（[RECENT_SEARCH_LIMIT]）由 [trim] 在每次写入后维护——不在表上做约束，
 * 因为「保留最近 N 条」是策略而非数据完整性要求（改上限时只需改常量）。
 */
@Dao
interface RecentSearchDao {

    @Query("SELECT * FROM recent_search ORDER BY searched_at DESC LIMIT :limit")
    fun observeRecent(limit: Int): Flow<List<RecentSearchEntity>>

    /**
     * 写入／覆盖。`query` 是主键、`normalized_query` 是唯一索引，两者都可能冲突，
     * 故用 REPLACE：归一化同词只留最近一条。
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(item: RecentSearchEntity)

    /** 删掉除最近 `keep` 条之外的全部记录。 */
    @Query(
        "DELETE FROM recent_search WHERE query NOT IN " +
            "(SELECT query FROM recent_search ORDER BY searched_at DESC LIMIT :keep)",
    )
    suspend fun trim(keep: Int)

    @Query("SELECT COUNT(*) FROM recent_search")
    suspend fun count(): Int

    companion object {
        /** 最近搜索词上限（FR-23）。 */
        const val RECENT_SEARCH_LIMIT: Int = 20
    }
}
