package com.dirtunnel.feature.directory

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.StarBorder
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
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
 * 便捷操作：左滑删除、行内一键修复（重新授权 / 所有文件访问）、回前台自动刷新状态。
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
    val runningByDir by viewModel.runningByDir.collectAsStateWithLifecycle()
    val issues by viewModel.issues.collectAsStateWithLifecycle()
    val alert by viewModel.alert.collectAsStateWithLifecycle()
    val optionTarget by viewModel.optionTarget.collectAsStateWithLifecycle()

    var showRenameDialog by remember { mutableStateOf(false) }
    var deleteTarget by remember { mutableStateOf<DirectoryEntity?>(null) }

    // 从系统权限页/选择器返回时自动刷新授权状态
    LifecycleResumeEffect(Unit) {
        viewModel.refreshOnResume()
        onPauseOrDispose { }
    }

    val picker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree(),
    ) { uri ->
        viewModel.onDirectoryPicked(uri)
    }

    val favorites = directories.filter { it.favorite }
    val others = directories.filterNot { it.favorite }
    val runningTotal = runningByDir.values.sumOf { it.count }

    AppleScreen(
        title = "目录",
        subtitle = when {
            directories.isEmpty() -> "选择任意目录开始"
            runningTotal > 0 -> "共 ${directories.size} 个 · 收藏 ${favorites.size} · 运行中 $runningTotal 服务"
            else -> "共 ${directories.size} 个 · 收藏 ${favorites.size}"
        },
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
                    AppleSectionHeader("收藏（一键复用）· 左滑可删除")
                    AppleGroupCard {
                        favorites.forEachIndexed { i, dir ->
                            SwipeToDeleteRow(onDelete = { deleteTarget = dir }) {
                                DirectoryRow(
                                    dir = dir,
                                    count = counts[dir.id] ?: 0,
                                    running = runningByDir[dir.id],
                                    issue = issues[dir.id] ?: DirectoryIssue.OK,
                                    showSeparator = i != favorites.lastIndex,
                                    onOpen = { onOpenDirectory(dir.id) },
                                    onToggleFavorite = { viewModel.toggleFavorite(dir) },
                                    onOptions = { viewModel.openOptions(dir) },
                                    onReauth = {
                                        viewModel.beginReauth(dir)
                                        picker.launch(null)
                                    },
                                    onGrantAllFiles = { viewModel.requestAllFilesAccess() },
                                )
                            }
                        }
                    }
                }
            }

            item {
                AppleSectionHeader("全部目录 · 左滑可删除")
                AppleGroupCard {
                    others.forEachIndexed { i, dir ->
                        SwipeToDeleteRow(onDelete = { deleteTarget = dir }) {
                            DirectoryRow(
                                dir = dir,
                                count = counts[dir.id] ?: 0,
                                running = runningByDir[dir.id],
                                issue = issues[dir.id] ?: DirectoryIssue.OK,
                                showSeparator = i != others.lastIndex,
                                onOpen = { onOpenDirectory(dir.id) },
                                onToggleFavorite = { viewModel.toggleFavorite(dir) },
                                onOptions = { viewModel.openOptions(dir) },
                                onReauth = {
                                    viewModel.beginReauth(dir)
                                    picker.launch(null)
                                },
                                onGrantAllFiles = { viewModel.requestAllFilesAccess() },
                            )
                        }
                    }
                }
            }

            item {
                Spacer(Modifier.height(6.dp))
                AppleText(
                    "提示：系统可能在重启后回收未持久化的授权，DirTunnel 已对所有目录执行持久化授权；" +
                        "权限变更后回到本页会自动刷新状态。",
                    modifier = Modifier.padding(horizontal = 8.dp),
                    style = appleTypography().caption,
                    color = colors.textSecondary,
                )
            }
        }
    }

    // ===== 目录操作菜单 =====
    optionTarget?.let { dir ->
        val issue = issues[dir.id] ?: DirectoryIssue.OK
        val actions = mutableListOf<() -> Unit>()
        val options = buildList {
            add(AppleOption(if (dir.favorite) "取消收藏" else "收藏（一键复用）"))
            actions += { viewModel.toggleFavorite(dir) }
            add(AppleOption("重命名"))
            actions += { showRenameDialog = true }
            if (dir.treeUri.isNotBlank()) {
                add(AppleOption("重新授权"))
                actions += {
                    viewModel.closeOptions()
                    viewModel.beginReauth(dir)
                    picker.launch(null)
                }
            }
            if (issue == DirectoryIssue.ALL_FILES_DENIED) {
                add(AppleOption("开启「所有文件访问」"))
                actions += {
                    viewModel.closeOptions()
                    viewModel.requestAllFilesAccess()
                }
            }
            add(AppleOption("删除目录（保留文件）", destructive = true))
            actions += { deleteTarget = dir }
        }
        AppleOptionSheet(
            visible = true,
            onDismiss = { viewModel.closeOptions() },
            title = dir.name,
            options = options,
            onSelect = { i -> viewModel.closeOptions(); actions[i].invoke() },
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

    // ===== 删除确认（左滑与菜单共用） =====
    deleteTarget?.let { dir ->
        val n = counts[dir.id] ?: 0
        AppleAlertDialog(
            visible = true,
            title = "删除“${dir.name}”？",
            message = buildString {
                append("仅移除 DirTunnel 中的记录与授权，不会删除手机上的任何文件。")
                if (n > 0) append("其下 $n 个服务实例会一并移除。")
            },
            confirmText = "删除",
            destructiveConfirm = true,
            onConfirm = {
                viewModel.delete(dir)
                deleteTarget = null
                viewModel.closeOptions()
            },
            onDismissRequest = {
                deleteTarget = null
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

/**
 * iOS 风格左滑删除：向左拖过阈值松手触发删除确认，背景红色删除层渐显。
 * 未过阈值自动回弹；纵向手势让位给列表滚动。
 */
@Composable
private fun SwipeToDeleteRow(
    onDelete: () -> Unit,
    content: @Composable () -> Unit,
) {
    val colors = appleColors()
    val typography = appleTypography()
    val threshold = 88f
    var target by remember { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(targetValue = target, label = "swipeDelete")
    val reveal = (-offset / threshold).coerceIn(0f, 1f)

    Box(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .matchParentSize()
                .background(colors.surface, RectangleShape)
                .alpha(reveal),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row(
                modifier = Modifier.padding(end = 22.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppleIcon(
                    imageVector = Icons.Outlined.Delete,
                    contentDescription = null,
                    tint = colors.error,
                    size = 20.dp,
                )
                Spacer(Modifier.width(6.dp))
                AppleText("删除", style = typography.subhead, color = colors.error)
            }
        }
        Box(
            Modifier
                .graphicsLayer { translationX = offset }
                .pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragEnd = {
                            if (target <= -threshold) {
                                target = 0f
                                onDelete()
                            } else {
                                target = 0f
                            }
                        },
                        onHorizontalDrag = { _, dragAmount ->
                            target = (target + dragAmount).coerceAtMost(0f)
                        },
                    )
                },
        ) {
            content()
        }
    }
}

@Composable
private fun DirectoryRow(
    dir: DirectoryEntity,
    count: Int,
    running: DirectoryViewModel.DirRunning?,
    issue: DirectoryIssue,
    showSeparator: Boolean,
    onOpen: () -> Unit,
    onToggleFavorite: () -> Unit,
    onOptions: () -> Unit,
    onReauth: () -> Unit,
    onGrantAllFiles: () -> Unit,
) {
    val colors = appleColors()
    val typography = appleTypography()

    val issueText = when (issue) {
        DirectoryIssue.OK -> null
        DirectoryIssue.PERMISSION_LOST -> "授权失效 — 点右侧「重新授权」"
        DirectoryIssue.NO_FILESYSTEM_PATH -> "分区不可挂载 — 方案：更换标准目录"
        DirectoryIssue.ALL_FILES_DENIED -> "文件访问受限 — 点右侧「授权」开启"
    }

    val pathText = dir.fsPath ?: dir.treeUri
    val runningText = running?.names?.takeIf { it.isNotEmpty() }?.joinToString("、")
    val subtitleText = when {
        issueText != null -> issueText
        runningText != null -> "运行中：$runningText"
        else -> pathText
    }

    AppleCell(
        title = dir.name,
        subtitle = subtitleText,
        leadingIcon = Icons.Outlined.Folder,
        showChevron = true,
        showSeparator = showSeparator,
        onClick = onOpen,
        trailing = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                when (issue) {
                    DirectoryIssue.PERMISSION_LOST -> FixChip("重新授权", onClick = onReauth)
                    DirectoryIssue.ALL_FILES_DENIED -> FixChip("授权", onClick = onGrantAllFiles)
                    else -> {}
                }
                if (issue != DirectoryIssue.OK) {
                    AppleBadge(
                        text = when (issue) {
                            DirectoryIssue.PERMISSION_LOST -> "失效"
                            DirectoryIssue.ALL_FILES_DENIED -> "受限"
                            else -> "受限"
                        },
                        dotColor = colors.error,
                        textColor = colors.error,
                    )
                    Spacer(Modifier.width(6.dp))
                }
                AppleText(
                    text = if ((running?.count ?: 0) > 0) {
                        "${running!!.count}/$count 运行"
                    } else {
                        "$count 服务"
                    },
                    style = typography.caption,
                    color = if ((running?.count ?: 0) > 0) colors.success else colors.textSecondary,
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

/** 行内小修复按钮（异常必附方案，REQ 五）。 */
@Composable
private fun FixChip(text: String, onClick: () -> Unit) {
    val colors = appleColors()
    Box(
        Modifier
            .padding(end = 6.dp)
            .background(colors.error.copy(alpha = 0.12f), androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 8.dp, vertical = 4.dp),
    ) {
        AppleText(text, style = appleTypography().caption, color = colors.error)
    }
}
