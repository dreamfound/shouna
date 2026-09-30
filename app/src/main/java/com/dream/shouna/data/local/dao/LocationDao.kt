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

    // --- P1-01 / P1-02（FR-04 / 06 / 21）：`path` 读改与子树范围查询 ------------------------

    /**
     * P1-02（FR-04）：移动单节点 —— 把 `parent_id` 与 `path` **一条语句**改写。
     *
     * 之所以两列同写而不是分两条：`path` 与 `parent_id` 必须恒一致（P1 §3.4-7），
     * 分成两条语句就会存在「父已改、路径未改」的中间态，移动途中被打断即产生脏路径。
     */
    @Query("UPDATE location SET parent_id = :parentId, path = :path WHERE id = :id")
    suspend fun updateParentAndPath(id: String, parentId: String?, path: String): Int

    /** P1-02（FR-04）：子树内逐行重写 `path`（移动后子孙路径前缀整体换掉）。 */
    @Query("UPDATE location SET path = :path WHERE id = :id")
    suspend fun updatePath(id: String, path: String): Int

    /**
     * P1-01 / P1-02：**子树范围查询**（含自身）。
     *
     * 必须写成**区间**（`path >= :prefix AND path < :prefix || char(0xFFFF)`）而不是
     * `LIKE :prefix || '%'` —— 后者是无法静态提取前缀的表达式形态，SQLite 不会走
     * `index_location_path`（`LocationEntity` / `Migrations` 的注释记了同一条实测结论）。
     */
    @Query(
        "SELECT * FROM location WHERE path >= :prefix AND path < :prefix || char(0xFFFF)",
    )
    suspend fun subtreeOf(prefix: String): List<LocationEntity>

    /**
     * P1-02（FR-04）：**子孙**（不含自身）—— 移动子树时按旧前缀逐行重写 `path` 用。
     */
    @Query(
        "SELECT * FROM location WHERE path >= :prefix AND path < :prefix || char(0xFFFF) " +
            "AND id != :excludeId",
    )
    suspend fun descendantsOf(prefix: String, excludeId: String): List<LocationEntity>

    /** P1-02（FR-06）：标记 / 取消临时位置（位置侧操作，不碰任何物品时间戳，P1 §3.5）。 */
    @Query("UPDATE location SET is_temporary = :flag WHERE id = :id")
    suspend fun setTemporary(id: String, flag: Boolean): Int

    /**
     * P1-03（C-1）：**非内置**位置总数 —— 哨兵是系统保留位，不计入「位置总数」
     * （否则用户看到的数字比自己建的多 1，解释不通；§2-9 哨兵不外露）。
     */
    @Query("SELECT COUNT(*) FROM location WHERE is_built_in = 0")
    fun observeUsableCount(): Flow<Int>
}
