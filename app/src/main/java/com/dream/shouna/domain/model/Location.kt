package com.dream.shouna.domain.model

/**
 * 位置（ARCHITECTURE-P0 §3）：`parent_id` 自引用的任意层级树。
 *
 * - [isBuiltIn] 为 true 的位置是**系统保留位**（仅 `未指定位置` 一条哨兵）：不出现在位置选择器
 *   与浏览页、不可删除。位置必填口径下，正常录入路径**永不**落到内置位置。
 * - [isTemporary] 字段已备、本页无写入路径（临时位置属 P1 的 FR-06）。
 * - 路径**不物化**：由父链实时拼装（[com.dream.shouna.util.LocationPath]）。
 */
data class Location(
    val id: String,
    val name: String,
    /** 内置（系统保留）标记。 */
    val isBuiltIn: Boolean,
    /** 父级；根级 = null。 */
    val parentId: String? = null,
    /** P1 FR-06 预留，本页恒 false。 */
    val isTemporary: Boolean = false,
    /** FR-03 备注。 */
    val note: String? = null,
    /** 同级排序。 */
    val sortOrder: Int = 0,
    /** FR-11 / FR-23：最近使用时间；从未使用 = null。 */
    val lastUsedAt: Long? = null,
)
