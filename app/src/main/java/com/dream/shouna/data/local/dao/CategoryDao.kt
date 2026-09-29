package com.dream.shouna.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dream.shouna.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * `category` 表读写（ARCHITECTURE-P0 §3.1）：本页**只读**（8 条内置由种子写入），
 * 分类管理属 P1 的 FR-44。
 */
@Dao
interface CategoryDao {

    @Query("SELECT * FROM category ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CategoryEntity>)

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int
}
