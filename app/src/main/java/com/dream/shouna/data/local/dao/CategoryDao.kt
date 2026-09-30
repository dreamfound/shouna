package com.dream.shouna.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dream.shouna.data.local.entity.CategoryEntity
import kotlinx.coroutines.flow.Flow

/**
 * `category` 表读写（ARCHITECTURE-P0 §3.1）：8 条内置由种子写入；
 * **P1-05（FR-44）由只读扩为可写** —— 增删改三个写路径。
 *
 * 内置（`is_built_in = 1`）**可改名、不可删**（`prd/08` §7.4）：
 * 改名语句不设 `is_built_in` 条件（内置也能改），删除语句**在 SQL 层就带 `is_built_in = 0`**
 * —— 让「内置不可删」由数据库而不是调用方的记性保证。
 */
@Dao
interface CategoryDao {

    @Query("SELECT * FROM category ORDER BY sort_order ASC")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(items: List<CategoryEntity>)

    @Query("SELECT COUNT(*) FROM category")
    suspend fun count(): Int

    // --- P1-05（FR-44）：分类管理 ------------------------------------------------

    /** 新建自定义分类（单条）。 */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: CategoryEntity)

    /** 当前最大 `sort_order`；空表回 0，供新分类排到末尾。 */
    @Query("SELECT COALESCE(MAX(sort_order), 0) FROM category")
    suspend fun maxSortOrder(): Int

    /** 改名。**不设内置条件** —— 内置分类也允许改名（`prd/08` §7.4）。 */
    @Query("UPDATE category SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String): Int

    /**
     * 删除**自定义**分类；内置分类被 SQL 条件挡下（受影响行数 0），调用方据此回报失败。
     * 其下物品经 `item.category_id` 的 FK `ON DELETE SET NULL` 回落「未分类」，物品不删。
     */
    @Query("DELETE FROM category WHERE id = :id AND is_built_in = 0")
    suspend fun deleteCustom(id: String): Int
}
