package com.dirtunnel.feature.service

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.engine.model.ProcessState
import com.dirtunnel.core.engine.model.ServiceRuntimeStatus
import com.dirtunnel.core.storage.dao.DirectoryDao
import com.dirtunnel.core.storage.dao.ServiceDao
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.core.storage.entity.DirectoryEntity
import com.dirtunnel.core.storage.entity.ServiceInstanceEntity
import com.dirtunnel.core.storage.model.ProtocolType
import com.dirtunnel.core.storage.saf.SaFDocumentTree
import com.dirtunnel.core.ui.components.AppleAlert
import com.dirtunnel.core.ui.components.AppleInfoDialogSpec
import com.dirtunnel.feature.service.runtime.ServiceRuntimeController
import com.dirtunnel.feature.service.runtime.StartResult
import com.dirtunnel.feature.service.runtime.toDialogSpec
import dagger.hilt.android.lifecycle.HiltViewModel
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

/** iOS 风格提示弹窗数据已迁移至 core:ui（跨模块复用），见 [com.dirtunnel.core.ui.components.AppleAlert]。 */

/** 目录级权限/可用性告警（REQ 4.6 权限不足告警）。 */
data class DirectoryBanner(
    val title: String,
    val solution: String,
    val isError: Boolean = true,
)

/**
 * 服务实例列表（REQ 4.2 状态卡片、一键启停；REQ 4.6 监控）。
 */
@HiltViewModel
class ServiceListViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    directoryDao: DirectoryDao,
    private val serviceDao: ServiceDao,
    settingsStore: SettingsStore,
    private val runtime: ServiceRuntimeController,
    private val saf: SaFDocumentTree,
) : ViewModel() {

    // 路由 NavType.LongType 存入的是 Long，按 Any 读取后解析，避免 ClassCastException
    val directoryId: Long =
        savedStateHandle.get<Any>("directoryId")?.toString()?.toLongOrNull() ?: 0L

    val directory: StateFlow<DirectoryEntity?> =
        directoryDao.observeById(directoryId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    val services: StateFlow<List<ServiceInstanceEntity>> =
        serviceDao.observeForDirectory(directoryId)
            .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val statuses: StateFlow<Map<Long, ServiceRuntimeStatus>> = runtime.statuses

    val advancedVisible: StateFlow<Boolean> = settingsStore.settings
        .map { it.advancedFeaturesVisible }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _alert = MutableStateFlow<AppleAlert?>(null)
    val alert: StateFlow<AppleAlert?> = _alert.asStateFlow()

    /** 一键添加成功后的可复制连接信息弹窗。 */
    private val _infoDialog = MutableStateFlow<AppleInfoDialogSpec?>(null)
    val infoDialog: StateFlow<AppleInfoDialogSpec?> = _infoDialog.asStateFlow()

    private val _banners = MutableStateFlow<List<DirectoryBanner>>(emptyList())
    val banners: StateFlow<List<DirectoryBanner>> = _banners.asStateFlow()

    private val _busyId = MutableStateFlow<Long?>(null)
    val busyId: StateFlow<Long?> = _busyId.asStateFlow()

    init {
        refreshBanners()
    }

    /** 权限不足 / 目录失效告警（附解决方案）。 */
    fun refreshBanners() {
        viewModelScope.launch {
            val dir = directory.value
            val list = buildList {
                if (dir != null) {
                    // treeUri 为空的纯文件系统路径目录（一键启动默认目录）无需 SAF 授权
                    if (dir.treeUri.isNotBlank() && !saf.hasPersistable(dir.treeUri)) {
                        add(
                            DirectoryBanner(
                                title = "目录授权已失效",
                                solution = "方案：返回目录页重新选择该文件夹完成授权（系统会在重启后回收未持久化的授权）。",
                            ),
                        )
                    } else if (dir.fsPath == null && withContext(Dispatchers.IO) { saf.resolveFsPath(dir.treeUri) } == null) {
                        add(
                            DirectoryBanner(
                                title = "当前分区无法直接挂载",
                                solution = "方案：该目录不在 /storage 标准分区，协议子进程无法访问。请改选内部存储或 SD 卡目录。",
                            ),
                        )
                    }
                }
            }
            _banners.value = list
        }
    }

    /**
     * 一键添加（REQ 五 · 易用性）：零配置在当前目录创建并启动指定协议服务，
     * 自动选空闲端口 + 随机密码，成功后弹窗展示连接信息。
     */
    fun quickAdd(protocol: ProtocolType) {
        viewModelScope.launch {
            _busyId.value = -1L // -1 = 一键操作进行中（不绑定任何卡片）
            when (val r = runtime.quickStart(protocol, directoryId)) {
                is ServiceRuntimeController.QuickResult.Success -> {
                    _infoDialog.value = r.info.toDialogSpec("「${r.serviceName}」已启动")
                }
                is ServiceRuntimeController.QuickResult.Failure -> {
                    _alert.value = AppleAlert(title = r.message, message = r.solution)
                }
            }
            _busyId.value = null
        }
    }

    fun start(serviceId: Long) = launchOp(serviceId) { runtime.start(serviceId) }

    fun restart(serviceId: Long) = launchOp(serviceId) { runtime.restart(serviceId) }

    fun stop(serviceId: Long) {
        viewModelScope.launch { runtime.stop(serviceId) }
    }

    fun startAll() {
        viewModelScope.launch {
            val ids = services.value.map { it.id }
            val ok = runtime.startAll(ids)
            val failed = ids.size - ok
            if (failed > 0) {
                val firstErr = services.value.firstNotNullOfOrNull { s ->
                    statuses.value[s.id]?.state as? com.dirtunnel.core.engine.model.ProcessState.Failed
                }
                _alert.value = AppleAlert(
                    title = "已启动 $ok 个，$failed 个失败",
                    message = firstErr?.solution ?: "请逐个检查失败服务的提示。",
                )
            }
        }
    }

    fun delete(serviceId: Long) {
        viewModelScope.launch {
            runtime.stop(serviceId)
            serviceDao.getById(serviceId)?.let { serviceDao.delete(it) }
        }
    }

    fun dismissAlert() {
        val confirm = _alert.value?.onConfirm
        _alert.value = null
        confirm?.invoke()
    }

    fun dismissInfo() {
        _infoDialog.value = null
    }

    /**
     * 服务详情（REQ 五）：卡片「详情」入口，任何时候查看完整连接信息——
     * 地址/账号/密码/端口/共享目录/自测/白名单/隧道提示，字段可复制。
     */
    fun showDetail(service: ServiceInstanceEntity) {
        viewModelScope.launch {
            val info = runtime.detailInfo(service)
            val running = statuses.value[service.id]?.state is ProcessState.Running
            _infoDialog.value = info.toDialogSpec(
                title = service.name,
                subtitle = "${service.protocolType.label} · ${if (running) "运行中" else "未启动"}",
            )
        }
    }

    private fun launchOp(serviceId: Long, op: suspend () -> StartResult) {
        viewModelScope.launch {
            _busyId.value = serviceId
            when (val r = op()) {
                is StartResult.Success -> {}
                is StartResult.Failure -> {
                    _alert.value = AppleAlert(
                        title = r.message,
                        message = r.solution,
                    )
                }
            }
            _busyId.value = null
        }
    }
}
