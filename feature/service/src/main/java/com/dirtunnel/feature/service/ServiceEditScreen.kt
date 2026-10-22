package com.dirtunnel.feature.service

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Save
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtunnel.core.storage.model.AccessPermission
import com.dirtunnel.core.storage.model.PROFILE_PUBLIC
import com.dirtunnel.core.storage.model.ProtocolType
import com.dirtunnel.core.ui.components.AppleAlertDialog
import com.dirtunnel.core.ui.components.AppleButton
import com.dirtunnel.core.ui.components.AppleButtonSize
import com.dirtunnel.core.ui.components.AppleButtonStyle
import com.dirtunnel.core.ui.components.AppleCell
import com.dirtunnel.core.ui.components.AppleGroupCard
import com.dirtunnel.core.ui.components.AppleOption
import com.dirtunnel.core.ui.components.AppleOptionSheet
import com.dirtunnel.core.ui.components.AppleScreen
import com.dirtunnel.core.ui.components.AppleSectionHeader
import com.dirtunnel.core.ui.components.AppleSwitchCell
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.components.AppleTextField
import com.dirtunnel.core.ui.components.rememberHazeState
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography

/**
 * 服务实例编辑（REQ 4.2 属性全集：绑定目录、协议、端口、账号密码（匿名独立）、
 * 权限、穿透开关、自动启动；V1.1 证书；V1.2 限流；V1.2 配置模板）。
 * 核心三步流程：选择目录 → 添加协议服务 →（可选开启穿透）。
 */
@Composable
fun ServiceEditScreen(
    onBack: () -> Unit,
    viewModel: ServiceEditViewModel = hiltViewModel(),
) {
    val hazeState = rememberHazeState()
    val colors = appleColors()
    val typography = appleTypography()

    val form by viewModel.form.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val saved by viewModel.saved.collectAsStateWithLifecycle()
    val deleted by viewModel.deleted.collectAsStateWithLifecycle()
    val portCheck by viewModel.portCheck.collectAsStateWithLifecycle()
    val templates by viewModel.templates.collectAsStateWithLifecycle()
    val profiles by viewModel.tunnelProfiles.collectAsStateWithLifecycle()
    val advanced by viewModel.advancedVisible.collectAsStateWithLifecycle()

    val visibleProtocols = remember(advanced) {
        ProtocolType.entries.filter { advanced || !it.advanced }
    }

    var showProtocolSheet by remember { mutableStateOf(false) }
    var showPermissionSheet by remember { mutableStateOf(false) }
    var showProfileSheet by remember { mutableStateOf(false) }
    var showTemplateSheet by remember { mutableStateOf(false) }
    var showSaveTemplateDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(saved) {
        if (saved) {
            viewModel.consumeSaved()
            onBack()
        }
    }
    LaunchedEffect(deleted) {
        if (deleted) {
            viewModel.consumeDeleted()
            onBack()
        }
    }

    AppleScreen(
        title = if (form.isNew) "添加服务" else "编辑服务",
        subtitle = "端口 ${form.port.ifBlank { "—" }} · ${form.protocol.label}",
        hazeState = hazeState,
        onBack = onBack,
        actions = {
            AppleButton(
                text = "保存",
                onClick = { viewModel.save() },
                style = AppleButtonStyle.FILLED,
                size = AppleButtonSize.SMALL,
                leadingIcon = Icons.Filled.Save,
            )
        },
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
            // ===== 基本信息 =====
            item {
                AppleSectionHeader("基本信息")
                AppleGroupCard {
                    AppleCell(
                        title = "名称",
                        showSeparator = true,
                    ) {
                        AppleTextField(
                            value = form.name,
                            onValueChange = { v -> viewModel.update { it.copy(name = v) } },
                            placeholder = "如：照片共享",
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(
                        title = "协议类型",
                        value = form.protocol.label,
                        showChevron = true,
                        onClick = { showProtocolSheet = true },
                    )
                    AppleCell(
                        title = "监听端口",
                        showSeparator = portCheck != null,
                        trailing = {
                            AppleText(
                                "检测",
                                style = typography.callout,
                                color = colors.primary,
                            )
                        },
                        onClick = { viewModel.checkPort() },
                    ) {
                        AppleTextField(
                            value = form.port,
                            onValueChange = { v ->
                                viewModel.update { it.copy(port = v.filter(Char::isDigit)) }
                            },
                            placeholder = form.protocol.defaultPort.toString(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    if (portCheck != null) {
                        AppleText(
                            text = portCheck ?: "",
                            modifier = Modifier
                                .fillMaxWidth()
                                .background(colors.error.copy(alpha = 0.07f))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            style = typography.footnote,
                            color = colors.error,
                            maxLines = 6,
                        )
                    }
                    AppleCell(
                        title = "绑定目录",
                        subtitle = "在目录页管理（一个目录可绑定多个协议实例）",
                        showSeparator = false,
                    )
                }
            }

            // ===== 账号与权限 =====
            item {
                AppleSectionHeader("账号与权限", footer = "所有服务默认禁止匿名访问，匿名需手动开启")
                AppleGroupCard {
                    AppleCell(title = "登录账号", showSeparator = true) {
                        AppleTextField(
                            value = form.username,
                            onValueChange = { v -> viewModel.update { it.copy(username = v) } },
                            placeholder = "用户名",
                            enabled = !form.anonymous,
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(title = "登录密码", showSeparator = true) {
                        AppleTextField(
                            value = form.password,
                            onValueChange = { v ->
                                viewModel.update { it.copy(password = v, passwordTouched = true) }
                            },
                            placeholder = if (form.isNew) "设置密码" else "留空则不修改",
                            isPassword = true,
                            enabled = !form.anonymous,
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleSwitchCell(
                        title = "匿名访问",
                        subtitle = "开启后忽略账号密码，任何客户端可直接连接",
                        checked = form.anonymous,
                        onCheckedChange = { v -> viewModel.update { it.copy(anonymous = v) } },
                        showSeparator = true,
                    )
                    AppleCell(
                        title = "权限",
                        value = form.permission.label,
                        subtitle = form.permission.hint,
                        showChevron = true,
                        showSeparator = false,
                        onClick = { showPermissionSheet = true },
                    )
                }
            }

            // ===== 内网穿透 =====
            item {
                AppleSectionHeader("内网穿透", footer = "隧道兼容 TCP，公共隧道一键开启，或选择自建 FRP 档案")
                AppleGroupCard {
                    AppleSwitchCell(
                        title = "启用穿透",
                        subtitle = "开启后通过 FRP 生成外网访问地址",
                        checked = form.enableTunnel,
                        onCheckedChange = { v -> viewModel.update { it.copy(enableTunnel = v) } },
                        showSeparator = form.enableTunnel,
                    )
                    if (form.enableTunnel) {
                        val profileName = if (form.tunnelProfileId == PROFILE_PUBLIC) {
                            "公共隧道"
                        } else {
                            profiles.firstOrNull { it.id == form.tunnelProfileId }?.name ?: "公共隧道"
                        }
                        AppleCell(
                            title = "隧道档案",
                            value = profileName,
                            showChevron = true,
                            showSeparator = false,
                            onClick = { showProfileSheet = true },
                        )
                    }
                }
            }

            // ===== 行为 =====
            item {
                AppleSectionHeader("运行行为")
                AppleGroupCard {
                    AppleSwitchCell(
                        title = "自动启动",
                        subtitle = "APP 启动 / 开机后自动拉起该服务",
                        checked = form.autoStart,
                        onCheckedChange = { v -> viewModel.update { it.copy(autoStart = v) } },
                        showSeparator = form.protocol.supportsTls,
                    )
                    if (form.protocol.supportsTls) {
                        AppleSwitchCell(
                            title = "使用自定义证书",
                            subtitle = "V1.1：在设置中导入 cert.pem / key.pem 后启用 HTTPS",
                            checked = form.useCustomCert,
                            onCheckedChange = { v -> viewModel.update { it.copy(useCustomCert = v) } },
                            showSeparator = false,
                        )
                    }
                }
            }

            // ===== 访问控制与限流 =====
            item {
                AppleSectionHeader("访问控制", footer = "IP 白名单生效于本机代理层，支持 192.168.1.*、10.0.0.0/24、多条逗号分隔")
                AppleGroupCard {
                    AppleCell(title = "IP 白名单", showSeparator = true) {
                        AppleTextField(
                            value = form.whitelist,
                            onValueChange = { v -> viewModel.update { it.copy(whitelist = v) } },
                            placeholder = "留空 = 不限制",
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(title = "限速 KB/s", showSeparator = true) {
                        AppleTextField(
                            value = form.rateLimitKbps,
                            onValueChange = { v ->
                                viewModel.update { it.copy(rateLimitKbps = v.filter(Char::isDigit)) }
                            },
                            placeholder = "0 = 不限速",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                    AppleCell(title = "最大连接数", showSeparator = false) {
                        AppleTextField(
                            value = form.maxConnections,
                            onValueChange = { v ->
                                viewModel.update { it.copy(maxConnections = v.filter(Char::isDigit)) }
                            },
                            placeholder = "0 = 不限制",
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            showBorder = false,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            // ===== 配置模板（V1.2） =====
            item {
                AppleSectionHeader("配置模板（V1.2）")
                AppleGroupCard {
                    AppleCell(
                        title = "从模板应用",
                        subtitle = "一键复用历史配置（不含密码）",
                        showChevron = true,
                        showSeparator = true,
                        onClick = { showTemplateSheet = true },
                    )
                    AppleCell(
                        title = "存为模板",
                        showChevron = false,
                        showSeparator = false,
                        onClick = { showSaveTemplateDialog = true },
                    )
                }
            }

            // ===== 保存 / 删除 =====
            item {
                Spacer(Modifier.height(20.dp))
                AppleButton(
                    text = if (form.isNew) "创建服务" else "保存修改",
                    onClick = { viewModel.save() },
                    modifier = Modifier.fillMaxWidth(),
                    size = AppleButtonSize.LARGE,
                )
                Spacer(Modifier.height(10.dp))
                if (!form.isNew) {
                    AppleButton(
                        text = "删除服务",
                        onClick = { showDeleteConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        style = AppleButtonStyle.DESTRUCTIVE,
                        size = AppleButtonSize.LARGE,
                        leadingIcon = Icons.Filled.Delete,
                    )
                }
                Spacer(Modifier.height(8.dp))
            }
        }
    }

    // ===== 协议选择 =====
    if (showProtocolSheet) {
        AppleOptionSheet(
            visible = true,
            onDismiss = { showProtocolSheet = false },
            title = "选择协议",
            options = visibleProtocols.map {
                AppleOption("${it.label} · 默认端口 ${it.defaultPort}", selected = it == form.protocol)
            },
            onSelect = { i -> visibleProtocols.getOrNull(i)?.let(viewModel::setProtocol) },
        )
    }

    // ===== 权限选择 =====
    if (showPermissionSheet) {
        AppleOptionSheet(
            visible = true,
            onDismiss = { showPermissionSheet = false },
            title = "目录权限",
            options = AccessPermission.entries.map {
                AppleOption("${it.label} — ${it.hint}", selected = it == form.permission)
            },
            onSelect = { i -> viewModel.setPermission(AccessPermission.entries[i]) },
        )
    }

    // ===== 隧道档案选择 =====
    if (showProfileSheet) {
        AppleOptionSheet(
            visible = true,
            onDismiss = { showProfileSheet = false },
            title = "隧道档案",
            options = buildList {
                add(AppleOption("公共隧道（一键使用）", selected = form.tunnelProfileId == PROFILE_PUBLIC))
                profiles.forEach { p ->
                    add(AppleOption("自建 FRP：${p.name}（${p.serverAddr}:${p.serverPort}）", selected = form.tunnelProfileId == p.id))
                }
            },
            onSelect = { i ->
                val id = if (i == 0) PROFILE_PUBLIC else profiles[i - 1].id
                viewModel.update { it.copy(tunnelProfileId = id) }
            },
        )
    }

    // ===== 模板选择 =====
    if (showTemplateSheet) {
        AppleOptionSheet(
            visible = true,
            onDismiss = { showTemplateSheet = false },
            title = "从模板应用",
            options = if (templates.isEmpty()) {
                listOf(AppleOption("暂无模板，先“存为模板”", enabled = false))
            } else {
                templates.map { AppleOption("${it.name} · ${ProtocolType.fromName(it.protocol).label} :${it.port}") }
            },
            onSelect = { i ->
                templates.getOrNull(i)?.let { viewModel.applyTemplate(it) }
            },
        )
    }

    // ===== 存为模板命名 =====
    if (showSaveTemplateDialog) {
        TemplateNameDialog(
            initialName = "${form.protocol.label} :${form.port}",
            onConfirm = { name ->
                showSaveTemplateDialog = false
                viewModel.saveAsTemplate(name)
            },
            onDismiss = { showSaveTemplateDialog = false },
        )
    }

    // ===== 删除确认 =====
    if (showDeleteConfirm) {
        AppleAlertDialog(
            visible = true,
            title = "删除该服务？",
            message = "将移除服务配置与加密保存的密码，正在运行的实例会先被停止。",
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                showDeleteConfirm = false
                viewModel.delete()
            },
            onDismissRequest = { showDeleteConfirm = false },
        )
    }

    // ===== 校验失败提示（必附解决方案） =====
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

/** 模板命名输入弹窗（iOS 圆角弹窗风格）。 */
@Composable
private fun TemplateNameDialog(
    initialName: String,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    val colors = appleColors()
    var name by remember { mutableStateOf(initialName) }

    Dialog(onDismissRequest = onDismiss) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface)
                .padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            AppleText(
                "保存为模板",
                style = appleTypography().headline,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(14.dp))
            AppleTextField(
                value = name,
                onValueChange = { name = it },
                placeholder = "模板名称",
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                AppleButton(
                    text = "取消",
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    style = AppleButtonStyle.BORDERED,
                )
                AppleButton(
                    text = "保存",
                    onClick = { onConfirm(name) },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}
