package com.dirtunnel.core.ui.components

import androidx.compose.foundation.border
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.times
import com.dirtunnel.core.ui.theme.AppleColors
import dev.chrisbanes.haze.HazeState
import dev.chrisbanes.haze.HazeTint
import dev.chrisbanes.haze.hazeEffect
import dev.chrisbanes.haze.hazeSource

/** 页面内容统一挂到该 State 上，供悬浮导航做背景取样（毛玻璃）。 */
@Composable
fun rememberHazeState(): HazeState = remember { HazeState() }

/**
 * 毛玻璃封装（TECH.md 四 · 悬浮导航栏 6/7 条）：
 * Compose BackdropBlur + 半透明底色叠加模拟 iOS `systemMaterial`。
 *
 * - Android 12+（支持 RenderEffect）为真模糊；
 * - 低版本/不支持的机型 Haze 自动降级为 [AppleColors.floatingBarBg] 对应主题的
 *   半透明纯色背景，保证可用性（TECH.md 七.6）。
 * - [scrollOffset] 0..1，滚动时模糊强度轻微变化（TECH.md 四 · 悬浮导航 4）。
 */
fun Modifier.appleGlass(
    hazeState: HazeState,
    colors: AppleColors,
    cornerRadius: Dp = 16.dp,
    baseBlurRadius: Dp = 16.dp,
    scrollOffset: Float = 0f,
): Modifier {
    val shape = RoundedCornerShape(cornerRadius)
    val dynamicBlur = baseBlurRadius + (scrollOffset.coerceIn(0f, 1f) * 6.dp)
    return this
        .clip(shape)
        .hazeEffect(hazeState) {
            // haze 1.2.x：绘制时要求显式背景色，Unspecified 会抛 IllegalArgumentException
            backgroundColor = colors.floatingBarBg
            blurRadius = dynamicBlur
            tints = listOf(HazeTint(colors.floatingBarBg))
            fallbackTint = HazeTint(colors.floatingBarBg)
            noiseFactor = 0.05f
        }
        .border(
            width = 0.5.dp,
            color = colors.outline.copy(alpha = if (colors.isDark) 0.6f else 0.9f),
            shape = shape,
        )
}

/** 将页面主体标记为毛玻璃取样源。 */
fun Modifier.appleHazeSource(hazeState: HazeState): Modifier = this.hazeSource(hazeState)
