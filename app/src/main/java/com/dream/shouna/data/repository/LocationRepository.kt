package com.dream.shouna.data.repository

import com.dream.shouna.domain.model.Location
import com.dream.shouna.domain.model.LocationTreeRow
import com.dream.shouna.domain.model.StoredItem
import kotlinx.coroutines.flow.Flow

/**
 * 位置删除的两档（FR-05 / ARCHITECTURE-P0 §8.1-4）。
 *
 * 两档都**不是**级联删除物品（§3.4 不变量 4），且都必须给物品一个去处
 * —— 因为 `item.location_id` 是 `NOT NULL`（位置必填口径）。
 */
enum class LocationDeleteMode {
    /** 档 1：把子树内全部物品**迁移**到用户指定的目标位置。 */
    MIGRATE,

    /** 档 2：把子树内全部物品标记 `gone`，并**收容到内置哨兵**（用户不可见）。 */
    ARCHIVE,
}

/**
 * 位置仓库（ARCHITECTURE-P0 §2 + P1 §2）：位置树读取、增删改、删除两档、最近使用；
 * **P1-02 起扩移动 / 合并 / 临时标记 / 递归计数**（接口扩展，既有签名不变）。
 * 只暴露稳定领域类型，不泄漏 Room 结构。
 */
interface LocationRepository {

    /** 位置树（已展平、带层次与路径、**过滤内置哨兵**）。位置选择器与浏览页共用。 */
    fun observeTree(): Flow<List<LocationTreeRow>>

    /** 某节点的直属子位置；`parentId = null` 取根级。 */
    fun observeChildren(parentId: String?): Flow<List<Location>>

    /** 某位置的**直属**物品（P-BROWSE 用）。 */
    fun observeItemsIn(locationId: String): Flow<List<StoredItem>>

    suspend fun findById(id: String): Location?

    /** 新建位置；`parentId = null` 建在根级。返回新建的节点。 */
    suspend fun create(name: String, parentId: String?): Location

    suspend fun rename(id: String, name: String): Boolean

    suspend fun setNote(id: String, note: String?): Boolean

    /**
     * 删除位置（**以子树为单位**）。
     * 返回 false 的情形：目标不存在、目标为内置哨兵、迁移目标非法（自身 / 自身子树 / 不存在）。
     */
    suspend fun delete(
        id: String,
        mode: LocationDeleteMode,
        migrateTargetId: String? = null,
    ): Boolean

    /** FR-11 / FR-23：最近使用的位置（不含内置哨兵），默认取 5 个。 */
    suspend fun recentUsed(limit: Int = RECENT_LOCATION_LIMIT): List<Location>

    /** FR-11：记录一次「被用」，下次录入据此预选。 */
    suspend fun touchLastUsed(id: String)

    // --- P1-02（FR-04 / 06 / 21 / 28）：位置树深化 --------------------------------

    /**
     * FR-04：把 [nodeId] **整棵子树**移到 [newParentId] 下（null = 根级）。
     *
     * 前置校验（P1 §3.4-8）：拒绝目标不存在、目标指向自身、目标位于自身子树内
     * （后者用 `LocationPath.isDescendantPath` 判 ID 序列路径前缀）。
     * 同事务内改 `parent_id` 并**重写受影响子树的 `path`** ⇒ 返回 false 时数据零变动。
     */
    suspend fun move(nodeId: String, newParentId: String?): Boolean

    /**
     * FR-04：合并位置 —— [sourceId] 的子位置与物品**全部改挂** [targetId]，随后 source 按
     * 「删位置」既有档位处理（不得产生孤儿）。方向由调用方显式给出，不做自动推断（P1 §8.1-10）。
     *
     * 返回 false 的情形：任一方不存在、source 为内置哨兵、target 为 source 自身或位于其子树内。
     */
    suspend fun merge(sourceId: String, targetId: String): Boolean

    /** FR-06：标记 / 取消位置为临时。位置侧操作，**不刷新**任何物品时间戳（P1 §3.5）。 */
    suspend fun setTemporary(nodeId: String, flag: Boolean): Boolean

    /**
     * FR-21：件数流 —— 同时给「本层」与「含子层」两个数，供树行展示
     * 「本层 N 件 / 含子层共 M 件」；`gone` 不计（P1 §8.1-7 的递归口径）。
     */
    fun observeCounts(nodeId: String): Flow<LocationCounts>

    companion object {
        /** 录入页「最近」chips 的条数（FR-11）。 */
        const val RECENT_LOCATION_LIMIT: Int = 5
    }
}

/**
 * 位置件数（P1-02，FR-21）。两个数分开给而不是只给合计：树行要同时说清
 * 「这一层有几件」与「连同子层一共几件」，用户才不会被「含子层」的批量确认范围吓到。
 */
data class LocationCounts(
    val nodeId: String,
    /** 本层**直属**且非 `gone` 的件数。 */
    val directCount: Int,
    /** 本层 + 全部子孙、非 `gone` 的件数。 */
    val subtreeCount: Int,
)
