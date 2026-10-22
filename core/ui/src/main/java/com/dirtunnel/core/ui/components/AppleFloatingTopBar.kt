package com.dirtunnel.core.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography
import dev.chrisbanes.haze.HazeState

/**
 * 悬浮浮窗式导航 Header（TECH.md 四 · 重点：悬浮导航栏），完全自研，全局统一复用，
 * 不使用安卓原生 TopAppBar。
 *
 * 1. 位置：距离状态栏下方有边距、左右 16dp 边距，非通栏；
 * 2. 样式：圆角 16dp 毛玻璃背景（苹果 systemMaterial 等效）；
 * 3. 内容：左侧返回/标题，右侧操作按钮；
 * 4. 滚动行为：悬浮置顶，模糊强度随 [scrollOffset] 轻微变化；
 * 5. 分层：zIndex 高于页面内容，低于弹窗（弹窗为系统 Dialog 窗口层）。
 */
@Composable
fun AppleFloatingTopBar(
    title: String,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    scrollOffset: Float = 0f,
    centerTitle: Boolean = true,
    actions: @Composable RowScope.() -> Unit = {},
) {
    val colors = appleColors()
    val typography = appleTypography()

    Row(
        modifier = modifier
            .fillMaxWidth()
            .zIndex(2f)
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(top = 8.dp, start = 16.dp, end = 16.dp)
            .appleGlass(
                hazeState = hazeState,
                colors = colors,
                cornerRadius = 16.dp,
                baseBlurRadius = 16.dp,
                scrollOffset = scrollOffset,
            )
            .heightIn(min = 52.dp)
            .padding(start = 4.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        // 左侧返回
        if (onBack != null) {
            Box(modifier = Modifier.height(40.dp), contentAlignment = Alignment.Center) {
                AppleIconButton(onClick = onBack, size = 40.dp) {
                    AppleIcon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "返回",
                        tint = colors.primary,
                        size = 22.dp,
                    )
                }
            }
        }

        // 标题区（左侧返回/标题，居中大标题两种布局）
        Column(
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 4.dp),
            horizontalAlignment = if (centerTitle) Alignment.CenterHorizontally else Alignment.Start,
        ) {
            AppleText(
                text = title,
                style = typography.headline,
                color = colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                textAlign = TextAlign.Center,
            )
            if (subtitle != null) {
                AppleText(
                    text = subtitle,
                    style = typography.caption,
                    color = colors.textSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = TextAlign.Center,
                )
            }
        }

        // 右侧操作按钮
        Row(
            modifier = Modifier.height(40.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            content = actions,
        )
    }
}
