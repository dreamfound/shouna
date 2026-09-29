package com.dream.shouna.data.local.dao

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.dream.shouna.data.local.entity.ItemEntity
import com.dream.shouna.domain.model.ItemStatus
import kotlinx.coroutines.flow.Flow

/**
 * `item` 表读写（ARCHITECTURE-P0 §2「读路径走 Flow、跨表写走单事务」）。
 *
 * 更新方法一律返回受影响行数 `Int`：仓库层据此判断命中与否，**不用「先查再写」**（避免竞态）。
 */
@Dao
interface ItemDao {

    @Query("SELECT * FROM item ORDER BY created_at DESC")
    fun observeAll(): Flow<List<ItemEntity>>

    @Query("SELECT * FROM item WHERE location_id = :locationId ORDER BY created_at DESC")
    fun observeByLocation(locationId: String): Flow<List<ItemEntity>>

    /** P-BROWSE / 位置选择器用：每个位置的**直属活跃**件数（`gone` 不计）。 */
    @Query(
        "SELECT location_id AS locationId, COUNT(*) AS itemCount FROM item " +
            "WHERE status != 'gone' GROUP BY location_id",
    )
    fun observeActiveCounts(): Flow<List<LocationItemCount>>

    @Query("SELECT * FROM item WHERE id = :id LIMIT 1")
    suspend fun findById(id: String): ItemEntity?

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: ItemEntity)

    /** FR-26：只写 `last_confirmed_at`。 */
    @Query("UPDATE item SET last_confirmed_at = :confirmedAt WHERE id = :id")
    suspend fun updateLastConfirmedAt(id: String, confirmedAt: Long): Int

    /** FR-25：写 `status`，并按时间戳矩阵刷新 `last_modified_at`。 */
    @Query("UPDATE item SET status = :status, last_modified_at = :modifiedAt WHERE id = :id")
    suspend fun updateStatus(id: String, status: ItemStatus, modifiedAt: Long): Int

    /** 位置迁移：把某位置的**直属**物品整体搬到目标位置。 */
    @Query("UPDATE item SET location_id = :targetId, last_modified_at = :modifiedAt WHERE location_id = :sourceId")
    suspend fun moveItems(sourceId: String, targetId: String, modifiedAt: Long): Int

    /** 删除位置档 2：直属物品标记 `gone` 并收容到目标位置（`location_id` 非空，必须给去处）。 */
    @Query(
        "UPDATE item SET location_id = :targetId, status = :status, last_modified_at = :modifiedAt " +
            "WHERE location_id = :sourceId",
    )
    suspend fun moveItemsAndSetStatus(
        sourceId: String,
        targetId: String,
        status: ItemStatus,
        modifiedAt: Long,
    ): Int

    /** P-BROWSE 的「直属件数」。递归计数属 P1（§8.2）。 */
    @Query("SELECT COUNT(*) FROM item WHERE location_id = :locationId")
    suspend fun countIn(locationId: String): Int

    @Query("SELECT COUNT(*) FROM item")
    suspend fun count(): Int
}

/** `location_id → 直属活跃件数` 的查询投影（Room 投影类，不入表）。 */
data class LocationItemCount(
    @ColumnInfo(name = "locationId")
    val locationId: String,
    @ColumnInfo(name = "itemCount")
    val itemCount: Int,
)
