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

/** Action Sheet 可选项。 */
data class AppleOption(
    val label: String,
    val selected: Boolean = false,
    val destructive: Boolean = false,
    val enabled: Boolean = true,
)

/**
 * iOS 风格底部选项单（Action Sheet）：圆角卡片、居中选项、勾选标记、独立取消按钮。
 * 用于主题切换、协议选择等场景。
 */
@Composable
fun AppleOptionSheet(
    visible: Boolean,
    onDismiss: () -> Unit,
    title: String? = null,
    message: String? = null,
    options: List<AppleOption>,
    onSelect: (index: Int) -> Unit,
    cancelText: String = "取消",
) {
    if (!visible) return
    val colors = appleColors()
    val typography = appleTypography()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(colors.scrim)
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = onDismiss,
                ),
            contentAlignment = Alignment.BottomCenter,
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 10.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (title != null || message != null) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(14.dp))
                            .background(colors.surface)
                            .padding(horizontal = 16.dp, vertical = 14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        if (title != null) {
                            AppleText(
                                text = title,
                                style = typography.footnote,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                        if (message != null) {
                            Spacer(Modifier.height(4.dp))
                            AppleText(
                                text = message,
                                style = typography.footnote,
                                color = colors.textSecondary,
                                textAlign = TextAlign.Center,
                            )
                        }
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface),
                ) {
                    options.forEachIndexed { index, option ->
                        SheetOptionRow(
                            option = option,
                            showSeparator = index != options.lastIndex,
                            onClick = {
                                if (option.enabled) {
                                    onDismiss()
                                    onSelect(index)
                                }
                            },
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(14.dp))
                        .background(colors.surface)
                        .clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                            onClick = onDismiss,
                        ),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    AppleText(
                        text = cancelText,
                        modifier = Modifier.fillMaxWidth().padding(vertical = 16.dp),
                        style = typography.headline.copy(fontWeight = FontWeight.SemiBold),
                        color = colors.primary,
                        textAlign = TextAlign.Center,
                    )
                }
            }
        }
    }
}

@Composable
private fun SheetOptionRow(
    option: AppleOption,
    showSeparator: Boolean,
    onClick: () -> Unit,
) {
    val colors = appleColors()
    val typography = appleTypography()

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(0.dp))
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                role = Role.Button,
                enabled = option.enabled,
                onClick = onClick,
            ),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 15.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center,
        ) {
            AppleText(
                text = option.label,
                modifier = Modifier.weight(1f, fill = false),
                style = typography.body,
                color = when {
                    !option.enabled -> colors.textSecondary.copy(alpha = 0.5f)
                    option.destructive -> colors.error
                    else -> colors.primary
                },
                textAlign = TextAlign.Center,
                maxLines = 2,
            )
            if (option.selected) {
                Spacer(Modifier.width(8.dp))
                AppleText(
                    text = "✓",
                    style = typography.body,
                    color = colors.primary,
                )
            }
        }
        if (showSeparator) {
            Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
        }
    }
}
