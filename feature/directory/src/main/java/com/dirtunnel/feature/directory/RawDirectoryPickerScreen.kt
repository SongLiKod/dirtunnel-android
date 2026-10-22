package com.dirtunnel.feature.directory

import android.os.Environment
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowUpward
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.FolderOff
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtunnel.core.ui.components.AppleAlertDialog
import com.dirtunnel.core.ui.components.AppleButton
import com.dirtunnel.core.ui.components.AppleButtonSize
import com.dirtunnel.core.ui.components.AppleCell
import com.dirtunnel.core.ui.components.AppleEmptyState
import com.dirtunnel.core.ui.components.AppleGroupCard
import com.dirtunnel.core.ui.components.AppleScreen
import com.dirtunnel.core.ui.components.AppleSectionHeader
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.components.rememberHazeState
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * 受限目录直选：Android 15+ 系统文件选择器（SAF）禁选内部存储根目录与整个
 * Download 目录（Android 11+ 另禁 Android/data、Android/obb），本页面经
 * 「所有文件访问」权限直接浏览原始文件系统路径，选中即以「直连模式」添加
 * （treeUri 为空、无需 SAF 授权）。
 */
@Composable
fun RawDirectoryPickerScreen(
    onBack: () -> Unit,
    viewModel: DirectoryViewModel = hiltViewModel(),
) {
    val colors = appleColors()
    val typography = appleTypography()
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()

    val alert by viewModel.alert.collectAsStateWithLifecycle()

    val storageRoot = remember {
        runCatching { Environment.getExternalStorageDirectory().absolutePath }
            .getOrDefault("/storage/emulated/0")
    }
    var path by remember { mutableStateOf(storageRoot) }
    var refreshTick by remember { mutableIntStateOf(0) }
    var dirs by remember { mutableStateOf<List<String>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }

    LaunchedEffect(path, refreshTick) {
        loading = true
        dirs = withContext(Dispatchers.IO) {
            runCatching {
                File(path).listFiles()
                    ?.filter { it.isDirectory }
                    ?.sortedBy { it.name.lowercase() }
                    ?.map { it.absolutePath }
                    .orEmpty()
            }.getOrDefault(emptyList())
        }
        loading = false
    }

    // 从「所有文件访问」授权页返回时刷新列表与权限状态
    LifecycleResumeEffect(Unit) {
        refreshTick++
        viewModel.refreshOnResume()
        onPauseOrDispose { }
    }

    // 权限状态读取挂在 refreshTick 上：授权页返回 → tick++ → 重组并重新读取
    val hasAccess = refreshTick.let { viewModel.hasAllFilesAccess() }

    AppleScreen(
        title = "受限目录直选",
        subtitle = "内部存储" + path.removePrefix(storageRoot).ifBlank { "/" },
        hazeState = hazeState,
        listState = listState,
        onBack = onBack,
        hasBottomBar = true,
        bottomBar = {
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                AppleButton(
                    text = "选择当前目录",
                    onClick = {
                        viewModel.addDirectoryRaw(path)
                        onBack()
                    },
                    modifier = Modifier.fillMaxWidth(),
                    leadingIcon = Icons.Outlined.Folder,
                )
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
        ) {
            item {
                AppleSectionHeader(
                    "说明",
                    footer = "安卓 15+ 系统文件选择器禁选：内部存储根目录、整个 Download 目录及其子目录；" +
                        "安卓 11+ 另禁 Android/data、Android/obb。本页面经「所有文件访问」权限" +
                        "直接浏览原始路径，选中即以直连方式添加（无需 SAF 授权）。",
                )
            }

            if (!hasAccess) {
                item {
                    AppleGroupCard {
                        AppleCell(
                            title = "未授予「所有文件访问」",
                            subtitle = "目录列表可能为空；点击右侧按钮前往系统设置开启",
                            leadingIcon = Icons.Outlined.FolderOff,
                            showSeparator = false,
                            trailing = {
                                AppleButton(
                                    text = "去授权",
                                    onClick = { viewModel.requestAllFilesAccess() },
                                    size = AppleButtonSize.SMALL,
                                )
                            },
                            onClick = { viewModel.requestAllFilesAccess() },
                        )
                    }
                }
            }

            val parent = File(path).parent
            if (parent != null && parent != path && path != storageRoot && path != "/") {
                item {
                    AppleGroupCard {
                        AppleCell(
                            title = "返回上一级",
                            subtitle = parent,
                            leadingIcon = Icons.Outlined.ArrowUpward,
                            showSeparator = false,
                            onClick = { path = parent },
                        )
                    }
                }
            }

            if (loading) {
                item {
                    AppleText(
                        "正在读取目录…",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                        style = typography.footnote,
                        color = colors.textSecondary,
                    )
                }
            } else if (dirs.isEmpty()) {
                item {
                    AppleGroupCard {
                        AppleEmptyState(
                            title = if (hasAccess) "此目录下没有子目录" else "无法读取目录内容",
                            message = if (hasAccess) "可直接选择当前目录，或返回上一级。"
                            else "请先授予「所有文件访问」权限后返回本页。",
                        )
                    }
                }
            } else {
                items(dirs, key = { it }) { dir ->
                    AppleGroupCard {
                        AppleCell(
                            title = File(dir).name,
                            subtitle = dir,
                            leadingIcon = Icons.Outlined.Folder,
                            showSeparator = dir != dirs.last(),
                            onClick = { path = dir },
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(12.dp)) }
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
}
