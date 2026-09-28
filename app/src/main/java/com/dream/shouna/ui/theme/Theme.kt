package com.dream.shouna.ui.theme

import android.os.Build
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density

private val LightColorScheme = lightColorScheme(
    primary = Purple40,
    secondary = PurpleGrey40,
    tertiary = Pink40,
)

/**
 * App-wide Material 3 theme.
 *
 * F1-01 永久浅色 + 固定字号（ARCHITECTURE §7.2）：
 * - 无 `darkTheme` 参数、无深色分支；动态取色锁 `dynamicLightColorScheme`，
 *   系统切深色后 UI 保持浅色（FR-48 / NFR-16 撤销）。
 * - `LocalDensity` 以 `fontScale = 1f` 覆盖，系统字号 200% 下 app 内文字尺寸不变（NFR-13 撤销）。
 *
 * Dynamic colour (Material You) is used on API 31+ where the platform can derive a
 * palette from the user's wallpaper; older versions fall back to the static palette
 * in [Color.kt].
 */
@Composable
fun ShounaTheme(
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val colorScheme = if (dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        dynamicLightColorScheme(LocalContext.current)
    } else {
        LightColorScheme
    }

    val density = LocalDensity.current
    CompositionLocalProvider(
        LocalDensity provides Density(density = density.density, fontScale = 1f),
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = ShounaTypography,
            content = content,
        )
    }
}
