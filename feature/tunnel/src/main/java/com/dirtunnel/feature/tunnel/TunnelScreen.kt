package com.dirtunnel.feature.tunnel

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
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Share
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtunnel.core.engine.model.ProcessState
import com.dirtunnel.core.storage.entity.FrpProfileEntity
import com.dirtunnel.core.storage.entity.TcpForwardEntity
import com.dirtunnel.core.storage.model.PROFILE_PUBLIC
import com.dirtunnel.core.ui.components.AppleAlertDialog
import com.dirtunnel.core.ui.components.AppleBadge
import com.dirtunnel.core.ui.components.AppleButton
import com.dirtunnel.core.ui.components.AppleButtonSize
import com.dirtunnel.core.ui.components.AppleButtonStyle
import com.dirtunnel.core.ui.components.AppleCell
import com.dirtunnel.core.ui.components.AppleEmptyState
import com.dirtunnel.core.ui.components.AppleGroupCard
import com.dirtunnel.core.ui.components.AppleIcon
import com.dirtunnel.core.ui.components.AppleIconButton
import com.dirtunnel.core.ui.components.AppleScreen
import com.dirtunnel.core.ui.components.AppleSectionHeader
import com.dirtunnel.core.ui.components.AppleSpinner
import com.dirtunnel.core.ui.components.AppleSwitch
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.components.StatusDot
import com.dirtunnel.core.ui.components.rememberHazeState
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography

/**
 * 隧道面板（REQ 4.3：两种模式公共隧道 + 自建 FRP；REQ 三 · 自定义 TCP 转发）。
 * 一键公共隧道 → 生成外网地址 + 二维码；自建 FRP 图形化配置，无需手写 ini。
 */
@Composable
fun TunnelScreen(
    onBack: () -> Unit,
    onAddProfile: () -> Unit,
    onEditProfile: (profileId: Long) -> Unit,
    onAddForward: () -> Unit,
    onEditForward: (forwardId: Long) -> Unit,
    viewModel: TunnelViewModel = hiltViewModel(),
) {
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()
    val colors = appleColors()
    val typography = appleTypography()

    val runtimes by viewModel.runtimes.collectAsStateWithLifecycle()
    val profiles by viewModel.profiles.collectAsStateWithLifecycle()
    val forwards by viewModel.forwards.collectAsStateWithLifecycle()
    val endpoint by viewModel.endpoint.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val qrSheet by viewModel.qrSheet.collectAsStateWithLifecycle()
    val busyId by viewModel.busyProfileId.collectAsStateWithLifecycle()

    val publicRuntime = runtimes[PROFILE_PUBLIC]
    val publicRunning = publicRuntime?.state is ProcessState.Running
    val publicBusy = busyId == PROFILE_PUBLIC

    AppleScreen(
        title = "内网穿透",
        subtitle = "公共隧道 · 自建 FRP · TCP 转发",
        hazeState = hazeState,
        onBack = onBack,
        listState = listState,
        actions = {
            AppleIconButton(onClick = { viewModel.refreshEndpoint() }) {
                AppleIcon(Icons.Filled.Refresh, contentDescription = "刷新端点", tint = colors.primary, size = 22.dp)
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
            // ===== 公共隧道（一键使用） =====
            item {
                AppleSectionHeader("公共隧道（一键穿透）")
                PublicTunnelCard(
                    running = publicRunning,
                    busy = publicBusy,
                    endpoint = endpoint,
                    remoteCount = publicRuntime?.proxies?.size ?: 0,
                    onStart = { viewModel.startPublic() },
                    onStop = { viewModel.stopProfile(PROFILE_PUBLIC) },
                    onShowQr = { viewModel.showQr(PROFILE_PUBLIC) },
                )
            }

            // ===== 自建 FRP 档案 =====
            item {
                AppleSectionHeader(
                    "自建 FRP 档案",
                    footer = "填写 frps 服务器地址、端口与 token，图形化配置，无需手写 ini。",
                )
                AppleGroupCard {
                    AppleCell(
                        title = "添加 FRP 档案",
                        subtitle = "连接你自己的 frps 服务端",
                        leadingIcon = Icons.Filled.Add,
                        showChevron = true,
                        onClick = onAddProfile,
                    )
                }
            }

            if (profiles.isEmpty()) {
                item {
                    AppleGroupCard {
                        AppleEmptyState(
                            title = "还没有自建档案",
                            message = "普通用户用上方公共隧道即可；开发者可添加自有 frps 服务器实现完全可控的穿透。",
                            icon = Icons.Outlined.Cloud,
                        )
                    }
                }
            } else {
                items(profiles, key = { "profile_${it.id}" }) { profile ->
                    val rt = runtimes[profile.id]
                    FrpProfileCard(
                        profile = profile,
                        runtime = rt,
                        busy = busyId == profile.id,
                        onStart = { viewModel.startProfile(profile.id) },
                        onStop = { viewModel.stopProfile(profile.id) },
                        onShowQr = { viewModel.showQr(profile.id) },
                        onEdit = { onEditProfile(profile.id) },
                        onDelete = { viewModel.deleteProfile(profile.id) },
                    )
                }
            }

            // ===== 自定义 TCP 转发 =====
            item {
                AppleSectionHeader(
                    "自定义 TCP 转发",
                    footer = "转发 APP 以外的本地服务（数据库、SSH、调试端口等），适配所有内置协议 + 用户自定义端口。",
                )
                AppleGroupCard {
                    AppleCell(
                        title = "添加 TCP 转发",
                        subtitle = "如：本地 3306 MySQL",
                        leadingIcon = Icons.Filled.Add,
                        showChevron = true,
                        onClick = onAddForward,
                    )
                }
            }

            if (forwards.isEmpty()) {
                item {
                    AppleGroupCard {
                        AppleEmptyState(
                            title = "还没有 TCP 转发",
                            message = "点击上方添加，把手机上任意本地服务的端口通过 FRP 隧道发布到公网。",
                            icon = Icons.Outlined.Router,
                        )
                    }
                }
            } else {
                items(forwards, key = { "forward_${it.id}" }) { forward ->
                    ForwardCard(
                        forward = forward,
                        profiles = profiles,
                        onToggle = { viewModel.toggleForward(forward.id, it) },
                        onEdit = { onEditForward(forward.id) },
                        onDelete = { viewModel.deleteForward(forward.id) },
                    )
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

    QrDialog(state = qrSheet, onDismiss = { viewModel.dismissQr() })
}

// ==================== 公共隧道卡片 ====================

@Composable
private fun PublicTunnelCard(
    running: Boolean,
    busy: Boolean,
    endpoint: com.dirtunnel.core.network.PublicTunnelEndpoint?,
    remoteCount: Int,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShowQr: () -> Unit,
) {
    val colors = appleColors()
    val typography = appleTypography()

    AppleGroupCard {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(34.dp).clip(RoundedCornerShape(9.dp)).background(colors.primary.copy(alpha = 0.12f)), contentAlignment = Alignment.Center) {
                    AppleIcon(Icons.Outlined.Cloud, contentDescription = null, tint = colors.primary, size = 20.dp)
                }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    AppleText("公共隧道", style = typography.headline, color = colors.textPrimary, maxLines = 1)
                    AppleText(
                        text = endpoint?.let { "${it.serverAddr}:${it.serverPort} · ${it.source}" } ?: "正在获取端点…",
                        style = typography.footnote,
                        color = colors.textSecondary,
                        maxLines = 2,
                    )
                }
                TunnelStatusBadge(running = running, busy = busy)
            }

            if (running && remoteCount > 0) {
                Spacer(Modifier.height(10.dp))
                AppleText(
                    "已映射 $remoteCount 个端口到公网",
                    style = typography.footnote,
                    color = colors.success,
                )
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        text = "二维码",
                        onClick = onShowQr,
                        modifier = Modifier.weight(1f),
                        style = AppleButtonStyle.FILLED,
                        size = AppleButtonSize.SMALL,
                        leadingIcon = Icons.Filled.QrCode2,
                        enabled = remoteCount > 0,
                    )
                } else {
                    AppleButton(
                        text = if (busy) "连接中…" else "一键开启穿透",
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        style = AppleButtonStyle.FILLED,
                        size = AppleButtonSize.LARGE,
                        enabled = !busy,
                        leadingIcon = Icons.Filled.PlayArrow,
                    )
                }
            }
        }
    }
}

// ==================== FRP 档案卡片 ====================

@Composable
private fun FrpProfileCard(
    profile: FrpProfileEntity,
    runtime: com.dirtunnel.feature.tunnel.runtime.TunnelRuntime?,
    busy: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShowQr: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = appleColors()
    val typography = appleTypography()
    val running = runtime?.state is ProcessState.Running
    val remoteCount = runtime?.proxies?.size ?: 0

    AppleGroupCard {
        Column(modifier = Modifier.fillMaxWidth().padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AppleText(profile.name, style = typography.headline, color = colors.textPrimary, maxLines = 1)
                    Spacer(Modifier.height(3.dp))
                    AppleText(
                        "${profile.serverAddr}:${profile.serverPort} · TLS ${if (profile.useTls) "开" else "关"}",
                        style = typography.footnote,
                        color = colors.textSecondary,
                        maxLines = 2,
                    )
                }
                TunnelStatusBadge(running = running, busy = busy)
            }

            if (running && remoteCount > 0) {
                Spacer(Modifier.height(8.dp))
                AppleText("已映射 $remoteCount 个端口", style = typography.footnote, color = colors.success)
            }

            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
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
                        text = "二维码",
                        onClick = onShowQr,
                        modifier = Modifier.weight(1f),
                        style = AppleButtonStyle.BORDERED,
                        size = AppleButtonSize.SMALL,
                        leadingIcon = Icons.Filled.QrCode2,
                        enabled = remoteCount > 0,
                    )
                } else {
                    AppleButton(
                        text = if (busy) "连接中…" else "启动",
                        onClick = onStart,
                        modifier = Modifier.weight(1f),
                        style = AppleButtonStyle.FILLED,
                        size = AppleButtonSize.SMALL,
                        enabled = !busy,
                        leadingIcon = Icons.Filled.PlayArrow,
                    )
                }
                AppleIconButton(onClick = onEdit, size = 36.dp) {
                    AppleIcon(Icons.Outlined.Share, contentDescription = "编辑", tint = colors.primary, size = 18.dp)
                }
                AppleIconButton(onClick = onDelete, size = 36.dp) {
                    AppleIcon(Icons.Filled.Delete, contentDescription = "删除", tint = colors.error, size = 18.dp)
                }
            }
        }
    }
}

// ==================== TCP 转发卡片 ====================

@Composable
private fun ForwardCard(
    forward: TcpForwardEntity,
    profiles: List<FrpProfileEntity>,
    onToggle: (Boolean) -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    val colors = appleColors()
    val typography = appleTypography()
    val profileLabel = when (forward.profileId) {
        PROFILE_PUBLIC -> "公共隧道"
        else -> profiles.firstOrNull { it.id == forward.profileId }?.name ?: "自建档案"
    }
    val remoteLabel = if (forward.remotePort > 0) "远端 ${forward.remotePort}" else "远端随机分配"

    AppleGroupCard {
        AppleCell(
            title = forward.name,
            subtitle = "本地 ${forward.localPort} → $remoteLabel · $profileLabel",
            showSeparator = false,
            onClick = onEdit,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    AppleSwitch(checked = forward.enabled, onCheckedChange = onToggle)
                    Spacer(Modifier.width(6.dp))
                    AppleIconButton(onClick = onDelete, size = 32.dp) {
                        AppleIcon(Icons.Filled.Delete, contentDescription = "删除", tint = colors.error, size = 18.dp)
                    }
                }
            },
        )
    }
}

// ==================== 状态徽标 ====================

@Composable
private fun TunnelStatusBadge(running: Boolean, busy: Boolean) {
    val colors = appleColors()
    when {
        busy -> Row(verticalAlignment = Alignment.CenterVertically) {
            AppleSpinner(size = 13.dp)
            Spacer(Modifier.width(6.dp))
            AppleText("连接中", style = appleTypography().caption2, color = colors.primary)
        }
        running -> Row(verticalAlignment = Alignment.CenterVertically) {
            StatusDot(color = colors.success, size = 8.dp)
            Spacer(Modifier.width(5.dp))
            AppleText("已连接", style = appleTypography().caption2, color = colors.success)
        }
        else -> AppleBadge("未连接", dotColor = colors.textSecondary)
    }
}
