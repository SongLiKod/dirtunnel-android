package com.dirtunnel.core.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.dirtunnel.core.ui.theme.appleColors
import kotlin.math.roundToInt

/**
 * 复刻 iOS UISwitch 样式（TECH.md 四 · 全局控件规范：开关复刻 iOS UISwitch）。
 * 轨道 51x31pt，白色圆形滑块 27pt，苹果式弹性滑动。
 */
@Composable
fun AppleSwitch(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
) {
    val colors = appleColors()
    val travel = remember { Animatable(if (checked) 1f else 0f) }
    LaunchedEffect(checked) {
        travel.animateTo(
            targetValue = if (checked) 1f else 0f,
            animationSpec = spring(
                dampingRatio = 0.8f,
                stiffness = Spring.StiffnessMediumLow,
            ),
        )
    }
    val trackColor by animateColorAsState(
        targetValue = if (checked) colors.switchOn else colors.switchOffTrack,
        animationSpec = spring(dampingRatio = Spring.DampingRatioNoBouncy, stiffness = Spring.StiffnessMediumLow),
        label = "switchTrack",
    )

    // 51x31 轨道，2pt 内边距，滑块 27pt → 水平行程 = 51 - 4 - 27 = 20
    val trackWidth = 51.dp
    val trackHeight = 31.dp
    val thumbSize = 27.dp
    val travelPx = with(androidx.compose.ui.platform.LocalDensity.current) { (trackWidth - 4.dp - thumbSize).toPx() }

    Box(
        modifier = modifier
            .size(trackWidth, trackHeight)
            .clip(CircleShape)
            .background(trackColor)
            .alpha(if (enabled) 1f else 0.5f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                role = Role.Switch,
                onClick = { onCheckedChange(!checked) },
            )
            .padding(2.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        Box(
            modifier = Modifier
                .offset { IntOffset((travel.value * travelPx).roundToInt(), 0) }
                .size(thumbSize)
                .shadow(elevation = 2.dp, shape = CircleShape, ambientColor = androidx.compose.ui.graphics.Color.Black, spotColor = androidx.compose.ui.graphics.Color.Black)
                .clip(CircleShape)
                .background(colors.switchThumb),
        )
    }
}
