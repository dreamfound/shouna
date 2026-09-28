package com.dream.shouna

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
