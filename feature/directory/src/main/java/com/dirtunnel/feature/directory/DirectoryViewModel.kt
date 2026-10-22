package com.dirtunnel.feature.directory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.storage.dao.DirectoryDao
import com.dirtunnel.core.storage.entity.DirectoryEntity
import com.dirtunnel.core.storage.saf.SaFDocumentTree
import com.dirtunnel.core.ui.components.AppleAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 目录可用性（REQ 4.1 权限 / TECH 七.4 SAF 授权持久化）。 */
enum class DirectoryIssue { OK, PERMISSION_LOST, NO_FILESYSTEM_PATH }

/**
 * 目录管理（REQ 4.1：浏览选择任意目录、SAF 适配、收藏一键复用、状态校验）。
 */
@HiltViewModel
class DirectoryViewModel @Inject constructor(
    private val directoryDao: DirectoryDao,
    private val serviceDao: com.dirtunnel.core.storage.dao.ServiceDao,
    private val saf: SaFDocumentTree,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val directories: StateFlow<List<DirectoryEntity>> = directoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 每目录服务数量（目录卡片展示）。 */
    val serviceCounts: StateFlow<Map<Long, Int>> = serviceDao.observeAll()
        .map { list -> list.groupingBy { it.directoryId }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _issues = MutableStateFlow<Map<Long, DirectoryIssue>>(emptyMap())
    val issues: StateFlow<Map<Long, DirectoryIssue>> = _issues.asStateFlow()

    private val _alert = MutableStateFlow<AppleAlert?>(null)
    val alert: StateFlow<AppleAlert?> = _alert.asStateFlow()

    private val _optionTarget = MutableStateFlow<DirectoryEntity?>(null)
    val optionTarget: StateFlow<DirectoryEntity?> = _optionTarget.asStateFlow()

    init {
        refreshIssues()
    }

    /** 接收 SAF 选择器返回的 tree Uri（TECH 一 · SAF + 持久化授权）。 */
    fun onDirectoryPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) { saf.takePersistable(uri.toString(), write = true) }
            if (!ok) {
                _alert.value = AppleAlert(
                    "目录授权失败",
                    "方案：请在系统选择器中重新选择文件夹，并允许“读写”访问；部分 ROM 需手动勾选“允许”。",
                )
                return@launch
            }
            val treeUri = uri.toString()
            val existing = withContext(Dispatchers.IO) { directoryDao.getByUri(treeUri) }
            if (existing != null) {
                directoryDao.touch(existing.id)
                refreshIssues()
                _alert.value = AppleAlert("目录已存在", "该目录已添加，已为你刷新为最近使用。")
                return@launch
            }
            val name = withContext(Dispatchers.IO) {
                saf.displayName(treeUri) ?: "目录"
            }
            val fsPath = withContext(Dispatchers.IO) { saf.resolveFsPath(treeUri) }
            directoryDao.insert(
                DirectoryEntity(
                    name = name,
                    treeUri = treeUri,
                    fsPath = fsPath,
                    favorite = false,
                ),
            )
            refreshIssues()
            promptAllFilesAccessIfNeeded(fsPath)
        }
    }

    /** 校验全部目录的授权与可挂载性（REQ 4.6 权限不足告警数据源）。 */
    fun refreshIssues() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { directoryDao.getAll() }
            val map = list.associate { dir ->
                dir.id to when {
                    // 直连文件系统路径目录（treeUri 为空，如一键启动专用目录）：无需 SAF 授权，不得误报失效
                    dir.treeUri.isBlank() && dir.fsPath != null -> DirectoryIssue.OK
                    !saf.hasPersistable(dir.treeUri) -> DirectoryIssue.PERMISSION_LOST
                    dir.fsPath == null && withContext(Dispatchers.IO) { saf.resolveFsPath(dir.treeUri) } == null ->
                        DirectoryIssue.NO_FILESYSTEM_PATH
                    else -> DirectoryIssue.OK
                }
            }
            _issues.value = map
        }
    }

    fun toggleFavorite(dir: DirectoryEntity) {
        viewModelScope.launch {
            directoryDao.setFavorite(dir.id, !dir.favorite)
        }
    }

    fun openOptions(dir: DirectoryEntity) {
        _optionTarget.value = dir
    }

    fun closeOptions() {
        _optionTarget.value = null
    }

    fun rename(dir: DirectoryEntity, newName: String) {
        if (newName.isBlank()) return
        viewModelScope.launch {
            directoryDao.update(dir.copy(name = newName.trim()))
        }
    }

    fun delete(dir: DirectoryEntity) {
        viewModelScope.launch {
            withContext(Dispatchers.IO) { saf.releasePersistable(dir.treeUri) }
            directoryDao.delete(dir)
            refreshIssues()
        }
    }

    /**
     * 安卓 11+ 分区存储：文件服务器以原始路径读写共享目录，必须有「所有文件访问」权限，
     * 否则客户端只能看到文件夹、读不到文件。选中共享存储目录时自动引导开启（REQ 五 · 异常必附方案）。
     */
    private fun promptAllFilesAccessIfNeeded(path: String?) {
        if (path == null || Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) return
        val own = context.getExternalFilesDir(null)?.absolutePath
        val appOwn = path.startsWith(context.filesDir.absolutePath) ||
            (own != null && path.startsWith(own)) ||
            path.contains("/Android/data/${context.packageName}/")
        if (appOwn) return
        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        } catch (_: Throwable) {
        }
        _alert.value = AppleAlert(
            "需要「所有文件访问」权限",
            "安卓 11+ 限制：文件服务直接读写该目录必须开启此权限，否则客户端只能看到文件夹。" +
                "方案：在系统设置里打开「允许访问所有文件」开关后返回本应用，目录即完整可用。",
        )
    }

    /** 目录内服务数量统计由 feature:service 侧查询；此处仅提供问题诊断文案。 */
    fun issueSolution(issue: DirectoryIssue): String = when (issue) {
        DirectoryIssue.OK -> ""
        DirectoryIssue.PERMISSION_LOST ->
            "目录授权已失效。方案：点击“重新授权”并在系统选择器中再次选择该文件夹。"
        DirectoryIssue.NO_FILESYSTEM_PATH ->
            "该分区无法映射为文件系统路径，协议子进程无法挂载。方案：改选内部存储/SD 卡标准目录。"
    }

    fun dismissAlert() {
        _alert.value = null
    }
}
