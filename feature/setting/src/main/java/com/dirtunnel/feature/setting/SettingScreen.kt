package com.dirtunnel.feature.setting

import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DarkMode
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.filled.Security
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.Storage
import androidx.compose.material.icons.outlined.Wifi
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtunnel.core.ui.components.AppleAlertDialog
import com.dirtunnel.core.ui.components.AppleBadge
import com.dirtunnel.core.ui.components.AppleCell
import com.dirtunnel.core.ui.components.AppleGroupCard
import com.dirtunnel.core.ui.components.AppleIcon
import com.dirtunnel.core.ui.components.AppleInputDialog
import com.dirtunnel.core.ui.components.AppleOption
import com.dirtunnel.core.ui.components.AppleOptionSheet
import com.dirtunnel.core.ui.components.AppleScreen
import com.dirtunnel.core.ui.components.AppleSectionHeader
import com.dirtunnel.core.ui.components.AppleSpinner
import com.dirtunnel.core.ui.components.AppleSwitchCell
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.components.AppleTextField
import com.dirtunnel.core.ui.components.rememberHazeState
import com.dirtunnel.core.ui.theme.ThemeMode
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.components.AppleSlider
import com.dirtunnel.core.ui.theme.appleTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 全局设置（TECH 模块拆分 6：主题切换、全局设置、证书导入、高级开关；
 * REQ 4.5 三种主题模式；REQ 五 安全：电池白名单、通知权限、日志脱敏）。
 */
@Composable
fun SettingScreen(
    onBack: () -> Unit,
    viewModel: SettingViewModel = hiltViewModel(),
) {
    val hazeState = rememberHazeState()
    val colors = appleColors()
    val typography = appleTypography()
    val context = LocalContext.current

    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
    val batteryRestricted by viewModel.batteryRestricted.collectAsStateWithLifecycle()
    val binaries by viewModel.binaries.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val ddnsResult by viewModel.ddnsResult.collectAsStateWithLifecycle()
    val ddnsBusy by viewModel.ddnsBusy.collectAsStateWithLifecycle()
    val lastDdns by viewModel.lastDdnsUpdate.collectAsStateWithLifecycle()

    var navOpacityLocal by remember { mutableStateOf(settings.navBarOpacity) }
    LaunchedEffect(settings.navBarOpacity) { navOpacityLocal = settings.navBarOpacity }

    var showThemeSheet by remember { mutableStateOf(false) }
    var showDdnsProviderSheet by remember { mutableStateOf(false) }
    var showPublicServerDialog by remember { mutableStateOf(false) }
    var showCertDialog by remember { mutableStateOf(false) }

    AppleScreen(
        title = "设置",
        subtitle = "主题 · 保活 · 安全 · 高级",
        hazeState = hazeState,
        onBack = onBack,
        listState = null,
    ) { padding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                end = 16.dp,
                top = padding.calculateTopPadding(),
                bottom = padding.calculateBottomPadding(),
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            // ===== 主题（REQ 4.5） =====
            item {
                AppleSectionHeader("外观", footer = "支持跟随系统 / 浅色 / 深色三种模式；可调节底部导航栏不透明度。")
                AppleGroupCard {
                    AppleCell(
                        title = "主题模式",
                        value = themeMode.label,
                        leadingIcon = Icons.Outlined.DarkMode,
                        showChevron = true,
                        showSeparator = false,
                        onClick = { showThemeSheet = true },
                    )
                }
                Spacer(Modifier.height(10.dp))
                AppleGroupCard {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            AppleText(
                                "导航栏不透明度",
                                style = typography.headline,
                                color = colors.textPrimary,
                            )
                            Spacer(Modifier.weight(1f))
                            AppleText(
                                "${(navOpacityLocal * 100).toInt()}%",
                                style = typography.callout,
                                color = colors.textSecondary,
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        AppleSlider(
                            value = navOpacityLocal,
                            onValueChange = { navOpacityLocal = it },
                            valueRange = 0.3f..1f,
                            onValueChangeFinished = { viewModel.setNavBarOpacity(navOpacityLocal) },
                        )
                        AppleText(
                            "导航栏底色自动跟随主题背景色；越低越透明，越高文字越清晰。",
                            style = typography.caption,
                            color = colors.textSecondary,
                        )
                    }
                }
            }

            // ===== 保活（TECH 三 前台服务 + WAKE_LOCK） =====
            item {
                AppleSectionHeader(
                    "后台保活",
                    footer = "前台服务是安卓保活唯一可靠路径；开启 WakeLock 可防止息屏断流（更耗电）。",
                )
                AppleGroupCard {
                    AppleSwitchCell(
                        title = "WakeLock 防休眠",
                        subtitle = "服务运行时持锁，避免息屏断流（可选）",
                        checked = settings.wakeLockEnabled,
                        onCheckedChange = { viewModel.setWakeLockEnabled(it) },
                        leadingIcon = Icons.Outlined.Speed,
                    )
                    AppleSwitchCell(
                        title = "开机自动启动",
                        subtitle = "开机后自动拉起标记为「自动启动」的服务",
                        checked = settings.bootAutoStart,
                        onCheckedChange = { viewModel.setBootAutoStart(it) },
                    )
                    AppleSwitchCell(
                        title = "崩溃自动重启",
                        subtitle = "子进程异常退出时按退避策略自动重启",
                        checked = settings.autoRestartProcess,
                        onCheckedChange = { viewModel.setAutoRestart(it) },
                        showSeparator = false,
                    )
                }
            }

            // ===== 电池白名单引导 =====
            item {
                AppleGroupCard {
                    if (batteryRestricted == true) {
                        AppleCell(
                            title = "电池优化正在限制 DirTunnel",
                            subtitle = "息屏后系统可能冻结前台服务，导致隧道断连。点击加入「不限制电池用量」白名单。",
                            leadingIcon = Icons.Outlined.Storage,
                            leadingIconTint = colors.error,
                            showChevron = true,
                            showSeparator = false,
                            onClick = { context.startActivity(viewModel.batteryRequestIntent(context)) },
                        )
                    } else {
                        AppleCell(
                            title = "电池白名单",
                            subtitle = if (batteryRestricted == false) "已加入白名单，后台运行不受限" else "点击检查电池优化限制",
                            leadingIcon = Icons.Outlined.Storage,
                            showChevron = true,
                            showSeparator = false,
                            onClick = { context.startActivity(viewModel.batteryListIntent(context)) },
                        )
                    }
                }
            }

            // ===== 通知权限 =====
            item {
                AppleGroupCard {
                    AppleCell(
                        title = "通知权限",
                        subtitle = "前台服务通知需要通知权限，Android 13+ 必须授权",
                        leadingIcon = Icons.Outlined.Wifi,
                        showChevron = true,
                        showSeparator = false,
                        onClick = { context.startActivity(viewModel.notificationSettingsIntent(context)) },
                    )
                }
            }

            // ===== 安全 =====
            item {
                AppleSectionHeader(
                    "安全与隐私",
                    footer = "所有服务默认不开启匿名访问；账号密码与 token 使用 EncryptedSharedPreferences 加密存储。",
                )
                AppleGroupCard {
                    AppleSwitchCell(
                        title = "日志记录账号",
                        subtitle = "关闭后日志与导出中账号列脱敏为 ***（推荐关闭）",
                        checked = settings.recordSecretsInLog,
                        onCheckedChange = { viewModel.setRecordSecrets(it) },
                        leadingIcon = Icons.Filled.Security,
                    )
                    AppleSwitchCell(
                        title = "显示高阶协议",
                        subtitle = "开启后显示 Rsync / NFS 等 V1.2 高阶协议",
                        checked = settings.advancedFeaturesVisible,
                        onCheckedChange = { viewModel.setAdvancedVisible(it) },
                        showSeparator = false,
                    )
                }
            }

            // ===== 公共隧道端点 =====
            item {
                AppleSectionHeader(
                    "公共隧道端点",
                    footer = "使用内置默认端点，或覆盖为自有 frps 服务器。留空使用内置默认。",
                )
                AppleGroupCard {
                    AppleCell(
                        title = "服务器地址",
                        value = settings.publicTunnelServer.ifBlank { "内置默认" },
                        showChevron = true,
                        showSeparator = true,
                        onClick = { showPublicServerDialog = true },
                    )
                    AppleCell(
                        title = "远端配置地址 (JSON)",
                        value = settings.publicTunnelConfigUrl.ifBlank { "未配置" },
                        showChevron = true,
                        showSeparator = true,
                        onClick = { showPublicServerDialog = true },
                    )
                    AppleCell(
                        title = "URL 生成域名",
                        value = settings.publicTunnelHost.ifBlank { "自动" },
                        showChevron = true,
                        showSeparator = false,
                        onClick = { showPublicServerDialog = true },
                    )
                }
            }

            // ===== DDNS（V1.2） =====
            item {
                AppleSectionHeader(
                    "DDNS 动态域名",
                    footer = "V1.2 功能：隧道连通后自动将公网 IP 更新到域名。",
                )
                AppleGroupCard {
                    AppleSwitchCell(
                        title = "启用 DDNS",
                        subtitle = "隧道连接成功后自动更新",
                        checked = settings.ddnsEnabled,
                        onCheckedChange = { viewModel.setDdnsEnabled(it) },
                    )
                    AppleCell(
                        title = "服务商",
                        value = viewModel.ddnsProviders.firstOrNull { it.id == settings.ddnsProvider }?.label
                            ?: settings.ddnsProvider,
                        showChevron = true,
                        showSeparator = true,
                        onClick = { showDdnsProviderSheet = true },
                    )
                    AppleCell(
                        title = "域名 / 更新地址",
                        showSeparator = true,
                    ) {
                        AppleTextField(
                            value = settings.ddnsDomain,
                            onValueChange = { viewModel.setDdnsDomain(it) },
                            placeholder = "如：example.com 或更新 URL",
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(
                        title = "记录",
                        showSeparator = true,
                    ) {
                        AppleTextField(
                            value = settings.ddnsRecord,
                            onValueChange = { viewModel.setDdnsRecord(it) },
                            placeholder = "Cloudflare: zoneId/recordId；DNSPod: 子域；通用: 含 {ip} 的 URL",
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(
                        title = "测试更新",
                        subtitle = if (lastDdns > 0) {
                            "上次成功：" + SimpleDateFormat("MM-dd HH:mm", Locale.US).format(Date(lastDdns))
                        } else "尚未更新过",
                        showSeparator = false,
                        onClick = { viewModel.testDdns() },
                        trailing = {
                            if (ddnsBusy) {
                                AppleSpinner(size = 16.dp)
                            } else {
                                AppleBadge("测试", dotColor = colors.primary, textColor = colors.primary)
                            }
                        },
                    )
                }
            }

            // ===== 自定义证书（V1.1） =====
            item {
                AppleSectionHeader("HTTPS 自定义证书", footer = "V1.1 功能：为 FTPS / WebDAV over HTTPS 导入自签或自有证书。")
                AppleGroupCard {
                    AppleCell(
                        title = "证书目录",
                        value = settings.customCertPath.ifBlank { "未导入（使用自签）" },
                        leadingIcon = Icons.Outlined.Description,
                        showChevron = true,
                        showSeparator = false,
                        onClick = { showCertDialog = true },
                    )
                }
            }

            // ===== 协议组件 md5 校验（TECH 六.6） =====
            item {
                AppleSectionHeader(
                    "协议组件",
                    footer = "每个二进制在释放后做 md5 校验，防止被篡改（TECH 六.6）。",
                )
                AppleGroupCard {
                    binaries.forEachIndexed { i, b ->
                        val notBundled = !b.present && b.expectedMd5.isBlank()
                        AppleCell(
                            title = b.name,
                            subtitle = "${b.fileName} · v${b.version}" + when {
                                b.present -> ""
                                notBundled -> " · 本版未内置"
                                else -> " · 未释放"
                            },
                            showSeparator = i != binaries.lastIndex,
                            trailing = {
                                when {
                                    notBundled -> AppleBadge(
                                        "未内置",
                                        dotColor = colors.textSecondary,
                                        textColor = colors.textSecondary,
                                    )
                                    !b.present -> AppleBadge("缺失", dotColor = colors.warning, textColor = colors.warning)
                                    b.md5Match -> AppleBadge("已校验", dotColor = colors.success, textColor = colors.success)
                                    else -> AppleBadge("校验失败", dotColor = colors.error, textColor = colors.error)
                                }
                            },
                        )
                    }
                    AppleCell(
                        title = "重新校验",
                        subtitle = "自动释放未安装组件（assets → filesDir）并比对 md5",
                        showSeparator = false,
                        onClick = { viewModel.refreshBinaries() },
                    )
                }
            }

            item { Spacer(Modifier.height(8.dp)) }
        }
    }

    // ===== 主题切换 =====
    AppleOptionSheet(
        visible = showThemeSheet,
        onDismiss = { showThemeSheet = false },
        title = "主题模式",
        options = ThemeMode.entries.map { AppleOption(it.label, selected = it == themeMode) },
        onSelect = { index ->
            ThemeMode.entries.getOrNull(index)?.let { viewModel.setThemeMode(it) }
        },
    )

    // ===== DDNS 服务商 =====
    AppleOptionSheet(
        visible = showDdnsProviderSheet,
        onDismiss = { showDdnsProviderSheet = false },
        title = "DDNS 服务商",
        options = viewModel.ddnsProviders.map {
            AppleOption("${it.label} · ${it.hint}", selected = it.id == settings.ddnsProvider)
        },
        onSelect = { index ->
            viewModel.ddnsProviders.getOrNull(index)?.let { viewModel.setDdnsProvider(it.id) }
        },
    )

    // ===== 公共隧道端点 =====
    if (showPublicServerDialog) {
        AppleInputDialog(
            visible = true,
            title = "公共隧道服务器",
            initialText = settings.publicTunnelServer,
            placeholder = "留空使用内置默认端点",
            confirmText = "保存",
            onConfirm = { v ->
                val parts = v.split(":")
                val host = parts.getOrNull(0)?.trim().orEmpty()
                val port = parts.getOrNull(1)?.trim()?.toIntOrNull() ?: 7000
                viewModel.setPublicTunnelServer(host, port)
                showPublicServerDialog = false
            },
            onDismiss = { showPublicServerDialog = false },
        )
    }

    // ===== 证书导入 =====
    if (showCertDialog) {
        AppleInputDialog(
            visible = true,
            title = "证书目录路径",
            initialText = settings.customCertPath,
            placeholder = "/sdcard/certs（含 cert.pem 与 key.pem）",
            confirmText = "保存",
            onConfirm = { v ->
                viewModel.setCustomCertPath(v.trim())
                showCertDialog = false
            },
            onDismiss = { showCertDialog = false },
        )
    }

    // ===== DDNS 测试结果 =====
    ddnsResult?.let { result ->
        AppleAlertDialog(
            visible = true,
            title = "DDNS 更新结果",
            message = result + "\n方案：若失败，请检查服务商配置、token 是否正确、域名是否已生效。",
            confirmText = "知道了",
            onConfirm = { viewModel.clearDdnsResult() },
            dismissText = null,
        )
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
}
