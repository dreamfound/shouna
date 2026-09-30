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
import com.dream.shouna.ui.screen.category.CategoryManageRoute
import com.dream.shouna.ui.screen.home.HomeRoute
import com.dream.shouna.ui.screen.itemdetail.ItemDetailRoute
import com.dream.shouna.ui.screen.itemedit.ItemEditRoute
import com.dream.shouna.ui.screen.location.LocationBrowseRoute
import com.dream.shouna.ui.screen.search.SearchRoute
import com.dream.shouna.ui.screen.settings.SettingsRoute
import com.dream.shouna.ui.screen.stats.StatsRoute

/**
 * 全局唯一 NavController 出口：仅 [com.dream.shouna.MainActivity] 提供，Route 层消费。
 * `XxxScreen` 不得读取本对象（ARCHITECTURE §2）。
 */
val LocalNavController = staticCompositionLocalOf<NavHostController> {
    error("LocalNavController 未提供：NavController 只能由 MainActivity 创建并下发")
}

/**
 * 唯一 NavHost：9 条 [ShounaRoute] 与 9 个页面一一注册。仅做路由，不写业务逻辑、不取 ViewModel。
 *
 * 系统栏避让是**唯一容器级**职责（ARCHITECTURE §2、§7.2）：窗口是边到边的（`MainActivity`
 * 调 `enableEdgeToEdge()`，且 API 35+ 本就强制），故在此对唯一 NavHost 施加
 * `systemBars ∪ displayCutout ∪ ime` 内边距，9 个页面统一避开状态栏、导航栏与键盘、各自不再处理。
 * 用 `union` 而非叠加两次 padding：刘海屏竖屏时状态栏高度已含刘海、键盘高度已含导航栏，叠加会多出空白。
 * **含 IME**（2026-09-29 由「不含 IME」改）：键盘弹起时容器整体上移，录入页的「保存并继续」
 * 浮在键盘上方可点 —— 保存的唯一入口是按钮，IME 完成键只收起键盘，不代替保存。
 *
 * P1 新增的 4 条（[Stats] / [ItemEdit] / [Settings] / [CategoryManage]）在**同一张 NavHost** 上注册，
 * 不引入嵌套图：它们的返回行为与既有 5 页一致（普通 `popBackStack`），统计的卡片/明细两态
 * 在 `StatsScreen` 内部消化，不占路由（P1 §8.1-16）。
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
        composable<Stats> {
            StatsRoute()
        }
        composable<ItemEdit> { entry ->
            ItemEditRoute(itemId = entry.toRoute<ItemEdit>().itemId)
        }
        composable<Settings> {
            SettingsRoute()
        }
        composable<CategoryManage> {
            CategoryManageRoute()
        }
    }
}

// ---- 对外导航动作：Route 层调用，Screen 只下发 onXxx() ----
// 这些动作的实参完全由调用点决定，无业务逻辑，直接接线。

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

/** P1 新增：进入归纳统计（FR-33）。卡片态 / 明细态是页内两态，不进路由。 */
fun NavHostController.goStats() {
    // 被 HomeScreen 的「更多 › 归纳统计」触发
    navigate(Stats)
}

/** P1 新增：进入物品编辑页（P1-04）。被详情页的「✏」与「⋯更多」折叠区触发。 */
fun NavHostController.goItemEdit(itemId: String) {
    navigate(ItemEdit(itemId))
}

/** P1 新增：进入设置页（FR-47）。被 HomeScreen 的「更多 › 设置」触发。 */
fun NavHostController.goSettings() {
    navigate(Settings)
}

/** P1 新增：进入分类管理页（FR-44）。被设置页的「分类管理」入口触发。 */
fun NavHostController.goCategoryManage() {
    navigate(CategoryManage)
}

fun NavHostController.popBack() {
    // 调用关系：NavController.popBackStack()
    // 被 SearchScreen 的 onBack 触发
    popBackStack()
}
