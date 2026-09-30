package com.dream.shouna.ui.navigation

import kotlinx.serialization.Serializable

/**
 * 类型安全路由键。F1 的 4 条（Home / QuickAdd / Search / ItemDetail）+ P0-02 新增 1 条
 * （[LocationBrowse]）+ **P1 新增 4 条**（[Stats] / [ItemEdit] / [Settings] / [CategoryManage]）
 * = **9 条**。纯声明，不含 Composable。
 *
 * P1 的两条边界（P1 §8.1-16 / §8.1-15）：
 * - **统计下钻不新增路由** —— 卡片态 / 明细态是同一个 `Stats` 目的地的页内两态；
 * - 分类管理**有独立页**（`prd/07` §6.1 未给），由 P-SETTINGS 内的入口进入。
 */

@Serializable
data object Home

@Serializable
data object QuickAdd

@Serializable
data class Search(val initialQuery: String? = null)

@Serializable
data class ItemDetail(val itemId: String)

/**
 * P-BROWSE（FR-01 / 02 / 03 / 05）。
 * `locationId = null` 表示**根级**（全部位置），非 null 表示进入该位置那一层。
 */
@Serializable
data class LocationBrowse(val locationId: String? = null)

/**
 * P-STATS（FR-33 / 29 / 38）：归纳统计。
 * 无参数 —— 卡片态与明细态由 ViewModel 的 `mode` 决定，不下沉到路由。
 */
@Serializable
data object Stats

/**
 * P-ITEM-EDIT（P1-04：别名 / 数量 / FR-14 落点）。
 * 详情页的「✏」与「⋯更多」折叠区都进这一条。
 */
@Serializable
data class ItemEdit(val itemId: String)

/** P-SETTINGS（FR-47）：阈值、分类管理入口、隐私说明、关于。 */
@Serializable
data object Settings

/** 分类管理页（FR-44）：由 P-SETTINGS 内的入口进入。 */
@Serializable
data object CategoryManage
