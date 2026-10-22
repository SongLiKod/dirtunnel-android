package com.dirtunnel.feature.service

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Timeline
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtunnel.core.engine.model.ProcessState
import com.dirtunnel.core.engine.model.ServiceRuntimeStatus
import com.dirtunnel.core.storage.entity.ServiceInstanceEntity
import com.dirtunnel.core.storage.model.ProtocolType
import com.dirtunnel.core.storage.model.AccessPermission
import com.dirtunnel.core.ui.components.AppleAlertDialog
import com.dirtunnel.core.ui.components.AppleInfoDialog
import com.dirtunnel.core.ui.components.AppleBadge
import com.dirtunnel.core.ui.components.AppleButton
import com.dirtunnel.core.ui.components.AppleButtonSize
import com.dirtunnel.core.ui.components.AppleButtonStyle
import com.dirtunnel.core.ui.components.AppleEmptyState
import com.dirtunnel.core.ui.components.AppleGroupCard
import com.dirtunnel.core.ui.components.AppleIcon
import com.dirtunnel.core.ui.components.AppleIconButton
import com.dirtunnel.core.ui.components.AppleScreen
import com.dirtunnel.core.ui.components.AppleSectionHeader
import com.dirtunnel.core.ui.components.AppleSpinner
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.components.StatusDot
import com.dirtunnel.core.ui.components.formatBytes
import com.dirtunnel.core.ui.components.formatDuration
import com.dirtunnel.core.ui.components.formatRate
import com.dirtunnel.core.ui.components.rememberHazeState
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography

/**
 * 服务实例列表（REQ 4.2 服务实例管理 / 状态卡片；REQ 4.6 状态监控）。
 * 一个目录可同时绑定多个协议实例，各自独立端口、账号、权限、穿透开关。
 */
@Composable
fun ServiceListScreen(
    onBack: () -> Unit,
    onOpenService: (serviceId: Long, directoryId: Long) -> Unit,
    viewModel: ServiceListViewModel = hiltViewModel(),
) {
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()
    val colors = appleColors()

    val directory by viewModel.directory.collectAsStateWithLifecycle()
    val services by viewModel.services.collectAsStateWithLifecycle()
    val statuses by viewModel.statuses.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val infoDialog by viewModel.infoDialog.collectAsStateWithLifecycle()
    val banners by viewModel.banners.collectAsStateWithLifecycle()
    val busyId by viewModel.busyId.collectAsStateWithLifecycle()
    val advanced by viewModel.advancedVisible.collectAsStateWithLifecycle()

    AppleScreen(
        title = directory?.name ?: "目录服务",
        subtitle = directory?.let { "已绑定 ${services.size} 个服务" },
        hazeState = hazeState,
        onBack = onBack,
        listState = listState,
        actions = {
            AppleIconButton(onClick = { onOpenService(0L, viewModel.directoryId) }) {
                AppleIcon(Icons.Filled.Add, contentDescription = "添加服务", tint = colors.primary, size = 24.dp)
            }
        },
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
            // 权限不足 / 目录失效告警（必附解决方案）
            items(banners.size) { i ->
                val banner = banners[i]
                WarningBanner(title = banner.title, solution = banner.solution)
            }

            // 一键添加：零配置直接启动（REQ 五 · 易用性）
            item {
                AppleSectionHeader(
                    text = "一键添加",
                    footer = "默认配置自动创建并启动，成功后弹窗展示连接地址与密码",
                )
                AppleGroupCard {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        val chipEnabled = busyId == null
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuickAddChip("FTP", Modifier.weight(1f), chipEnabled) { viewModel.quickAdd(ProtocolType.FTP) }
                            QuickAddChip("FTPS", Modifier.weight(1f), chipEnabled) { viewModel.quickAdd(ProtocolType.FTPS) }
                            QuickAddChip("SFTP", Modifier.weight(1f), chipEnabled) { viewModel.quickAdd(ProtocolType.SFTP) }
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            QuickAddChip("HTTP", Modifier.weight(1f), chipEnabled) { viewModel.quickAdd(ProtocolType.HTTP) }
                            QuickAddChip("WebDAV", Modifier.weight(1f), chipEnabled) { viewModel.quickAdd(ProtocolType.WEBDAV) }
                            Spacer(Modifier.weight(1f))
                        }
                    }
                }
            }

            if (services.isEmpty()) {
                item {
                    AppleGroupCard {
                        AppleEmptyState(
                            title = "该目录还没有服务",
                            message = "点上方「一键添加」即可零配置启动；或用右上角 + 手动创建自定义服务",
                            icon = Icons.Outlined.FolderOpen,
                        )
                    }
                }
            }

            items(services, key = { it.id }) { svc ->
                if (advanced || !svc.protocolType.advanced) {
                    ServiceCard(
                        service = svc,
                        status = statuses[svc.id],
                        busy = busyId == svc.id,
                        onStart = { viewModel.start(svc.id) },
                        onStop = { viewModel.stop(svc.id) },
                        onRestart = { viewModel.restart(svc.id) },
                        onEdit = { onOpenService(svc.id, viewModel.directoryId) },
                        onDetail = { viewModel.showDetail(svc) },
                    )
                }
            }

            if (services.isNotEmpty()) {
                item {
                    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        AppleButton(
                            text = "一键启动全部",
                            onClick = { viewModel.startAll() },
                            modifier = Modifier.weight(1f),
                            style = AppleButtonStyle.FILLED,
                            size = AppleButtonSize.LARGE,
                        )
                        AppleButton(
                            text = "停止全部",
                            onClick = { services.forEach { viewModel.stop(it.id) } },
                            modifier = Modifier.weight(1f),
                            style = AppleButtonStyle.BORDERED,
                            size = AppleButtonSize.LARGE,
                        )
                    }
                }
            }
            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    alert?.let {
        AppleAlertDialog(
            visible = true,
            title = it.title,
            message = it.message,
            confirmText = it.confirmText,
            onConfirm = { viewModel.dismissAlert() },
            dismissText = null,
        )
    }

    AppleInfoDialog(
        spec = infoDialog,
        onDismiss = { viewModel.dismissInfo() },
    )
}

@Composable
private fun WarningBanner(title: String, solution: String) {
    val colors = appleColors()
    val typography = appleTypography()
    AppleGroupCard {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.error.copy(alpha = 0.08f))
                .padding(14.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StatusDot(color = colors.error, size = 8.dp)
                Spacer(Modifier.width(8.dp))
                AppleText(title, style = typography.headline, color = colors.error, maxLines = 2)
            }
            Spacer(Modifier.height(6.dp))
            AppleText(solution, style = typography.footnote, color = colors.textSecondary, maxLines = 5)
        }
    }
}

@Composable
private fun ServiceCard(
    service: ServiceInstanceEntity,
    status: ServiceRuntimeStatus?,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onRestart: () -> Unit,
    onEdit: () -> Unit,
    onDetail: () -> Unit,
) {
    val colors = appleColors()
    val typography = appleTypography()
    val state = status?.state
    val running = state is ProcessState.Running
    val failed = state as? ProcessState.Failed

    AppleGroupCard {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            // 标题行
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AppleText(
                        service.name,
                        style = typography.headline,
                        color = colors.textPrimary,
                        maxLines = 1,
                    )
                    Spacer(Modifier.height(3.dp))
                    AppleText(
                        "${service.protocolType.label} · 端口 ${service.port} · ${service.accessPermission.label}",
                        style = typography.footnote,
                        color = colors.textSecondary,
                        maxLines = 2,
                    )
                }
                StatusBadge(state, busy)
            }

            // 徽标行：穿透 / 自动启动 / 匿名
            Spacer(Modifier.height(9.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (service.enableTunnel) {
                    AppleBadge("隧道", dotColor = colors.primary)
                }
                if (service.autoStart) {
                    AppleBadge("自启", dotColor = colors.success)
                }
                if (service.anonymous) {
                    AppleBadge("匿名", dotColor = colors.warning)
                }
                if (service.whitelist.isNotBlank()) {
                    AppleBadge("IP白名单")
                }
                if (service.maxConnections > 0 || service.rateLimitKbps > 0) {
                    AppleBadge("已限流")
                }
            }

            // 运行时统计（REQ 4.6：在线连接数、实时流量）
            if (status != null && running) {
                Spacer(Modifier.height(9.dp))
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(8.dp))
                        .background(colors.background.copy(alpha = 0.6f))
                        .padding(9.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    StatCell("在线", "${status.activeConnections}")
                    StatCell("上传", formatBytes(status.bytesUp))
                    StatCell("下载", formatBytes(status.bytesDown))
                    StatCell(
                        "运行",
                        formatDuration(
                            System.currentTimeMillis() - (state as ProcessState.Running).startedAt,
                        ),
                    )
                }
            }

            // 失败提示（必附解决方案）
            if (failed != null) {
                Spacer(Modifier.height(9.dp))
                AppleText(
                    "⚠ ${failed.message}",
                    style = typography.footnote,
                    color = colors.error,
                    maxLines = 2,
                )
                Spacer(Modifier.height(3.dp))
                AppleText(
                    failed.solution,
                    style = typography.caption,
                    color = colors.textSecondary,
                    maxLines = 4,
                )
            }

            // 操作行
            Spacer(Modifier.height(12.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (running) {
                    AppleButton(
                        text = if (busy) "停止中…" else "停止",
                        onClick = onStop,
                        modifier = Modifier.weight(1f),
                        style = AppleButtonStyle.BORDERED,
                        size = AppleButtonSize.SMALL,
                        leadingIcon = Icons.Filled.Stop,
                    )
                    AppleButton(
                        text = "重启",
                        onClick = onRestart,
                        modifier = Modifier.weight(1f),
                        style = AppleButtonStyle.BORDERED,
                        size = AppleButtonSize.SMALL,
                        leadingIcon = Icons.Filled.Refresh,
                    )
                } else {
                    AppleButton(
                        text = when {
                            busy -> "启动中…"
                            state is ProcessState.Starting -> "启动中…"
                            else -> "启动"
                        },
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        style = AppleButtonStyle.FILLED,
                        size = AppleButtonSize.SMALL,
                        enabled = !busy && state !is ProcessState.Starting,
                        leadingIcon = Icons.Filled.PlayArrow,
                    )
                }
                AppleIconButton(onClick = onDetail, size = 36.dp) {
                    AppleIcon(
                        Icons.Filled.Info,
                        contentDescription = "详情",
                        tint = colors.primary,
                        size = 20.dp,
                    )
                }
                AppleIconButton(onClick = onEdit, size = 36.dp) {
                    AppleIcon(
                        Icons.Filled.Edit,
                        contentDescription = "编辑",
                        tint = colors.primary,
                        size = 20.dp,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatCell(label: String, value: String) {
    val colors = appleColors()
    val typography = appleTypography()
    Column {
        AppleText(value, style = typography.subhead, color = colors.textPrimary, maxLines = 1)
        AppleText(label, style = typography.caption2, color = colors.textSecondary, maxLines = 1)
    }
}

@Composable
private fun StatusBadge(state: ProcessState?, busy: Boolean) {
    val colors = appleColors()
    when {
        state is ProcessState.Running -> AppleBadge("运行中", dotColor = colors.success, textColor = colors.success)
        state is ProcessState.Starting || busy -> Row(verticalAlignment = Alignment.CenterVertically) {
            AppleSpinner(size = 14.dp)
            Spacer(Modifier.width(6.dp))
            AppleText("启动中", style = appleTypography().caption2, color = colors.primary)
        }
        state is ProcessState.Stopping -> AppleBadge("停止中", dotColor = colors.warning, textColor = colors.warning)
        state is ProcessState.Failed -> AppleBadge("异常", dotColor = colors.error, textColor = colors.error)
        state is ProcessState.Exited -> AppleBadge("已退出", dotColor = colors.warning, textColor = colors.warning)
        else -> AppleBadge("已停止", dotColor = colors.textSecondary)
    }
}

/** 一键添加协议小按钮（iOS 边框胶囊样式，无 Material 组件）。 */
@Composable
private fun QuickAddChip(
    text: String,
    modifier: Modifier,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    AppleButton(
        text = text,
        onClick = onClick,
        modifier = modifier,
        style = AppleButtonStyle.BORDERED,
        size = AppleButtonSize.SMALL,
        enabled = enabled,
    )
}
