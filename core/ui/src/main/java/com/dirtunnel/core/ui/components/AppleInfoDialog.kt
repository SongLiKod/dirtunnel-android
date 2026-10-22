package com.dirtunnel.core.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography
import kotlinx.coroutines.delay

/** 可复制信息弹窗的字段（点击整行或右侧「复制」写入剪贴板）。 */
data class AppleInfoField(
    val label: String,
    val value: String,
)

/**
 * 可复制信息弹窗（TECH 四 · iOS 风格；REQ 五 · 一键启动后展示连接信息）。
 * 地址/账号/密码按字段独立展示、点击即复制，带「已复制」反馈；可选警示条与「复制全部」。
 */
data class AppleInfoDialogSpec(
    val title: String,
    val subtitle: String? = null,
    val fields: List<AppleInfoField> = emptyList(),
    /** 警示条（黄底，环境/模式类提示） */
    val notices: List<String> = emptyList(),
    /** 底部灰色小字 */
    val tip: String? = null,
    /** 「复制全部」写入剪贴板的整段文本；null = 不显示该按钮 */
    val copyAllText: String? = null,
    val confirmText: String = "完成",
)

@Composable
fun AppleInfoDialog(
    spec: AppleInfoDialogSpec?,
    onDismiss: () -> Unit,
) {
    if (spec == null) return
    val colors = appleColors()
    val typography = appleTypography()
    val clipboard = LocalClipboardManager.current

    var copiedIndex by remember(spec) { mutableStateOf(-1) }
    var copiedAll by remember(spec) { mutableStateOf(false) }
    LaunchedEffect(copiedIndex) {
        if (copiedIndex >= 0) {
            delay(1_600)
            copiedIndex = -1
        }
    }
    LaunchedEffect(copiedAll) {
        if (copiedAll) {
            delay(1_600)
            copiedAll = false
        }
    }

    fun copy(text: String) {
        clipboard.setText(AnnotatedString(text))
    }

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
                )
                .navigationBarsPadding()
                .padding(28.dp),
            contentAlignment = Alignment.Center,
        ) {
            Column(
                modifier = Modifier
                    .width(312.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(colors.surface)
                    // 吞掉卡片内部点击，避免误触蒙层关闭
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null,
                        onClick = {},
                    ),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                // ===== 头部：成功徽标 + 标题 =====
                Column(
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(
                        modifier = Modifier
                            .size(44.dp)
                            .clip(CircleShape)
                            .background(colors.success.copy(alpha = 0.15f)),
                        contentAlignment = Alignment.Center,
                    ) {
                        AppleText(
                            text = "✓",
                            style = typography.headline.copy(fontSize = 24.sp, fontWeight = FontWeight.Bold),
                            color = colors.success,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    AppleText(
                        text = spec.title,
                        style = typography.headline,
                        color = colors.textPrimary,
                        textAlign = TextAlign.Center,
                        maxLines = 2,
                    )
                    if (spec.subtitle != null) {
                        Spacer(Modifier.height(4.dp))
                        AppleText(
                            text = spec.subtitle,
                            style = typography.footnote,
                            color = colors.textSecondary,
                            textAlign = TextAlign.Center,
                            maxLines = 2,
                        )
                    }
                }

                // ===== 警示条 =====
                if (spec.notices.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(start = 14.dp, end = 14.dp, bottom = 12.dp)
                            .fillMaxWidth(),
                        verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    ) {
                        spec.notices.forEach { NoticeStrip(it) }
                    }
                }

                // ===== 字段组（可复制行） =====
                if (spec.fields.isNotEmpty()) {
                    Column(
                        modifier = Modifier
                            .padding(start = 14.dp, end = 14.dp)
                            .fillMaxWidth()
                            .clip(RoundedCornerShape(10.dp))
                            .background(colors.background),
                    ) {
                        spec.fields.forEachIndexed { i, field ->
                            if (i > 0) {
                                Box(
                                    modifier = Modifier
                                        .padding(start = 12.dp)
                                        .fillMaxWidth()
                                        .height(0.5.dp)
                                        .background(colors.separator),
                                )
                            }
                            FieldRow(
                                field = field,
                                copied = copiedIndex == i,
                                onClick = {
                                    copy(field.value)
                                    copiedIndex = i
                                },
                            )
                        }
                    }
                }

                if (spec.tip != null) {
                    Spacer(Modifier.height(10.dp))
                    AppleText(
                        text = spec.tip,
                        style = typography.footnote,
                        color = colors.textSecondary,
                        textAlign = TextAlign.Center,
                        maxLines = 3,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                Spacer(Modifier.height(14.dp))

                // ===== 底部：复制全部 | 完成 =====
                Box(Modifier.fillMaxWidth().height(0.5.dp).background(colors.separator))
                Row(
                    modifier = Modifier.fillMaxWidth().height(44.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    if (spec.copyAllText != null) {
                        BottomActionButton(
                            text = if (copiedAll) "✓ 已复制" else "复制全部",
                            color = if (copiedAll) colors.success else colors.primary,
                            weight = 1f,
                            bold = false,
                            onClick = {
                                copy(spec.copyAllText)
                                copiedAll = true
                            },
                        )
                        Box(
                            modifier = Modifier
                                .width(0.5.dp)
                                .height(44.dp)
                                .background(colors.separator),
                        )
                    }
                    BottomActionButton(
                        text = spec.confirmText,
                        color = colors.primary,
                        weight = 1f,
                        bold = true,
                        onClick = onDismiss,
                    )
                }
            }
        }
    }
}

@Composable
private fun NoticeStrip(text: String) {
    val colors = appleColors()
    val typography = appleTypography()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(8.dp))
            .background(colors.warning.copy(alpha = 0.14f))
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppleText(
            text = "⚠",
            style = typography.footnote,
            color = colors.warning,
        )
        Spacer(Modifier.width(6.dp))
        AppleText(
            text = text,
            style = typography.footnote,
            color = colors.textPrimary,
            maxLines = 3,
        )
    }
}

@Composable
private fun FieldRow(
    field: AppleInfoField,
    copied: Boolean,
    onClick: () -> Unit,
) {
    val colors = appleColors()
    val typography = appleTypography()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onClick,
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            AppleText(
                text = field.label,
                style = typography.footnote,
                color = colors.textSecondary,
                maxLines = 1,
            )
            Spacer(Modifier.height(2.dp))
            AppleText(
                text = field.value,
                style = typography.body,
                color = colors.textPrimary,
                maxLines = 2,
            )
        }
        Spacer(Modifier.width(10.dp))
        AppleText(
            text = if (copied) "✓ 已复制" else "复制",
            style = typography.footnote.copy(fontWeight = FontWeight.SemiBold),
            color = if (copied) colors.success else colors.primary,
            maxLines = 1,
        )
    }
}

@Composable
private fun RowScope.BottomActionButton(
    text: String,
    color: androidx.compose.ui.graphics.Color,
    weight: Float,
    bold: Boolean,
    onClick: () -> Unit,
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
