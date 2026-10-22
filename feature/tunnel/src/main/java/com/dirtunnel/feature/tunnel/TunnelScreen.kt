package com.dirtunnel.feature.tunnel

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.QrCode2
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.Cloud
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.material.icons.outlined.Router
import androidx.compose.material.icons.outlined.Share
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.delay
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
import com.dirtunnel.core.ui.components.AppleTextField
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
    val endpointEditor by viewModel.endpointEditor.collectAsStateWithLifecycle()
    val busyId by viewModel.busyProfileId.collectAsStateWithLifecycle()
    val ipv6Addresses by viewModel.ipv6Addresses.collectAsStateWithLifecycle()
    val runningServices by viewModel.runningServices.collectAsStateWithLifecycle()

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
            AppleIconButton(onClick = {
                viewModel.refreshEndpoint()
                viewModel.refreshIpv6()
            }) {
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
                    runtime = publicRuntime,
                    onStart = { viewModel.startPublic() },
                    onStop = { viewModel.stopProfile(PROFILE_PUBLIC) },
                    onShowQr = { viewModel.showQr(PROFILE_PUBLIC) },
                )
                Spacer(Modifier.height(8.dp))
                AppleGroupCard {
                    AppleCell(
                        title = "公共隧道服务器",
                        subtitle = endpoint?.let { ep ->
                            if (ep.serverAddr.isBlank()) "未配置 — 点击填入自有 frps 服务器"
                            else "${ep.serverAddr}:${ep.serverPort} · ${ep.source}"
                        } ?: "获取中…",
                        leadingIcon = Icons.Outlined.Router,
                        showChevron = true,
                        onClick = { viewModel.openEndpointEditor() },
                    )
                }
            }

            // ===== 公网直连（IPv6 · 零服务器真实可用） =====
            item {
                AppleSectionHeader(
                    "公网直连（IPv6 · 零配置）",
                    footer = "检测手机的全局 IPv6 地址并自动生成公网访问地址——不依赖任何服务器、完全真实可用。要求访问端设备也支持 IPv6（蜂窝网络通常具备）。",
                )
                AppleGroupCard {
                    Column(Modifier.fillMaxWidth().padding(14.dp)) {
                        val v6 = ipv6Addresses.firstOrNull()
                        when {
                            v6 == null -> {
                                AppleText(
                                    "当前网络未检测到全局 IPv6 地址。",
                                    style = typography.footnote,
                                    color = colors.textSecondary,
                                )
                                Spacer(Modifier.height(2.dp))
                                AppleText(
                                    "方案：切换到移动数据（通常分配 IPv6）或支持 IPv6 的 Wi-Fi，再点右上角刷新；也可在上方配置自有 frps 服务器走中转。",
                                    style = typography.caption,
                                    color = colors.textSecondary,
                                )
                            }
                            runningServices.isEmpty() -> {
                                AppleText(
                                    "已检测到 IPv6：[$v6]",
                                    style = typography.footnote,
                                    color = colors.success,
                                )
                                Spacer(Modifier.height(2.dp))
                                AppleText(
                                    "启动任一服务后，这里会自动生成可点击复制的公网访问地址。",
                                    style = typography.caption,
                                    color = colors.textSecondary,
                                )
                            }
                            else -> {
                                AppleText(
                                    "可从公网直接访问以下运行中服务：",
                                    style = typography.footnote,
                                    color = colors.textSecondary,
                                )
                                runningServices.forEach { svc ->
                                    val url = ipv6Url(svc, v6) ?: return@forEach
                                    AddressRow(label = "${svc.name}（${svc.protocol}）", address = url)
                                }
                            }
                        }
                    }
                }
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

    EndpointConfigDialog(
        state = endpointEditor,
        onSave = { s, p, t, h -> viewModel.saveEndpointEditor(s, p, t, h) },
        onDismiss = { viewModel.closeEndpointEditor() },
    )
}

// ==================== 公共隧道卡片 ====================

@Composable
private fun PublicTunnelCard(
    running: Boolean,
    busy: Boolean,
    endpoint: com.dirtunnel.core.network.PublicTunnelEndpoint?,
    runtime: com.dirtunnel.feature.tunnel.runtime.TunnelRuntime?,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onShowQr: () -> Unit,
) {
    val colors = appleColors()
    val typography = appleTypography()
    val mapped = runtime?.proxies?.count { (it.remotePort ?: 0) > 0 } ?: 0
    val host = endpoint?.let { it.hostForUrl.ifBlank { it.serverAddr } }
    val addresses = runtime?.proxies?.mapNotNull { p ->
        val port = p.remotePort ?: return@mapNotNull null
        if (port <= 0 || host == null) null else p.label to "tcp://$host:$port"
    } ?: emptyList()
    val lastError = runtime?.lastError

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
                        text = endpoint?.let { ep ->
                            if (ep.serverAddr.isBlank()) "未配置公网中转服务器 · 点击下方配置"
                            else "${ep.serverAddr}:${ep.serverPort} · ${ep.source}"
                        } ?: "正在获取端点…",
                        style = typography.footnote,
                        color = colors.textSecondary,
                        maxLines = 2,
                    )
                }
                TunnelStatusBadge(running = running, busy = busy)
            }

            if (mapped > 0) {
                Spacer(Modifier.height(10.dp))
                AppleText(
                    "已映射 $mapped 个端口到公网",
                    style = typography.footnote,
                    color = colors.success,
                )
                addresses.forEach { (label, addr) ->
                    AddressRow(label = label, address = addr)
                }
            } else if (running) {
                Spacer(Modifier.height(10.dp))
                AppleText(
                    "已启动 · 等待服务器分配端口…",
                    style = typography.footnote,
                    color = colors.warning,
                )
            }
            if (lastError != null && running && mapped == 0) {
                Spacer(Modifier.height(6.dp))
                AppleText(
                    "连接问题：$lastError",
                    style = typography.caption,
                    color = colors.error,
                    maxLines = 2,
                )
                Spacer(Modifier.height(2.dp))
                AppleText(
                    "方案：核对服务器地址/端口/token 与网络连通性（telnet frps 7000）；或改用下方「公网直连(IPv6)」零配置方式。",
                    style = typography.caption,
                    color = colors.textSecondary,
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
                        enabled = mapped > 0,
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
    val mapped = runtime?.proxies?.count { (it.remotePort ?: 0) > 0 } ?: 0
    val addresses = runtime?.proxies?.mapNotNull { p ->
        val port = p.remotePort ?: return@mapNotNull null
        if (port <= 0) null else p.label to "tcp://${profile.serverAddr}:$port"
    } ?: emptyList()
    val lastError = runtime?.lastError

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

            if (mapped > 0) {
                Spacer(Modifier.height(8.dp))
                AppleText("已映射 $mapped 个端口", style = typography.footnote, color = colors.success)
                addresses.forEach { (label, addr) ->
                    AddressRow(label = label, address = addr)
                }
            } else if (running) {
                Spacer(Modifier.height(8.dp))
                AppleText(
                    "已启动 · 等待 ${profile.serverAddr}:${profile.serverPort} 分配端口…",
                    style = typography.footnote,
                    color = colors.warning,
                )
            }
            if (lastError != null && running && mapped == 0) {
                Spacer(Modifier.height(6.dp))
                AppleText("连接问题：$lastError", style = typography.caption, color = colors.error, maxLines = 2)
                Spacer(Modifier.height(2.dp))
                AppleText(
                    "方案：核对 frps 地址/端口/token，确认手机与服务器网络连通（可先 telnet 服务器端口测试）。",
                    style = typography.caption,
                    color = colors.textSecondary,
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
                        style = AppleButtonStyle.BORDERED,
                        size = AppleButtonSize.SMALL,
                        leadingIcon = Icons.Filled.QrCode2,
                        enabled = mapped > 0,
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

/** 公共隧道服务器配置对话框（域名/IP、端口、token、外网 host；一键穿透端点可换） 。 */
@Composable
private fun EndpointConfigDialog(
    state: TunnelViewModel.EndpointEditor?,
    onSave: (String, String, String, String) -> Unit,
    onDismiss: () -> Unit,
) {
    if (state == null) return
    val colors = appleColors()
    val typography = appleTypography()
    var server by remember(state) { mutableStateOf(state.server) }
    var port by remember(state) { mutableStateOf(state.port) }
    var token by remember(state) { mutableStateOf(state.token) }
    var host by remember(state) { mutableStateOf(state.host) }

    Dialog(onDismissRequest = onDismiss) {
        AppleGroupCard {
            Column(
                Modifier.fillMaxWidth().padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                AppleText("公共隧道服务器", style = typography.headline, color = colors.textPrimary)
                AppleTextField(server, { server = it }, placeholder = "frps 域名或 IP")
                AppleTextField(port, { port = it }, placeholder = "端口（默认 7000）")
                AppleTextField(token, { token = it }, placeholder = "token（与 frps 服务端 authentication_token 一致）", isPassword = true)
                AppleTextField(host, { host = it }, placeholder = "外网访问域名/IP（可选）")
                AppleText(
                    "自有 frps：在服务器执行 frps -c frps.toml 即得地址/端口/token。",
                    style = typography.caption,
                    color = colors.textSecondary,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    AppleButton(
                        "取消", onDismiss,
                        Modifier.weight(1f),
                        style = AppleButtonStyle.BORDERED, size = AppleButtonSize.SMALL,
                    )
                    AppleButton(
                        "保存", { onSave(server, port, token, host) },
                        Modifier.weight(1f),
                        style = AppleButtonStyle.FILLED, size = AppleButtonSize.SMALL,
                    )
                }
            }
        }
    }
}

/** IPv6 直连地址：协议 → URL（IPv6 字面量用方括号；RSync/NFS 等不适用的协议返回 null）。 */
private fun ipv6Url(svc: TunnelViewModel.RunningService, v6: String): String? {
    val scheme = when (svc.protocol.uppercase()) {
        "FTP" -> "ftp"
        "FTPS" -> "ftps"
        "SFTP" -> "sftp"
        "HTTP" -> "http"
        "HTTPS" -> "https"
        "WEBDAV" -> "http"
        "SMB" -> "smb"
        else -> null
    } ?: return null
    return "$scheme://[$v6]:${svc.port}"
}

/** 外网地址行：点击复制（REQ 4.3.1 地址可复制）。 */
@Composable
private fun AddressRow(label: String, address: String) {
    val colors = appleColors()
    val typography = appleTypography()
    val clipboard = LocalClipboardManager.current
    var copied by remember { mutableStateOf(false) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(colors.primary.copy(alpha = 0.07f))
            .clickable {
                clipboard.setText(AnnotatedString(address))
                copied = true
            }
            .padding(horizontal = 10.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            AppleText(label, style = typography.caption, color = colors.textSecondary, maxLines = 1)
            AppleText(address, style = typography.footnote, color = colors.textPrimary, maxLines = 1)
        }
        Spacer(Modifier.width(8.dp))
        AppleIcon(
            imageVector = if (copied) Icons.Filled.Check else Icons.Outlined.ContentCopy,
            contentDescription = "复制地址",
            tint = if (copied) colors.success else colors.primary,
            size = 16.dp,
        )
    }
    if (copied) {
        LaunchedEffect(address) {
            delay(1_500)
            copied = false
        }
    }
}
