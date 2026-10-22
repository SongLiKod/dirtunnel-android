package com.dirtunnel.app.dashboard

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.engine.health.SystemHealthMonitor
import com.dirtunnel.core.engine.health.HealthWarning
import com.dirtunnel.core.engine.model.ProcessState
import com.dirtunnel.core.engine.model.ServiceRuntimeStatus
import com.dirtunnel.core.network.NetworkState
import com.dirtunnel.core.network.NetworkStatusMonitor
import com.dirtunnel.core.storage.dao.DirectoryDao
import com.dirtunnel.core.storage.dao.LogDao
import com.dirtunnel.core.storage.dao.ServiceDao
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.core.storage.model.ProtocolType
import com.dirtunnel.core.ui.components.AppleAlert
import com.dirtunnel.core.ui.components.AppleInfoDialogSpec
import com.dirtunnel.feature.service.runtime.ServiceRuntimeController
import com.dirtunnel.feature.service.runtime.toDialogSpec
import com.dirtunnel.feature.tunnel.runtime.TunnelRuntime
import com.dirtunnel.feature.tunnel.runtime.TunnelRuntimeController
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页仪表盘数据源（REQ 4.6 监控：服务运行状态、在线连接数、实时流量、
 * 隧道状态、端口占用告警、权限不足告警）。
 */
@HiltViewModel
class DashboardViewModel @Inject constructor(
    directoryDao: DirectoryDao,
    private val serviceDao: ServiceDao,
    logDao: LogDao,
    settingsStore: SettingsStore,
    private val serviceRuntime: ServiceRuntimeController,
    private val tunnelRuntime: TunnelRuntimeController,
    private val healthMonitor: SystemHealthMonitor,
    networkMonitor: NetworkStatusMonitor,
) : ViewModel() {

    val serviceStatuses: StateFlow<Map<Long, ServiceRuntimeStatus>> = serviceRuntime.statuses
    val tunnelRuntimes: StateFlow<Map<Long, TunnelRuntime>> = tunnelRuntime.runtimes
    val network: StateFlow<NetworkState> = networkMonitor.state

    val directoryCount: StateFlow<Int> = directoryDao.observeAll()
        .map { list -> list.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val serviceCount: StateFlow<Int> = serviceDao.observeAll()
        .map { list -> list.size }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val connectionLogCount: StateFlow<Int> = logDao.observeConnectionCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 健康告警（电池 / 通知 / SELinux + 端口占用，必附解决方案）。 */
    private val _warnings = MutableStateFlow<List<HealthWarning>>(emptyList())
    val warnings: StateFlow<List<HealthWarning>> = _warnings.asStateFlow()

    private val _alert = MutableStateFlow<AppleAlert?>(null)
    val alert: StateFlow<AppleAlert?> = _alert.asStateFlow()

    /** 一键启动成功后的可复制连接信息弹窗。 */
    private val _infoDialog = MutableStateFlow<AppleInfoDialogSpec?>(null)
    val infoDialog: StateFlow<AppleInfoDialogSpec?> = _infoDialog.asStateFlow()

    /** 汇总仪表盘数值。 */
    data class DashboardStats(
        val runningServices: Int = 0,
        val totalServices: Int = 0,
        val activeConnections: Int = 0,
        val bytesUp: Long = 0,
        val bytesDown: Long = 0,
        val connectedTunnels: Int = 0,
        val totalTunnels: Int = 0,
        val portConflicts: Int = 0,
    )

    private val _stats = MutableStateFlow(DashboardStats())
    val stats: StateFlow<DashboardStats> = _stats.asStateFlow()

    init {
        viewModelScope.launch {
            // 1s 聚合采样（与 ServiceRuntimeController 的 1s 采样对齐）
            while (true) {
                val statuses = serviceStatuses.value.values
                val tunnels = tunnelRuntimes.value.values
                _stats.value = DashboardStats(
                    runningServices = statuses.count { it.state is ProcessState.Running },
                    totalServices = serviceCount.value,
                    activeConnections = statuses.sumOf { it.activeConnections },
                    bytesUp = statuses.sumOf { it.bytesUp },
                    bytesDown = statuses.sumOf { it.bytesDown },
                    connectedTunnels = tunnels.count { it.connected },
                    totalTunnels = tunnels.size,
                    portConflicts = statuses.count {
                        val st = it.state
                        st is ProcessState.Failed && st.message.contains("端口")
                    },
                )
                kotlinx.coroutines.delay(1_000)
            }
        }
        refreshWarnings()
    }

    /** 刷新健康告警（REQ 4.6 权限不足 / 端口占用告警）。 */
    fun refreshWarnings() {
        viewModelScope.launch {
            healthMonitor.refreshBattery()
            val portWarnings = serviceStatuses.value.values
                .filter { it.state is ProcessState.Failed }
                .mapNotNull { st ->
                    val failed = st.state as ProcessState.Failed
                    if (failed.message.contains("端口")) {
                        HealthWarning(
                            id = "port_${st.serviceId}",
                            title = "端口冲突：${failed.message}",
                            message = failed.solution,
                            solution = failed.solution,
                            severity = HealthWarning.Severity.ERROR,
                        )
                    } else null
                }
            _warnings.value = healthMonitor.collectWarnings(extra = portWarnings)
        }
    }

    fun dismissAlert() {
        _alert.value = null
    }

    fun dismissInfo() {
        _infoDialog.value = null
    }

    fun notify(title: String, message: String) {
        _alert.value = AppleAlert(title, message)
    }

    // ==================== 一键启动 / 停止（REQ 五 · 易用性） ====================

    private val _tapBusy = MutableStateFlow(false)
    val tapBusy: StateFlow<Boolean> = _tapBusy.asStateFlow()

    /**
     * 一键操作：
     * - 无服务运行：自动创建默认共享目录 + 零配置 FTP 服务并启动，弹窗展示连接地址/账号/密码；
     * - 有服务运行：一键停止全部服务。
     */
    fun oneTap() {
        if (_tapBusy.value) return
        viewModelScope.launch {
            _tapBusy.value = true
            try {
                val anyActive = serviceStatuses.value.values.any {
                    it.state is ProcessState.Running || it.state is ProcessState.Starting
                }
                if (anyActive) {
                    serviceRuntime.stopAll()
                    _alert.value = AppleAlert(
                        title = "已停止全部服务",
                        message = "需要时可随时再次点「一键启动」。若开机自启开启，重启手机后会自动恢复。",
                    )
                } else {
                    when (val r = serviceRuntime.quickStart(ProtocolType.FTP)) {
                        is ServiceRuntimeController.QuickResult.Success -> {
                            _infoDialog.value = r.info.toDialogSpec("「${r.serviceName}」已启动")
                            refreshWarnings()
                        }
                        is ServiceRuntimeController.QuickResult.Failure -> {
                            _alert.value = AppleAlert(title = r.message, message = r.solution)
                        }
                    }
                }
            } finally {
                _tapBusy.value = false
            }
        }
    }
}
