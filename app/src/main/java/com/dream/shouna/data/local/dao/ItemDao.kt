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

    // --- P1-02 / 03 / 04：批量确认、补丁式更新、统计清单 --------------------------------

    /**
     * P1-04（FR-14 / 别名 / 数量）：**补丁式**更新 —— 4 个字段各带一个「是否本次要改」开关。
     *
     * 为什么用 `CASE WHEN :applyX THEN ... ELSE 原值 END` 而不是「先查再拼 SQL」：
     * ① `category_id` / `note` 本身可空，`COALESCE(:value, column)` 无法区分「不改这一项」
     *    与「置为 NULL（未分类 / 清空备注）」，只有显式开关能表达；
     * ② 一条语句写完 = 单表写，不必引事务（守 `实现约束.md` §2-5、§2-6 的「不先查再写」）。
     *
     * `pinyin_full` / `pinyin_initial` 跟随**别名**开关一起写：别名与名称同档参与检索
     * （`SearchScorer` 的 `NAME` 档），别名一变检索键就得同事务重算（P1 §4 P1-04 ①）。
     * 时间戳两列**都不在本语句内**：改分类 / 别名 / 备注 / 数量一律不刷新 `last_modified_at`
     * （P1 §3.4-10 / §3.5）。
     */
    @Query(
        "UPDATE item SET " +
            "category_id = CASE WHEN :applyCategory THEN :categoryId ELSE category_id END, " +
            "alias_blob = CASE WHEN :applyAliases THEN :aliasBlob ELSE alias_blob END, " +
            "quantity = CASE WHEN :applyQuantity THEN :quantity ELSE quantity END, " +
            "note = CASE WHEN :applyNote THEN :note ELSE note END, " +
            "pinyin_full = CASE WHEN :applyAliases THEN :pinyinFull ELSE pinyin_full END, " +
            "pinyin_initial = CASE WHEN :applyAliases THEN :pinyinInitial ELSE pinyin_initial END " +
            "WHERE id = :id",
    )
    suspend fun updateFields(
        id: String,
        applyCategory: Boolean,
        categoryId: String?,
        applyAliases: Boolean,
        aliasBlob: String,
        applyQuantity: Boolean,
        quantity: Int,
        applyNote: Boolean,
        note: String?,
        pinyinFull: String,
        pinyinInitial: String,
    ): Int

    /**
     * P1-02 ⑥（FR-28）：对某位置**含子层**的全部活跃物品一次性确认。
     *
     * 口径（P1 §8.1-7）：**含子层**、`gone` 不计。只写 `last_confirmed_at`
     * —— 批量确认不是「变动」，不刷 `last_modified_at`（P1 §3.5 矩阵末行）。
     *
     * `prefix` = 目标位置的 ID 序列路径（含自身、前后带 `/`），区间形态见 [LocationDao.subtreeOf]。
     */
    @Query(
        "UPDATE item SET last_confirmed_at = :confirmedAt WHERE status != 'gone' " +
            "AND location_id IN (" +
            "SELECT id FROM location WHERE path >= :prefix AND path < :prefix || char(0xFFFF))",
    )
    suspend fun confirmActiveInSubtree(prefix: String, confirmedAt: Long): Int

    /**
     * P1-03（C-4 / FR-29）：超期未确认清单 = 活跃（非 `gone`）且
     * `last_confirmed_at IS NULL` 或早于 `:thresholdAt`。
     *
     * 排序：`last_confirmed_at ASC` 在 SQLite 下 **NULL 排最前**，正好是「从未确认优先」；
     * 同组内按最久未确认在前 —— 一次 ORDER BY 同时满足两个诉求，不必写 CASE。
     */
    @Query(
        "SELECT * FROM item WHERE status != 'gone' " +
            "AND (last_confirmed_at IS NULL OR last_confirmed_at < :thresholdAt) " +
            "ORDER BY last_confirmed_at ASC",
    )
    fun observeOverdue(thresholdAt: Long): Flow<List<ItemEntity>>

    /**
     * P1-03（C-5 / FR-38）：待归位清单 = **状态为 `to_be_put_back`** ∪ **位于用户标记过的临时位置**。
     *
     * 两部分用一条 `OR` 合并 —— 主键唯一，天然**按物品去重**，不需要 `DISTINCT` 或调用方做集合运算
     * （P1 §8.1-9）。`gone` 不在其中：状态是三选一，`to_be_put_back` 本身即非 `gone`，
     * 临时位置分支再显式排除一次。
     */
    @Query(
        "SELECT * FROM item WHERE status = 'to_be_put_back' " +
            "OR (status != 'gone' AND location_id IN (" +
            "SELECT id FROM location WHERE is_temporary = 1)) " +
            "ORDER BY last_modified_at DESC",
    )
    fun observeToBePutBack(): Flow<List<ItemEntity>>

    /** P1-03（C-1 第一块）：物品总数 = 非 `gone`（`in_storage` + `to_be_put_back`）。 */
    @Query("SELECT COUNT(*) FROM item WHERE status != 'gone'")
    fun observeActiveCount(): Flow<Int>

    /**
     * P1-03（C-5 / FR-38）：**归位到指定位置** —— 位置 + 状态 + `last_modified_at` 在**一条语句**内写完。
     *
     * 为什么合成一条而不是 `moveItem` + `setStatus` 两步：跨两条 UPDATE 需要事务才原子，
     * 而这里两件事本就落在同一张表的同一行（单表写，守 `实现约束.md` §2-5 不必引事务）。
     * 「归位」既是状态变化（离开待归位）也是位置变化，按 §3.5 矩阵两列的写法一致：刷新 `last_modified_at`。
     */
    @Query(
        "UPDATE item SET location_id = :locationId, status = :status, last_modified_at = :modifiedAt " +
            "WHERE id = :id",
    )
    suspend fun putBack(id: String, locationId: String, status: ItemStatus, modifiedAt: Long): Int

    /**
     * P1-04：**移动物品**到另一个位置（详情页折叠区的「移动到…」入口）。
     *
     * 只改位置 + `last_modified_at`（位置变化属「变动」），**不动** `status`、`last_confirmed_at`
     * ——「换了地方」不是一次确认（§3.5 矩阵）。
     */
    @Query("UPDATE item SET location_id = :locationId, last_modified_at = :modifiedAt WHERE id = :id")
    suspend fun moveItem(id: String, locationId: String, modifiedAt: Long): Int
}

/** `location_id → 直属活跃件数` 的查询投影（Room 投影类，不入表）。 */
data class LocationItemCount(
    @ColumnInfo(name = "locationId")
    val locationId: String,
    @ColumnInfo(name = "itemCount")
    val itemCount: Int,
)
