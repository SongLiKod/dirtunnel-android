package com.dirtunnel.feature.directory

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.Settings
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.engine.model.ProcessState
import com.dirtunnel.core.engine.process.ProcessSupervisor
import com.dirtunnel.core.storage.dao.DirectoryDao
import com.dirtunnel.core.storage.entity.DirectoryEntity
import com.dirtunnel.core.storage.saf.SaFDocumentTree
import com.dirtunnel.core.ui.components.AppleAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 目录可用性（REQ 4.1 权限 / TECH 七.4 SAF 授权持久化）。 */
enum class DirectoryIssue { OK, PERMISSION_LOST, NO_FILESYSTEM_PATH, ALL_FILES_DENIED }

/**
 * 目录管理（REQ 4.1：浏览选择任意目录、SAF 适配、收藏一键复用、状态校验）。
 */
@HiltViewModel
class DirectoryViewModel @Inject constructor(
    private val directoryDao: DirectoryDao,
    private val serviceDao: com.dirtunnel.core.storage.dao.ServiceDao,
    private val saf: SaFDocumentTree,
    private val supervisor: ProcessSupervisor,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    val directories: StateFlow<List<DirectoryEntity>> = directoryDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 每目录服务数量（目录卡片展示）。 */
    val serviceCounts: StateFlow<Map<Long, Int>> = serviceDao.observeAll()
        .map { list -> list.groupingBy { it.directoryId }.eachCount() }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    /** 每目录运行中的服务（数量 + 名称），随进程状态实时刷新。 */
    data class DirRunning(val count: Int, val names: List<String>)

    val runningByDir: StateFlow<Map<Long, DirRunning>> =
        combine(serviceDao.observeAll(), supervisor.states) { services, states ->
            services
                .filter { states["service_${it.id}"] is ProcessState.Running }
                .groupBy { it.directoryId }
                .mapValues { (_, list) -> DirRunning(list.size, list.map { it.name }) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyMap())

    private val _issues = MutableStateFlow<Map<Long, DirectoryIssue>>(emptyMap())
    val issues: StateFlow<Map<Long, DirectoryIssue>> = _issues.asStateFlow()

    private val _alert = MutableStateFlow<AppleAlert?>(null)
    val alert: StateFlow<AppleAlert?> = _alert.asStateFlow()

    private val _optionTarget = MutableStateFlow<DirectoryEntity?>(null)
    val optionTarget: StateFlow<DirectoryEntity?> = _optionTarget.asStateFlow()

    /**
     * 重新授权目标：非空时表示下一次系统选择器返回是「给该目录重新授权」，
     * 而不是添加新目录（避免用户选错文件夹时误加记录）。
     */
    private val _reauthTarget = MutableStateFlow<DirectoryEntity?>(null)

    init {
        refreshIssues()
    }

    /** 接收 SAF 选择器返回的 tree Uri（TECH 一 · SAF + 持久化授权）。 */
    fun onDirectoryPicked(uri: Uri?) {
        if (uri == null) return
        viewModelScope.launch {
            val reauth = _reauthTarget.value
            if (reauth != null) {
                _reauthTarget.value = null
                applyReauth(reauth, uri)
                return@launch
            }
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

    /** 进入「重新授权」模式：下一次选择器返回将更新该目录的授权，而非新增记录。 */
    fun beginReauth(dir: DirectoryEntity) {
        _reauthTarget.value = dir
    }

    /** 给指定目录重新取 SAF 授权：保留 id/名称/收藏，仅换 treeUri/fsPath。 */
    private suspend fun applyReauth(dir: DirectoryEntity, uri: Uri) {
        val treeUri = uri.toString()
        val ok = withContext(Dispatchers.IO) { saf.takePersistable(treeUri, write = true) }
        if (!ok) {
            _alert.value = AppleAlert(
                "重新授权失败",
                "方案：请在系统选择器中重新选择该文件夹，并允许“读写”访问；若反复失败，删除该目录后重新添加。",
            )
            return
        }
        val fsPath = withContext(Dispatchers.IO) { saf.resolveFsPath(treeUri) }
        directoryDao.update(dir.copy(treeUri = treeUri, fsPath = fsPath ?: dir.fsPath))
        refreshIssues()
        _alert.value = AppleAlert("已重新授权", "“${dir.name}”已恢复可用。")
    }

    /**
     * 直达系统「所有文件访问」开关页（分区存储：原始文件 I/O 必需，
     * 否则客户端只能看到文件夹、读不到文件）。
     */
    fun requestAllFilesAccess() {
        if (!openAllFilesSettings()) {
            _alert.value = AppleAlert(
                "无法打开权限页",
                "该 ROM 不支持直达。方案：设置 → 应用 → DirTunnel → 权限 → 所有文件访问，手动开启后返回。",
            )
        }
    }

    private fun openAllFilesSettings(): Boolean = try {
        val intent = Intent(
            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
            Uri.parse("package:${context.packageName}"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
        true
    } catch (_: Throwable) {
        false
    }

    /** 该路径是否需要「所有文件访问」（非应用自有目录 + 安卓 11+ 且未授予）。 */
    private fun needsAllFiles(path: String?): Boolean {
        if (path == null || Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()) return false
        val own = context.getExternalFilesDir(null)?.absolutePath
        return !(path.startsWith(context.filesDir.absolutePath) ||
            (own != null && path.startsWith(own)) ||
            path.contains("/Android/data/${context.packageName}/"))
    }

    /** 校验全部目录的授权与可挂载性（REQ 4.6 权限不足告警数据源）。 */
    fun refreshIssues() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { directoryDao.getAll() }
            val map = list.associate { dir ->
                dir.id to when {
                    // 直连文件系统路径目录（treeUri 为空，如一键启动专用目录）：无需 SAF 授权，不得误报失效
                    dir.treeUri.isBlank() && dir.fsPath != null ->
                        if (needsAllFiles(dir.fsPath)) DirectoryIssue.ALL_FILES_DENIED else DirectoryIssue.OK
                    !saf.hasPersistable(dir.treeUri) -> DirectoryIssue.PERMISSION_LOST
                    dir.fsPath == null && withContext(Dispatchers.IO) { saf.resolveFsPath(dir.treeUri) } == null ->
                        DirectoryIssue.NO_FILESYSTEM_PATH
                    needsAllFiles(dir.fsPath) -> DirectoryIssue.ALL_FILES_DENIED
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

    /** 返回前台/权限页返回时刷新授权状态（onResume 由 UI 侧触发）。 */
    fun refreshOnResume() {
        refreshIssues()
        // 「所有文件访问」授权页返回后，自动补加挂起的直选目录
        val pending = pendingRawPath
        if (pending != null && hasAllFilesAccess()) {
            pendingRawPath = null
            viewModelScope.launch { addRawNow(pending) }
        }
    }

    // ===== 受限目录直选（Android 15+ 系统选择器禁选：内部存储根 / Download / Android·data 等） =====

    /** 待直选路径：缺「所有文件访问」跳去授权时挂起，授权返回后自动完成添加。 */
    @Volatile
    private var pendingRawPath: String? = null

    /** 是否已具备直选条件（安卓 11+ 需「所有文件访问」；更早版本无分区存储限制）。 */
    fun hasAllFilesAccess(): Boolean =
        Build.VERSION.SDK_INT < 30 || Environment.isExternalStorageManager()

    /**
     * 经原始文件路径直选目录（绕过 SAF 系统选择器）：treeUri 置空 = 直连文件系统模式
     * （[refreshIssues] 已按此模式校验，无需 SAF 授权）。缺权限时先引导授权，
     * [refreshOnResume] 在授权返回后自动完成添加。
     */
    fun addDirectoryRaw(path: String) {
        val normalized = path.trim().trimEnd('/')
        if (normalized.isBlank()) return
        viewModelScope.launch {
            if (!hasAllFilesAccess()) {
                pendingRawPath = normalized
                requestAllFilesAccess()
                _alert.value = AppleAlert(
                    "需要「所有文件访问」权限",
                    "直选受限目录（内部存储根目录、Download、Android/data 等）必须开启该权限。" +
                        "方案：在系统设置中打开「允许访问所有文件」开关后返回本应用，将自动完成添加。",
                )
                return@launch
            }
            addRawNow(normalized)
        }
    }

    private suspend fun addRawNow(path: String) {
        val existing = withContext(Dispatchers.IO) {
            directoryDao.getAll().find { it.treeUri.isBlank() && it.fsPath == path }
        }
        if (existing != null) {
            directoryDao.touch(existing.id)
            refreshIssues()
            _alert.value = AppleAlert("目录已存在", "该目录已添加，已为你刷新为最近使用。")
            return
        }
        val name = File(path).name.ifBlank { "内部存储" }
        directoryDao.insert(DirectoryEntity(name = name, treeUri = "", fsPath = path))
        refreshIssues()
        _alert.value = AppleAlert("已添加", "“$name”已通过直连方式添加，无需 SAF 授权。")
    }

    /**
     * 安卓 11+ 分区存储：文件服务器以原始路径读写共享目录，必须有「所有文件访问」权限，
     * 否则客户端只能看到文件夹、读不到文件。选中共享存储目录时自动引导开启（REQ 五 · 异常必附方案）。
     */
    private fun promptAllFilesAccessIfNeeded(path: String?) {
        if (!needsAllFiles(path)) return
        openAllFilesSettings()
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
        DirectoryIssue.ALL_FILES_DENIED ->
            "缺少「所有文件访问」权限：客户端只能看到文件夹、读不到文件。方案：点击“授权”并打开系统开关。"
    }

    fun dismissAlert() {
        _alert.value = null
    }
}
