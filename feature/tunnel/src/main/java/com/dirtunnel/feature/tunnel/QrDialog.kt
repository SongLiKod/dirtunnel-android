package com.dirtunnel.feature.tunnel

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.dirtunnel.core.ui.components.AppleButton
import com.dirtunnel.core.ui.components.AppleButtonStyle
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography

/**
 * 外网地址二维码弹窗（REQ 4.3.1：生成外网访问地址 + 二维码分享）。
 * iOS 圆角卡片 + 背景蒙版，二维码黑白高对比保证扫码率。
 */
@Composable
fun QrDialog(
    state: QrSheetState?,
    onDismiss: () -> Unit,
) {
    if (state == null) return
    val colors = appleColors()
    val typography = appleTypography()
    val clipboard = LocalClipboardManager.current

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, dismissOnClickOutside = true),
    ) {
        Column(
            modifier = Modifier
                .navigationBarsPadding()
                .padding(32.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(colors.surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppleText(state.title, style = typography.headline, color = colors.textPrimary, textAlign = TextAlign.Center)
            Spacer(Modifier.height(14.dp))

            state.bitmap?.let { bmp ->
                Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = "外网访问地址二维码",
                    modifier = Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White)
                        .padding(8.dp),
                )
            }
            Spacer(Modifier.height(14.dp))

            AppleText(
                text = state.content,
                style = typography.footnote,
                color = colors.textSecondary,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth(),
            )

            Spacer(Modifier.height(18.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                AppleButton(
                    text = "复制地址",
                    onClick = {
                        clipboard.setText(AnnotatedString(state.content))
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    style = AppleButtonStyle.BORDERED,
                )
                AppleButton(
                    text = "关闭",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
