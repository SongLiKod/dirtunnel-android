package com.dirtunnel.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.dirtunnel.core.ui.theme.appleColors
import dev.chrisbanes.haze.HazeState

/**
 * 页面骨架：毛玻璃悬浮导航 + 页面内容 + 可选底栏（如 TabBar）。
 *
 * - 内容整体挂 [hazeSource] 供悬浮导航取样；
 * - 传入 [listState] 时，滚动位置换算成 scrollOffset 驱动模糊强度轻微变化；
 * - contentPadding 顶部已避开状态栏 + 悬浮导航高度，底部避开底栏。
 */
@Composable
fun AppleScreen(
    title: String,
    hazeState: HazeState,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    onBack: (() -> Unit)? = null,
    listState: LazyListState? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
    hasBottomBar: Boolean = false,
    bottomBar: @Composable () -> Unit = {},
    content: @Composable (PaddingValues) -> Unit,
) {
    val colors = appleColors()

    val statusBarTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val navBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()

    val topBarHeight = if (subtitle != null) 58.dp else 52.dp
    val topPadding = statusBarTop + 8.dp + topBarHeight + 12.dp
    val bottomPadding = if (hasBottomBar) 56.dp + navBottom + 12.dp else navBottom + 16.dp

    val scrollOffset by remember(listState) {
        derivedStateOf {
            val state = listState ?: return@derivedStateOf 0f
            if (state.firstVisibleItemIndex == 0) {
                (state.firstVisibleItemScrollOffset / 160f).coerceIn(0f, 1f)
            } else {
                1f
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(colors.background),
    ) {
        // 页面主体（毛玻璃取样源）
        Box(
            modifier = Modifier
                .fillMaxSize()
                .appleHazeSource(hazeState),
        ) {
            content(
                PaddingValues(top = topPadding, bottom = bottomPadding),
            )
        }

        // 悬浮导航浮层：高于页面内容，低于弹窗
        AppleFloatingTopBar(
            title = title,
            hazeState = hazeState,
            subtitle = subtitle,
            onBack = onBack,
            scrollOffset = scrollOffset,
            actions = actions,
        )

        // 底栏（如 TabBar），毛玻璃、贴底
        if (hasBottomBar) {
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .zIndex(2f),
            ) {
                bottomBar()
            }
        }
    }
}
