package com.dirtunnel.app.dashboard

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtunnel.core.engine.health.HealthWarning
import com.dirtunnel.core.engine.model.ProcessState
import com.dirtunnel.core.network.NetworkType
import com.dirtunnel.core.ui.components.AppleBadge
import com.dirtunnel.core.ui.components.AppleButton
import com.dirtunnel.core.ui.components.AppleButtonSize
import com.dirtunnel.core.ui.components.AppleButtonStyle
import com.dirtunnel.core.ui.components.AppleAlertDialog
import com.dirtunnel.core.ui.components.AppleInfoDialog
import com.dirtunnel.core.ui.components.AppleGroupCard
import com.dirtunnel.core.ui.components.AppleIcon
import com.dirtunnel.core.ui.components.AppleScreen
import com.dirtunnel.core.ui.components.AppleSectionHeader
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.components.StatusDot
import com.dirtunnel.core.ui.components.formatBytes
import com.dirtunnel.core.ui.components.rememberHazeState
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography

/**
 * 首页仪表盘（REQ 4.6 监控）：
 * 服务运行状态、在线连接数、实时流量、隧道状态、端口占用告警、权限不足告警。
 */
@Composable
fun DashboardScreen(
    onOpenDirectory: () -> Unit,
    onOpenTunnel: () -> Unit,
    onOpenLog: () -> Unit,
    viewModel: DashboardViewModel = hiltViewModel(),
) {
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()
    val colors = appleColors()
    val typography = appleTypography()

    val stats by viewModel.stats.collectAsStateWithLifecycle()
    val warnings by viewModel.warnings.collectAsStateWithLifecycle()
    val network by viewModel.network.collectAsStateWithLifecycle()
    val directoryCount by viewModel.directoryCount.collectAsStateWithLifecycle()
    val serviceCount by viewModel.serviceCount.collectAsStateWithLifecycle()
    val tapBusy by viewModel.tapBusy.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val infoDialog by viewModel.infoDialog.collectAsStateWithLifecycle()

    LaunchedEffect(Unit) {
        viewModel.refreshWarnings()
    }

    AppleScreen(
        title = "DirTunnel",
        subtitle = "多协议目录服务 & 内网穿透",
        hazeState = hazeState,
        listState = listState,
    ) { padding ->
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            // ===== 一键启动 / 停止（REQ 五 · 易用性：打开即可用） =====
            item {
                AppleGroupCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(16.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        val anyActive = stats.runningServices > 0
                        AppleText(
                            if (anyActive) "已运行 ${stats.runningServices} 个服务" else "尚未启动任何服务",
                            style = typography.footnote,
                            color = colors.textSecondary,
                        )
                        Spacer(Modifier.height(10.dp))
                        AppleButton(
                            text = when {
                                tapBusy -> "处理中…"
                                anyActive -> "一键停止全部"
                                else -> "一键启动 FTP"
                            },
                            onClick = viewModel::oneTap,
                            modifier = Modifier.fillMaxWidth(),
                            size = AppleButtonSize.LARGE,
                            enabled = !tapBusy,
                        )
                        Spacer(Modifier.height(8.dp))
                        AppleText(
                            "首次点击自动创建共享目录与 FTP 服务并弹出连接地址/密码，无需任何配置。",
                            style = typography.caption2,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            // ===== 告警区（REQ 4.6：端口占用告警、权限不足告警） =====
            if (warnings.isNotEmpty()) {
                items(warnings.size) { i ->
                    WarningCard(warnings[i])
                }
            }

            // ===== 网络状态 =====
            item {
                AppleGroupCard {
                    Row(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        StatusDot(
                            color = if (network.isConnected) colors.success else colors.error,
                            size = 10.dp,
                        )
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            AppleText(
                                when {
                                    !network.isConnected -> "网络未连接"
                                    network.type == NetworkType.HOTSPOT -> "热点模式"
                                    network.type == NetworkType.WIFI -> "已连接 WiFi"
                                    network.type == NetworkType.CELLULAR -> "蜂窝网络"
                                    else -> "已连接"
                                },
                                style = typography.headline,
                                color = colors.textPrimary,
                            )
                            if (network.transports.isNotEmpty()) {
                                AppleText(
                                    network.transports.joinToString(" · "),
                                    style = typography.footnote,
                                    color = colors.textSecondary,
                                )
                            }
                        }
                        if (network.isMetered) {
                            AppleBadge("计费网络", dotColor = colors.warning, textColor = colors.warning)
                        }
                    }
                }
            }

            // ===== 核心指标卡（REQ 4.6：服务状态、连接数、流量、隧道状态） =====
            item {
                AppleSectionHeader("运行概览")
                AppleGroupCard {
                    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
                        Row(modifier = Modifier.fillMaxWidth()) {
                            StatCell(
                                value = "${stats.runningServices}/${stats.totalServices}",
                                label = "运行中服务",
                                modifier = Modifier.weight(1f),
                                highlight = stats.runningServices > 0,
                            )
                            StatCell(
                                value = stats.activeConnections.toString(),
                                label = "在线连接",
                                modifier = Modifier.weight(1f),
                                highlight = stats.activeConnections > 0,
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            StatCell(
                                value = "↑${formatBytes(stats.bytesUp)}",
                                label = "累计上传",
                                modifier = Modifier.weight(1f),
                            )
                            StatCell(
                                value = "↓${formatBytes(stats.bytesDown)}",
                                label = "累计下载",
                                modifier = Modifier.weight(1f),
                            )
                        }
                        Spacer(Modifier.height(16.dp))
                        Row(modifier = Modifier.fillMaxWidth()) {
                            StatCell(
                                value = "${stats.connectedTunnels}/${stats.totalTunnels}",
                                label = "已连接隧道",
                                modifier = Modifier.weight(1f),
                                highlight = stats.connectedTunnels > 0,
                            )
                            StatCell(
                                value = directoryCount.toString(),
                                label = "已授权目录",
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            // ===== 快捷入口 =====
            item {
                AppleSectionHeader("快捷入口", footer = "核心流程：选择目录 → 添加协议服务 →（可选）开启穿透")
                AppleGroupCard {
                    QuickActionRow(
                        icon = Icons.Outlined.Folder,
                        title = "目录管理",
                        subtitle = "浏览、收藏手机任意目录，一键复用",
                        onClick = onOpenDirectory,
                    )
                    QuickActionRow(
                        icon = Icons.Outlined.Cloud,
                        title = "内网穿透",
                        subtitle = "公共隧道一键开启 · 自建 FRP · TCP 转发",
                        onClick = onOpenTunnel,
                    )
                    QuickActionRow(
                        icon = Icons.Outlined.Speed,
                        title = "连接日志",
                        subtitle = "连接记录、流量审计、CSV 导出",
                        onClick = onOpenLog,
                        showSeparator = false,
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }

        alert?.let { a ->
            AppleAlertDialog(
                visible = true,
                title = a.title,
                message = a.message,
                confirmText = a.confirmText,
                destructiveConfirm = a.destructive,
                onConfirm = { viewModel.dismissAlert() },
                onDismissRequest = { viewModel.dismissAlert() },
            )
        }

        AppleInfoDialog(
            spec = infoDialog,
            onDismiss = { viewModel.dismissInfo() },
        )
    }
}

@Composable
private fun StatCell(
    value: String,
    label: String,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
) {
    val colors = appleColors()
    val typography = appleTypography()
    Column(modifier = modifier) {
        AppleText(
            value,
            style = typography.title2.copy(fontWeight = FontWeight.Bold),
            color = if (highlight) colors.primary else colors.textPrimary,
            maxLines = 1,
        )
        AppleText(label, style = typography.caption, color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun QuickActionRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
    showSeparator: Boolean = true,
) {
    val colors = appleColors()
    val typography = appleTypography()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(0.dp))
                .clickable(
                    interactionSource = MutableInteractionSource(),
                    indication = null,
                    onClick = onClick,
                )
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(colors.primary.copy(alpha = 0.12f)),
                contentAlignment = Alignment.Center,
            ) {
                AppleIcon(icon, contentDescription = null, tint = colors.primary, size = 18.dp)
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                AppleText(title, style = typography.body, color = colors.textPrimary, maxLines = 1)
                AppleText(subtitle, style = typography.footnote, color = colors.textSecondary, maxLines = 2)
            }
            AppleIcon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.separator,
                size = 20.dp,
            )
        }
        if (showSeparator) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = 60.dp)
                    .height(0.5.dp)
                    .background(colors.separator),
            )
        }
    }
}

@Composable
private fun WarningCard(warning: HealthWarning) {
    val colors = appleColors()
    val typography = appleTypography()
    val isWarning = warning.severity == HealthWarning.Severity.WARNING
    val tint = if (warning.severity == HealthWarning.Severity.ERROR) colors.error else colors.warning

    AppleGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(tint.copy(alpha = 0.08f))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppleIcon(
                    if (isWarning) Icons.Outlined.Warning else Icons.Outlined.Info,
                    contentDescription = null,
                    tint = tint,
                    size = 18.dp,
                )
                Spacer(Modifier.width(8.dp))
                AppleText(
                    warning.title,
                    style = typography.headline,
                    color = tint,
                    maxLines = 2,
                    modifier = Modifier.weight(1f),
                )
            }
            Spacer(Modifier.height(6.dp))
            AppleText(warning.message, style = typography.footnote, color = colors.textPrimary, maxLines = 4)
            Spacer(Modifier.height(6.dp))
            AppleText(warning.solution, style = typography.caption, color = colors.textSecondary, maxLines = 5)
        }
    }
}
