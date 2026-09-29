package com.dream.shouna.ui.navigation

import kotlinx.serialization.Serializable

/**
 * 类型安全路由键。F1 的 4 条（Home / QuickAdd / Search / ItemDetail）+ P0-02 新增 1 条
 * （[LocationBrowse]）= **5 条**。纯声明，不含 Composable。
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
