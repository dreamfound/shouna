package com.dream.shouna.data.local.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * `location` 表（ARCHITECTURE-P0 §3.1）：位置树，`parent_id` 自引用、任意层级。
 *
 * - **不物化 `path`**（§8.1-2）：本页只需「直属物品 + 子位置」，父链拼路径在内存完成
 *   （[com.dream.shouna.util.LocationPath]）。ID 序列路径属 P1 的递归统计，届时再起迁移。
 * - `parent_id` **ON DELETE RESTRICT**：删位置以子树为单位、自底向上，禁止隐式级联（§3.4 不变量 3）。
 * - `is_built_in = 1` 的位置**不出现在位置选择器与浏览页**：它只为「删位置档 2 的 `gone` 物品收容」
 *   与极端兜底而存在（位置必填口径下，正常录入路径永不落内置位置）。
 * - `is_temporary` 字段已备、本页无写入路径（临时位置属 P1 的 FR-06）。
 */
@Entity(
    tableName = "location",
    foreignKeys = [
        ForeignKey(
            entity = LocationEntity::class,
            parentColumns = ["id"],
            childColumns = ["parent_id"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["parent_id"])],
)
data class LocationEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    /** 位置称呼（储物间、纸箱-07）。 */
    @ColumnInfo(name = "name")
    val name: String,
    /** 父级；根级 = NULL。 */
    @ColumnInfo(name = "parent_id")
    val parentId: String?,
    /** 内置标记；内置位置不在选择器／浏览页露出。 */
    @ColumnInfo(name = "is_built_in")
    val isBuiltIn: Boolean,
    /** P1 FR-06 预留，本页恒 false。 */
    @ColumnInfo(name = "is_temporary")
    val isTemporary: Boolean,
    /** FR-03 备注，可空。 */
    @ColumnInfo(name = "note")
    val note: String?,
    /** 同级排序。 */
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    /** FR-11 / FR-23：最近使用时间，**替代 `recent_location` 表**（§8.1-3）。 */
    @ColumnInfo(name = "last_used_at")
    val lastUsedAt: Long?,
    /** epoch millis UTC。 */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
)
