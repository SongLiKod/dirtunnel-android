package com.dirtunnel.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import com.dirtunnel.core.ui.theme.AppleColors
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography

/**
 * iOS 分组列表（Grouped List）：分组标题浅灰、分割线细、单元格圆角（TECH.md 四）。
 */
@Composable
fun AppleSectionHeader(
    text: String,
    modifier: Modifier = Modifier,
    footer: String? = null,
) {
    val colors = appleColors()
    Column(modifier = modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 22.dp, bottom = 7.dp)) {
        AppleText(
            text = text,
            style = appleTypography().footnote,
            color = colors.textSecondary,
            maxLines = 2,
        )
        if (footer != null) {
            Spacer(Modifier.height(4.dp))
            AppleText(
                text = footer,
                style = appleTypography().caption,
                color = colors.textSecondary,
                maxLines = 4,
            )
        }
    }
}

/** 分组卡片容器：单元格圆角、轻阴影、细分割线。 */
@Composable
fun AppleGroupCard(
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    content: @Composable ColumnScope.() -> Unit,
) {
    val colors = appleColors()
    Column(
        modifier = modifier
            .fillMaxWidth()
            .shadow(elevation = 1.dp, shape = shape, ambientColor = Color.Transparent, spotColor = Color.Transparent)
            .clip(shape)
            .background(colors.surface),
        content = content,
    )
}

/**
 * iOS 列表单元格：最小高度 44dp、分割线内缩、可选图标/值/箭头。
 */
@Composable
fun AppleCell(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    value: String? = null,
    leadingIcon: ImageVector? = null,
    leadingIconTint: Color = Color.Unspecified,
    leadingIconContainerColor: Color? = null,
    showChevron: Boolean = false,
    showSeparator: Boolean = true,
    separatorInset: Dp? = null,
    destructive: Boolean = false,
    enabled: Boolean = true,
    onClick: (() -> Unit)? = null,
    trailing: @Composable (() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val colors = appleColors()
    val typography = appleTypography()
    val effectiveInset = separatorInset
        ?: if (leadingIcon != null) 60.dp else 16.dp

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp)),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .let {
                    if (onClick != null) {
                        val interaction = remember { MutableInteractionSource() }
                        it.clickable(
                            interactionSource = interaction,
                            indication = null,
                            enabled = enabled,
                            role = Role.Button,
                            onClick = onClick,
                        )
                    } else it
                }
                .alpha(if (enabled) 1f else 0.4f)
                .defaultMinSize(minHeight = 44.dp)
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (leadingIcon != null) {
                if (leadingIconContainerColor != null) {
                    Box(
                        modifier = Modifier
                            .width(30.dp)
                            .height(30.dp)
                            .clip(RoundedCornerShape(7.dp))
                            .background(leadingIconContainerColor),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppleIcon(
                            imageVector = leadingIcon,
                            contentDescription = null,
                            tint = if (leadingIconTint != Color.Unspecified) leadingIconTint else colors.onPrimary,
                            size = 18.dp,
                        )
                    }
                } else {
                    AppleIcon(
                        imageVector = leadingIcon,
                        contentDescription = null,
                        tint = if (leadingIconTint != Color.Unspecified) leadingIconTint else colors.primary,
                        size = 21.dp,
                    )
                }
                Spacer(Modifier.width(12.dp))
            }

            Column(modifier = Modifier.weight(1f)) {
                AppleText(
                    text = title,
                    style = typography.body,
                    color = if (destructive) colors.error else colors.textPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    AppleText(
                        text = subtitle,
                        style = typography.footnote,
                        color = colors.textSecondary,
                        maxLines = 3,
                    )
                }
                content()
            }

            if (value != null) {
                Spacer(Modifier.width(8.dp))
                AppleText(
                    text = value,
                    style = typography.callout,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            if (trailing != null) {
                Spacer(Modifier.width(8.dp))
                trailing()
            }
            if (showChevron) {
                Spacer(Modifier.width(4.dp))
                AppleIcon(
                    imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                    contentDescription = null,
                    tint = colors.separator,
                    size = 20.dp,
                )
            }
        }

        if (showSeparator) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = effectiveInset),
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(0.5.dp)
                        .background(colors.separator),
                )
            }
        }
    }
}

/** 行内开关单元格：整行可点切换，复刻 iOS 设置页。 */
@Composable
fun AppleSwitchCell(
    title: String,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    showSeparator: Boolean = true,
    enabled: Boolean = true,
    leadingIcon: ImageVector? = null,
    leadingIconContainerColor: Color? = null,
) {
    AppleCell(
        title = title,
        modifier = modifier,
        subtitle = subtitle,
        leadingIcon = leadingIcon,
        leadingIconContainerColor = leadingIconContainerColor,
        showSeparator = showSeparator,
        enabled = enabled,
        onClick = { onCheckedChange(!checked) },
        trailing = {
            AppleSwitch(checked = checked, onCheckedChange = onCheckedChange, enabled = enabled)
        },
    )
}

/** 分组容器：水平内缩 16dp（页面内列表已处理边距时传 0）。 */
@Composable
fun AppleGroup(padding: Dp = 0.dp, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = padding),
        verticalArrangement = Arrangement.spacedBy(0.dp),
        content = content,
    )
}
