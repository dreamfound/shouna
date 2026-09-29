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
 * 位置仓库（ARCHITECTURE-P0 §2）：位置树读取、增删改、删除两档、最近使用。
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

    companion object {
        /** 录入页「最近」chips 的条数（FR-11）。 */
        const val RECENT_LOCATION_LIMIT: Int = 5
    }
}
