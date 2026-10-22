package com.dirtunnel.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.dirtunnel.core.ui.theme.appleColors

/**
 * iOS 风格滑杆（自研，不使用 Material 组件）。
 * 轨道 + 主题色填充 + 白色圆钮；点击跳转、拖动连续调节。
 *
 * 手势层覆盖整个滑杆区域且坐标系固定（不随圆点移动，避免反馈震荡）；
 * 持续拖动只回调 [onValueChange]，拖动结束 / 点按时才回调
 * [onValueChangeFinished]（调用方在该时机一次性落盘，避免逐帧写存储）。
 *
 * @param value 当前值（受 [valueRange] 约束）
 * @param onValueChange 值变化回调（拖动过程中持续触发）
 * @param onValueChangeFinished 拖动结束 / 点按时触发（持久化时机）
 */
@Composable
fun AppleSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
) {
    val colors = appleColors()
    val trackHeight = 5.dp
    val thumbSize = 24.dp

    BoxWithConstraints(
        modifier = modifier
            .fillMaxWidth()
            .height(32.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        val trackWidth = maxWidth
        val fraction = if (valueRange.endInclusive > valueRange.start) {
            ((value - valueRange.start) / (valueRange.endInclusive - valueRange.start)).coerceIn(0f, 1f)
        } else {
            0f
        }

        fun valueAtX(xPx: Float, trackWidthPx: Float): Float {
            val frac = (xPx / trackWidthPx).coerceIn(0f, 1f)
            return valueRange.start + frac * (valueRange.endInclusive - valueRange.start)
        }

        // 手势层：铺满整块区域、坐标系固定（圆点移动不影响坐标换算）
        Box(
            Modifier
                .matchParentSize()
                .pointerInput(enabled, valueRange, trackWidth) {
                    if (!enabled) return@pointerInput
                    // PointerInputScope 实现 Density：在此换算像素宽
                    val wPx = trackWidth.toPx()
                    detectTapGestures { off ->
                        onValueChange(valueAtX(off.x, wPx))
                        onValueChangeFinished?.invoke()
                    }
                }
                .pointerInput(enabled, valueRange, trackWidth) {
                    if (!enabled) return@pointerInput
                    val wPx = trackWidth.toPx()
                    detectHorizontalDragGestures(
                        onDragStart = { off -> onValueChange(valueAtX(off.x, wPx)) },
                        onHorizontalDrag = { change, _ ->
                            onValueChange(valueAtX(change.position.x, wPx))
                        },
                        onDragEnd = { onValueChangeFinished?.invoke() },
                        onDragCancel = { onValueChangeFinished?.invoke() },
                    )
                },
        )

        // 视觉层：轨道 / 填充 / 圆钮（无手势处理，事件透传给手势层）
        Box(
            Modifier
                .fillMaxWidth()
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(colors.separator.copy(alpha = 0.6f)),
        )
        Box(
            Modifier
                .fillMaxWidth(fraction)
                .height(trackHeight)
                .clip(RoundedCornerShape(trackHeight / 2))
                .background(if (enabled) colors.primary else colors.separator),
        )
        Box(
            Modifier
                .offset(x = (trackWidth - thumbSize) * fraction)
                .size(thumbSize)
                .clip(CircleShape)
                .background(Color.White)
                .border(0.5.dp, colors.separator, CircleShape),
        )
    }
}
