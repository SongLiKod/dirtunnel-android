package com.dirtunnel.core.ui.components

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography

/** iOS 圆角按钮样式：主按钮填充、次要按钮描边（TECH.md 四 · 全局控件规范）。 */
enum class AppleButtonStyle { FILLED, BORDERED, PLAIN, DESTRUCTIVE }

enum class AppleButtonSize(val vertical: Dp, val horizontal: Dp, val minHeight: Dp) {
    SMALL(6.dp, 12.dp, 30.dp),
    MEDIUM(10.dp, 16.dp, 38.dp),
    LARGE(13.dp, 20.dp, 46.dp),
}

/**
 * iOS 圆角按钮（圆角半径 12px），无 Material 水滴波纹，
 * 按下时低阻尼缩放（0.96）苹果式弹性过渡。
 */
@Composable
fun AppleButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    style: AppleButtonStyle = AppleButtonStyle.FILLED,
    size: AppleButtonSize = AppleButtonSize.MEDIUM,
    enabled: Boolean = true,
    shape: Shape = RoundedCornerShape(12.dp),
    leadingIcon: ImageVector? = null,
    iconTint: Color = Color.Unspecified,
) {
    val colors = appleColors()
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) 0.96f else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMediumLow,
        ),
        label = "appleButtonPress",
    )

    val background = when (style) {
        AppleButtonStyle.FILLED -> colors.primary
        AppleButtonStyle.DESTRUCTIVE -> colors.error
        else -> Color.Transparent
    }
    val contentColor = when (style) {
        AppleButtonStyle.FILLED, AppleButtonStyle.DESTRUCTIVE -> colors.onPrimary
        else -> colors.primary
    }

    Row(
        modifier = modifier
            .scale(scale)
            .clip(shape)
            .background(background)
            .let {
                if (style == AppleButtonStyle.BORDERED) {
                    it.border(width = 1.dp, color = colors.outline, shape = shape)
                } else it
            }
            .clickable(
                interactionSource = interaction,
                indication = null,
                enabled = enabled,
                role = Role.Button,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.4f)
            .defaultMinSize(minHeight = size.minHeight)
            .padding(horizontal = size.horizontal, vertical = size.vertical)
            .padding(size.vertical / 2),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (leadingIcon != null) {
            AppleIcon(
                imageVector = leadingIcon,
                contentDescription = null,
                tint = if (iconTint != Color.Unspecified) iconTint else contentColor,
                size = 18.dp,
            )
            androidx.compose.foundation.layout.Spacer(Modifier.padding(start = 6.dp))
        }
        AppleText(
            text = text,
            style = when (size) {
                AppleButtonSize.SMALL -> appleTypography().subhead
                AppleButtonSize.MEDIUM -> appleTypography().callout
                AppleButtonSize.LARGE -> appleTypography().headline
            },
            color = contentColor,
        )
    }
}

/** 仅文字的 iOS 分段/行内动作按钮。 */
@Composable
fun AppleTextAction(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    destructive: Boolean = false,
) {
    val colors = appleColors()
    AppleText(
        text = text,
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                enabled = enabled,
                onClick = onClick,
            )
            .alpha(if (enabled) 1f else 0.4f)
            .padding(horizontal = 8.dp, vertical = 6.dp),
        style = appleTypography().callout,
        color = if (destructive) colors.error else colors.primary,
    )
}

/** 便于列表单元格使用的内边距预设。 */
val AppleCellPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
