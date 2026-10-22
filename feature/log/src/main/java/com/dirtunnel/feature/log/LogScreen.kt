package com.dirtunnel.feature.log

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileDownload
import androidx.compose.material.icons.outlined.ListAlt
import androidx.compose.material.icons.outlined.Search
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtunnel.core.ui.components.AppleAlertDialog
import com.dirtunnel.core.ui.components.AppleBadge
import com.dirtunnel.core.ui.components.AppleButton
import com.dirtunnel.core.ui.components.AppleButtonSize
import com.dirtunnel.core.ui.components.AppleButtonStyle
import com.dirtunnel.core.ui.components.AppleEmptyState
import com.dirtunnel.core.ui.components.AppleGroupCard
import com.dirtunnel.core.ui.components.AppleIcon
import com.dirtunnel.core.ui.components.AppleIconButton
import com.dirtunnel.core.ui.components.AppleScreen
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.components.AppleTextField
import com.dirtunnel.core.ui.components.rememberHazeState
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 日志列表（REQ 4.4 连接日志 / 上传下载日志；REQ V1.1 日志导出；
 * REQ 五 · 安全 一键清空日志）。
 */
@Composable
fun LogScreen(
    onBack: () -> Unit,
    viewModel: LogViewModel = hiltViewModel(),
) {
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()
    val colors = appleColors()
    val typography = appleTypography()

    val tab by viewModel.tab.collectAsStateWithLifecycle()
    val range by viewModel.range.collectAsStateWithLifecycle()
    val keyword by viewModel.keyword.collectAsStateWithLifecycle()
    val rows by viewModel.rows.collectAsStateWithLifecycle()
    val connCount by viewModel.connectionCount.collectAsStateWithLifecycle()
    val transCount by viewModel.transferCount.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val exportRequested by viewModel.exportRequested.collectAsStateWithLifecycle()
    val recordSecrets by viewModel.recordSecrets.collectAsStateWithLifecycle()

    // CSV 导出：SAF 创建文档（V1.1 日志导出，无需存储权限）
    val appContext = androidx.compose.ui.platform.LocalContext.current
    val exportLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("text/csv"),
    ) { uri ->
        if (uri != null) {
            val content = viewModel.csvContent()
            try {
                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    out.write(content.toByteArray(Charsets.UTF_8))
                    out.flush()
                }
            } catch (t: Throwable) {
                // 写入失败时静默（SAF 已提示系统错误）
            }
        }
        viewModel.consumeExportRequest()
    }

    LaunchedEffect(exportRequested) {
        if (exportRequested) {
            exportLauncher.launch(viewModel.exportFileName())
        }
    }

    AppleScreen(
        title = "日志",
        subtitle = "连接记录 · 流量审计 · CSV 导出",
        hazeState = hazeState,
        onBack = onBack,
        listState = listState,
        actions = {
            AppleIconButton(onClick = { viewModel.showExportHint() }) {
                AppleIcon(Icons.Filled.FileDownload, contentDescription = "导出 CSV", tint = colors.primary, size = 22.dp)
            }
            AppleIconButton(onClick = { viewModel.requestClear() }) {
                AppleIcon(Icons.Filled.Delete, contentDescription = "清空日志", tint = colors.error, size = 22.dp)
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
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            // ===== Tab 切换（连接 / 流量） =====
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .background(colors.surface)
                        .padding(3.dp),
                ) {
                    LogTab.entries.forEach { t ->
                        val selected = t == tab
                        val interaction = remember { MutableInteractionSource() }
                        Column(
                            modifier = Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(8.dp))
                                .background(if (selected) colors.primary else Color.Transparent)
                                .clickable(
                                    interactionSource = interaction,
                                    indication = null,
                                ) { viewModel.selectTab(t) }
                                .padding(vertical = 8.dp),
                            horizontalAlignment = Alignment.CenterHorizontally,
                        ) {
                            AppleText(
                                t.label,
                                style = typography.subhead,
                                color = if (selected) colors.onPrimary else colors.textSecondary,
                            )
                        }
                    }
                }
            }

            // ===== 搜索 + 时间范围 =====
            item {
                AppleTextField(
                    value = keyword,
                    onValueChange = { viewModel.setKeyword(it) },
                    placeholder = "搜索服务名 / IP / 协议",
                    modifier = Modifier.fillMaxWidth(),
                    trailing = {
                        if (keyword.isNotEmpty()) {
                            AppleIconButton(onClick = { viewModel.setKeyword("") }, size = 28.dp) {
                                AppleIcon(Icons.Outlined.Search, contentDescription = "清除", tint = colors.textSecondary, size = 16.dp)
                            }
                        }
                    },
                )
            }

            item {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    LogRange.entries.forEach { r ->
                        val selected = r == range
                        AppleButton(
                            text = r.label,
                            onClick = { viewModel.selectRange(r) },
                            style = if (selected) AppleButtonStyle.FILLED else AppleButtonStyle.BORDERED,
                            size = AppleButtonSize.SMALL,
                        )
                    }
                }
            }

            // ===== 计数概览 =====
            item {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    AppleText(
                        "共 ${rows.size} 条 · 连接 $connCount / 流量 $transCount",
                        style = typography.footnote,
                        color = colors.textSecondary,
                    )
                    if (!recordSecrets) {
                        AppleBadge("账号已脱敏", dotColor = colors.success)
                    }
                }
            }

            // ===== 列表 =====
            if (rows.isEmpty()) {
                item {
                    AppleGroupCard {
                        AppleEmptyState(
                            title = "暂无日志",
                            message = "启动任意协议服务并有客户端访问后，这里会记录连接与上传下载明细。",
                            icon = Icons.Outlined.ListAlt,
                        )
                    }
                }
            } else {
                items(rows, key = { "${tab.name}_${it.id}" }) { row ->
                    LogRowCard(row)
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
            destructiveConfirm = it.destructive,
            onConfirm = { viewModel.dismissAlert() },
            dismissText = if (it.destructive) "取消" else null,
        )
    }
}

@Composable
private fun LogRowCard(row: LogRow) {
    val colors = appleColors()
    val typography = appleTypography()
    val time = remember(row.timestamp) {
        SimpleDateFormat("MM-dd HH:mm:ss", Locale.US).format(Date(row.timestamp))
    }

    AppleGroupCard {
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppleText(
                    row.serviceName.ifBlank { "—" },
                    style = typography.subhead,
                    color = if (row.isError) colors.error else colors.textPrimary,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                )
                AppleText(time, style = typography.caption2, color = colors.textSecondary, maxLines = 1)
            }
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                AppleBadge(
                    row.event,
                    dotColor = when {
                        row.isError -> colors.error
                        row.event == "CONNECT" || row.event == "上传" -> colors.success
                        else -> colors.textSecondary
                    },
                )
                Spacer(Modifier.width(8.dp))
                AppleText(
                    "${row.protocol} · ${row.clientIp}",
                    style = typography.footnote,
                    color = colors.textSecondary,
                    maxLines = 1,
                )
            }
            if (row.detail.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                AppleText(
                    if (row.event == "上传" || row.event == "下载") "${row.detail} 字节" else row.detail,
                    style = typography.caption,
                    color = colors.textSecondary,
                    maxLines = 2,
                )
            }
        }
    }
}
