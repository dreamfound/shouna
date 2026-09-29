package com.dream.shouna.ui.navigation

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.toRoute
import com.dream.shouna.ui.screen.add.QuickAddRoute
import com.dream.shouna.ui.screen.home.HomeRoute
import com.dream.shouna.ui.screen.itemdetail.ItemDetailRoute
import com.dream.shouna.ui.screen.location.LocationBrowseRoute
import com.dream.shouna.ui.screen.search.SearchRoute

/**
 * 全局唯一 NavController 出口：仅 [com.dream.shouna.MainActivity] 提供，Route 层消费。
 * `XxxScreen` 不得读取本对象（ARCHITECTURE §2）。
 */
val LocalNavController = staticCompositionLocalOf<NavHostController> {
    error("LocalNavController 未提供：NavController 只能由 MainActivity 创建并下发")
}

/**
 * 唯一 NavHost：5 条 [ShounaRoute] 与 5 个页面一一注册。仅做路由，不写业务逻辑、不取 ViewModel。
 *
 * 系统栏避让是**唯一容器级**职责（ARCHITECTURE §2、§7.2）：窗口是边到边的（`MainActivity`
 * 调 `enableEdgeToEdge()`，且 API 35+ 本就强制），故在此对唯一 NavHost 施加
 * `systemBars ∪ displayCutout ∪ ime` 内边距，5 个页面统一避开状态栏、导航栏与键盘、各自不再处理。
 * 用 `union` 而非叠加两次 padding：刘海屏竖屏时状态栏高度已含刘海、键盘高度已含导航栏，叠加会多出空白。
 * **含 IME**（2026-09-29 由「不含 IME」改）：键盘弹起时容器整体上移，录入页的「保存并继续」
 * 浮在键盘上方可点 —— 保存的唯一入口是按钮，IME 完成键只收起键盘，不代替保存。
 */
@Composable
fun RouteHandler(modifier: Modifier = Modifier) {
    val navController = LocalNavController.current
    NavHost(
        navController = navController,
        startDestination = Home,
        modifier = modifier.windowInsetsPadding(
            WindowInsets.systemBars.union(WindowInsets.displayCutout).union(WindowInsets.ime),
        ),
    ) {
        composable<Home> {
            HomeRoute()
        }
        composable<QuickAdd> {
            QuickAddRoute()
        }
        composable<Search> { entry ->
            SearchRoute(initialQuery = entry.toRoute<Search>().initialQuery)
        }
        composable<ItemDetail> { entry ->
            ItemDetailRoute(itemId = entry.toRoute<ItemDetail>().itemId)
        }
        composable<LocationBrowse> { entry ->
            LocationBrowseRoute(locationId = entry.toRoute<LocationBrowse>().locationId)
        }
    }
}

// ---- 对外导航动作：Route 层调用，Screen 只下发 onXxx() ----
// 这 6 个动作的实参完全由调用点决定，无业务逻辑，直接接线。

fun NavHostController.goHome() {
    // 调用关系：NavController.navigate(Home)
    // [待定] 是否加 popUpTo(Home) { inclusive = true } 清理返回栈未定
    navigate(Home)
}

fun NavHostController.goQuickAdd() {
    // 调用关系：NavController.navigate(QuickAdd)
    // 被 HomeScreen 的 onQuickAddClick 触发
    navigate(QuickAdd)
}

fun NavHostController.goSearch(initialQuery: String? = null) {
    // 调用关系：NavController.navigate(Search(initialQuery))
    // 被 HomeScreen 的 onSearchClick 触发（F1 传 null，进入后聚焦）
    navigate(Search(initialQuery))
}

fun NavHostController.goItemDetail(itemId: String) {
    // 调用关系：NavController.navigate(ItemDetail(itemId))
    // 被 SearchScreen 的 onResultClick、LocationBrowseScreen 的 onItemClick 触发
    navigate(ItemDetail(itemId))
}

/** P0-02 新增：进入位置浏览。`locationId = null` = 根级（全部位置）。 */
fun NavHostController.goLocationBrowse(locationId: String? = null) {
    navigate(LocationBrowse(locationId))
}

fun NavHostController.popBack() {
    // 调用关系：NavController.popBackStack()
    // 被 SearchScreen 的 onBack 触发
    popBackStack()
}
