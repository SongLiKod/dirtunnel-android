package com.dirtunnel.core.ui.theme

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Dp
import androidx.core.view.WindowCompat
import dev.chrisbanes.haze.HazeStyle
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.LocalHazeStyle

val LocalAppleColors = staticCompositionLocalOf { LightAppleColors }

/** 页面内读取当前主题色值。 */
@Composable
fun appleColors(): AppleColors = LocalAppleColors.current

/**
 * 全局 Apple 主题：所有页面读取这套色值（TECH.md 四 · Compose 实现思路）。
 * - [mode] = SYSTEM / LIGHT / DARK，读取系统夜间模式在浅深两套调色板之间切换；
 * - 悬浮导航毛玻璃样式由当前主题驱动，自动适配深色/浅色模式。
 */
@Composable
fun AppleTheme(
    mode: ThemeMode,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val dark = when (mode) {
        ThemeMode.SYSTEM -> systemDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }
    val colors = if (dark) DarkAppleColors else LightAppleColors

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            var context: Context? = view.context
            var activity: Activity? = null
            while (context is ContextWrapper && activity == null) {
                activity = context as? Activity
                context = context.baseContext
            }
            activity?.let {
                val controller = WindowCompat.getInsetsController(it.window, view)
                controller.isAppearanceLightStatusBars = !dark
                controller.isAppearanceLightNavigationBars = !dark
            }
        }
    }

    CompositionLocalProvider(
        LocalAppleColors provides colors,
        // 毛玻璃降级兜底（TECH.md 七.6：低版本毛玻璃失效时降级为对应主题半透明纯色背景）
        LocalHazeStyle provides HazeStyle(
            tints = emptyList(),
            blurRadius = Dp.Unspecified,
            fallbackTint = HazeTint(colors.floatingBarBg),
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.background),
        ) {
            content()
        }
    }
}
