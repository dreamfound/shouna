package com.dream.shouna.data.local.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dream.shouna.data.local.entity.LocationEntity
import kotlinx.coroutines.flow.Flow

/**
 * `location` 表读写（ARCHITECTURE-P0 §2）。
 *
 * `parent_id IS :parentId` 用 SQLite 的 `IS` 运算符，**对 NULL 也成立**（`= NULL` 恒假），
 * 这样同一份查询同时服务「取根级（null）」与「取某节点子级」。
 */
@Dao
interface LocationDao {

    @Query("SELECT * FROM location ORDER BY sort_order ASC, name ASC")
    fun observeAll(): Flow<List<LocationEntity>>

    @Query("SELECT * FROM location WHERE parent_id IS :parentId ORDER BY sort_order ASC, name ASC")
    fun observeChildren(parentId: String?): Flow<List<LocationEntity>>

    @Query("SELECT * FROM location WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): LocationEntity?

    /** 直属子级（删除子树时自底向上用）。 */
    @Query("SELECT * FROM location WHERE parent_id = :parentId")
    suspend fun childrenOf(parentId: String): List<LocationEntity>

    /** 同级列表（含根级：`parent_id` 为 NULL 时 `IS` 仍成立），用于分配新的 `sort_order`。 */
    @Query("SELECT * FROM location WHERE parent_id IS :parentId")
    suspend fun siblings(parentId: String?): List<LocationEntity>

    /** 全部非内置位置（构建实体树用）。 */
    @Query("SELECT * FROM location WHERE is_built_in = 0")
    suspend fun allUsable(): List<LocationEntity>

    /**
     * FR-11：最近使用的位置（**排除内置哨兵**——它不可被用户选中）。
     */
    @Query(
        "SELECT * FROM location WHERE is_built_in = 0 AND id != :excludeId " +
            "AND last_used_at IS NOT NULL ORDER BY last_used_at DESC LIMIT :limit",
    )
    suspend fun recentUsed(excludeId: String, limit: Int): List<LocationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(location: LocationEntity)

    /** FR-03：改名。路径展示由父链实时拼装，故改名**不动物品记录**（§1 FR-03 落地形态）。 */
    @Query("UPDATE location SET name = :name WHERE id = :id")
    suspend fun rename(id: String, name: String): Int

    /** FR-03：备注。 */
    @Query("UPDATE location SET note = :note WHERE id = :id")
    suspend fun setNote(id: String, note: String?): Int

    /** FR-11 / FR-23：记录一次「被用」。 */
    @Query("UPDATE location SET last_used_at = :at WHERE id = :id")
    suspend fun touchLastUsed(id: String, at: Long): Int

    @Query("DELETE FROM location WHERE id = :id")
    suspend fun delete(id: String): Int
}
