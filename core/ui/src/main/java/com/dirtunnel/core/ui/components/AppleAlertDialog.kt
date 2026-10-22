package com.dirtunnel.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography

/**
 * iOS 圆角弹窗 + 背景变暗蒙版（TECH.md 四 · 全局控件规范）。
 * 宽 270pt、圆角 14pt、标题/正文居中、底部按钮细分割线分隔。
 */
@Composable
fun AppleAlertDialog(
    visible: Boolean,
    title: String,
    onDismissRequest: () -> Unit = {},
    message: String? = null,
    confirmText: String = "确定",
    onConfirm: () -> Unit = { onDismissRequest() },
    dismissText: String? = "取消",
    destructiveConfirm: Boolean = false,
) {
    if (!visible) return
    val colors = appleColors()
    val typography = appleTypography()

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismissRequest,
                )
                .navigationBarsPadding()
                .padding(40.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(270.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 19.dp, bottom = 16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppleText(
                        text = title,
                        style = typography.headline,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 4,
                    )
                    if (message != null) {
                        Spacer(Modifier.height(6.dp))
                        AppleText(
                            text = message,
                            style = typography.footnote,
                            color = colors.textPrimary,
                            textAlign = TextAlign.Center,
                            maxLines = 8,
                        )
                    }
                }

                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))

                Row(
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Center,
                ) {
                    if (dismissText != null) {
                        AlertButton(
                            text = dismissText,
                            weight = 1f,
                            color = colors.primary,
                            onClick = onDismissRequest,
                        )
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .height(44.dp)
                                .background(colors.separator),
                        )
                    }
                    AlertButton(
                        text = confirmText,
                        weight = if (dismissText == null) 1f else 1f,
                        color = if (destructiveConfirm) colors.error else colors.primary,
                        bold = dismissText != null,
                        onClick = onConfirm,
                    )
                }
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.AlertButton(
    text: String,
    weight: Float,
    color: Color,
    onClick: () -> Unit,
    bold: Boolean = false,
) {
    val typography = appleTypography()
    Box(
        modifier = Modifier
            .weight(weight)
            .fillMaxWidth()
            .height(44.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                onClick = onClick,
            ),
        contentAlignment = Alignment.Center,
    ) {
        AppleText(
            text = text,
            style = if (bold) typography.headline.copy(fontWeight = FontWeight.SemiBold) else typography.body,
            color = color,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}
