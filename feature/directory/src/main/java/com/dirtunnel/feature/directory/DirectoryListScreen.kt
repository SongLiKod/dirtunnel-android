package com.dirtunnel.feature.directory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.dirtunnel.core.storage.entity.DirectoryEntity
import com.dirtunnel.core.ui.components.AppleAlertDialog
import com.dirtunnel.core.ui.components.AppleBadge
import com.dirtunnel.core.ui.components.AppleCell
import com.dirtunnel.core.ui.components.AppleEmptyState
import com.dirtunnel.core.ui.components.AppleGroupCard
import com.dirtunnel.core.ui.components.AppleIcon
import com.dirtunnel.core.ui.components.AppleIconButton
import com.dirtunnel.core.ui.components.AppleInputDialog
import com.dirtunnel.core.ui.components.AppleOption
import com.dirtunnel.core.ui.components.AppleOptionSheet
import com.dirtunnel.core.ui.components.AppleScreen
import com.dirtunnel.core.ui.components.AppleSectionHeader
import com.dirtunnel.core.ui.components.AppleText
import com.dirtunnel.core.ui.components.rememberHazeState
import com.dirtunnel.core.ui.theme.appleColors
import com.dirtunnel.core.ui.theme.appleTypography

/**
 * 目录列表（REQ 4.1：浏览、选择手机任意目录；目录收藏一键复用）。
 * 核心流程第一步：选择目录。
 */
@Composable
fun DirectoryListScreen(
    onOpenDirectory: (directoryId: Long) -> Unit,
    viewModel: DirectoryViewModel = hiltViewModel(),
) {
    val hazeState = rememberHazeState()
    val listState = rememberLazyListState()
    val colors = appleColors()

    val directories by viewModel.directories.collectAsStateWithLifecycle()
    val counts by viewModel.serviceCounts.collectAsStateWithLifecycle()
    val issues by viewModel.issues.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val optionTarget by viewModel.optionTarget.collectAsStateWithLifecycle()

    var showRenameDialog by remember { mutableStateOf(false) }
    var showDeleteConfirm by remember { mutableStateOf(false) }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        viewModel.onDirectoryPicked(uri)
    }

    val favorites = directories.filter { it.favorite }
    val others = directories.filterNot { it.favorite }

    AppleScreen(
        title = "目录",
        subtitle = if (directories.isEmpty()) "选择任意目录开始" else "共 ${directories.size} 个 · 收藏 ${favorites.size}",
        hazeState = hazeState,
        listState = listState,
        actions = {
            AppleIconButton(onClick = { picker.launch(null) }) {
                AppleIcon(Icons.Filled.Add, contentDescription = "添加目录", tint = colors.primary, size = 24.dp)
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
            if (directories.isEmpty()) {
                item {
                    AppleGroupCard {
                        AppleEmptyState(
                            title = "还没有目录",
                            message = "点击右上角 + 选择手机上的任意文件夹（内部存储 / SD 卡 / 可访问分区），授权后即可挂载为网络服务。",
                            icon = Icons.Outlined.Folder,
                        )
                    }
                }
                return@LazyColumn
            }

            if (favorites.isNotEmpty()) {
                item {
                    AppleSectionHeader("收藏（一键复用）")
                    AppleGroupCard {
                        favorites.forEachIndexed { i, dir ->
                            DirectoryRow(
                                dir = dir,
                                count = counts[dir.id] ?: 0,
                                issue = issues[dir.id] ?: DirectoryIssue.OK,
                                showSeparator = i != favorites.lastIndex,
                                onOpen = { onOpenDirectory(dir.id) },
                                onToggleFavorite = { viewModel.toggleFavorite(dir) },
                                onOptions = { viewModel.openOptions(dir) },
                            )
                        }
                    }
                }
            }

            item {
                AppleSectionHeader("全部目录")
                AppleGroupCard {
                    others.forEachIndexed { i, dir ->
                        DirectoryRow(
                            dir = dir,
                            count = counts[dir.id] ?: 0,
                            issue = issues[dir.id] ?: DirectoryIssue.OK,
                            showSeparator = i != others.lastIndex,
                            onOpen = { onOpenDirectory(dir.id) },
                            onToggleFavorite = { viewModel.toggleFavorite(dir) },
                            onOptions = { viewModel.openOptions(dir) },
                        )
                    }
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                AppleText(
                    "提示：系统可能在重启后回收未持久化的授权，DirTunnel 已对所有目录执行持久化授权。",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = appleTypography().caption,
                    color = colors.textSecondary,
                )
            }
        }
    }

    // ===== 目录操作菜单 =====
    optionTarget?.let { dir ->
        AppleOptionSheet(
            visible = true,
            onDismiss = { viewModel.closeOptions() },
            title = dir.name,
            options = listOf(
                AppleOption(if (dir.favorite) "取消收藏" else "收藏（一键复用）"),
                AppleOption("重命名"),
                AppleOption("重新授权", enabled = issues[dir.id] == DirectoryIssue.PERMISSION_LOST || issues[dir.id] == null),
                AppleOption("删除目录（保留文件）", destructive = true),
            ),
            onSelect = { i ->
                when (i) {
                    0 -> viewModel.toggleFavorite(dir)
                    1 -> showRenameDialog = true
                    2 -> picker.launch(null)
                    3 -> showDeleteConfirm = true
                }
            },
        )
    }

    // ===== 重命名 =====
    if (showRenameDialog && optionTarget != null) {
        AppleInputDialog(
            visible = true,
            title = "重命名目录",
            initialText = optionTarget?.name ?: "",
            placeholder = "目录名称",
            onConfirm = { name ->
                optionTarget?.let { viewModel.rename(it, name) }
                showRenameDialog = false
                viewModel.closeOptions()
            },
            onDismiss = {
                showRenameDialog = false
                viewModel.closeOptions()
            },
        )
    }

    // ===== 删除确认 =====
    if (showDeleteConfirm && optionTarget != null) {
        AppleAlertDialog(
            visible = true,
            title = "删除目录记录？",
            message = "仅移除 DirTunnel 中的记录与授权，不会删除手机上的任何文件。其下服务实例会一并移除。",
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                optionTarget?.let { viewModel.delete(it) }
                showDeleteConfirm = false
                viewModel.closeOptions()
            },
            onDismissRequest = {
                showDeleteConfirm = false
                viewModel.closeOptions()
            },
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

@Composable
private fun DirectoryRow(
    dir: DirectoryEntity,
    count: Int,
    issue: DirectoryIssue,
    showSeparator: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOptions: () -> Unit,
) {
    val colors = appleColors()
    val typography = appleTypography()

    val issueText = when (issue) {
        DirectoryIssue.OK -> null
        DirectoryIssue.PERMISSION_LOST -> "授权失效 — 方案：重新授权"
        DirectoryIssue.NO_FILESYSTEM_PATH -> "分区不可挂载 — 方案：更换标准目录"
    }

    AppleCell(
        title = dir.name,
        subtitle = issueText ?: (dir.fsPath ?: dir.treeUri),
        leadingIcon = Icons.Outlined.Folder,
        showChevron = true,
        showSeparator = showSeparator,
        onClick = onOpen,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (issue != DirectoryIssue.OK) {
                    AppleBadge(
                        text = if (issue == DirectoryIssue.PERMISSION_LOST) "失效" else "受限",
                        dotColor = colors.error,
                        textColor = colors.error,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                AppleText(
                    "$count 服务",
                    style = typography.caption,
                    color = colors.textSecondary,
                )
                Spacer(Modifier.width(4.dp))
                AppleIconButton(onClick = onToggleFavorite, size = 32.dp) {
                    AppleIcon(
                        imageVector = if (dir.favorite) Icons.Filled.Star else Icons.Outlined.StarBorder,
                        contentDescription = "收藏",
                        tint = if (dir.favorite) colors.warning else colors.textSecondary,
                        size = 18.dp,
                    )
                }
                AppleIconButton(onClick = onOptions, size = 32.dp) {
                    AppleText("⋯", style = typography.body, color = colors.textSecondary)
                }
            }
        },
    )
}
