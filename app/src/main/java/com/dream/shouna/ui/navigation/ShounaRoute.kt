package com.dream.shouna.ui.navigation

import kotlinx.serialization.Serializable

/**
 * F1 的 4 条类型安全路由键（ARCHITECTURE §1.2）。纯声明，不含 Composable。
 */

@Serializable
data object Home

@Serializable
data object QuickAdd

@Serializable
data class Search(val initialQuery: String? = null)

@Serializable
data class ItemDetail(val itemId: String)
