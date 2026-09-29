package com.dream.shouna

import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.CompositionLocalProvider
import androidx.navigation.compose.rememberNavController
import com.dream.shouna.ui.navigation.LocalNavController
import com.dream.shouna.ui.navigation.RouteHandler
import com.dream.shouna.ui.theme.ShounaTheme
import dagger.hilt.android.AndroidEntryPoint

/**
 * F1 唯一 Activity。`NavController` 只在此创建一次，经 [LocalNavController] 下发（ARCHITECTURE §2）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    /**
     * 永久浅色的窗口层收敛（ARCHITECTURE §7.2）：把 `uiMode` 锁成 `NIGHT_NO`。
     *
     * 只靠 Compose 侧 `dynamicLightColorScheme` + `fontScale = 1f` 不够——窗口与系统栏不归
     * Compose 管。锁住 `uiMode` 后：① 系统切深色时 `enableEdgeToEdge()` 仍按浅色背景取**深色**
     * 系统栏图标（否则会给白图标，压在本 App 的浅色背景上几乎不可见）；② 不再选中任何 `night`
     * 资源（`res/values-night/` 已删除）；③ `isSystemInDarkTheme()` 恒为 false。
     */
    override fun attachBaseContext(newBase: Context) {
        val lightUiMode = Configuration(newBase.resources.configuration).apply {
            uiMode = (uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
                Configuration.UI_MODE_NIGHT_NO
        }
        super.attachBaseContext(newBase.createConfigurationContext(lightUiMode))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            CompositionLocalProvider(LocalNavController provides rememberNavController()) {
                ShounaTheme {
                    RouteHandler()
                }
            }
        }
    }
}
