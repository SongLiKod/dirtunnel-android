package com.dirtunnel.feature.tunnel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.engine.model.ProcessState
import com.dirtunnel.core.engine.network.GlobalIpv6
import com.dirtunnel.core.engine.process.ProcessSupervisor
import com.dirtunnel.core.network.PublicTunnelConfigProvider
import com.dirtunnel.core.network.PublicTunnelEndpoint
import com.dirtunnel.core.network.QrCodeEncoder
import com.dirtunnel.core.storage.dao.FrpProfileDao
import com.dirtunnel.core.storage.dao.ForwardDao
import com.dirtunnel.core.storage.dao.ServiceDao
import com.dirtunnel.core.storage.data.SecurityStore
import com.dirtunnel.core.storage.entity.FrpProfileEntity
import com.dirtunnel.core.storage.entity.TcpForwardEntity
import com.dirtunnel.core.storage.model.PROFILE_PUBLIC
import com.dirtunnel.core.ui.components.AppleAlert
import com.dirtunnel.feature.tunnel.runtime.TunnelResult
import com.dirtunnel.feature.tunnel.runtime.TunnelRuntime
import com.dirtunnel.feature.tunnel.runtime.TunnelRuntimeController
import dagger.hilt.android.lifecycle.HiltViewModel
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

/** 二维码弹层数据（REQ 4.3.1：生成外网访问地址 + 二维码）。 */
data class QrSheetState(
    val title: String,
    val content: String,
    val bitmap: Bitmap?,
)

/**
 * 隧道面板（REQ 4.3：公共隧道一键使用 + 自建 FRP 图形化配置；
 * REQ 三 · 穿透层独立能力：自定义本地 TCP 端口转发）。
 */
@HiltViewModel
class TunnelViewModel @Inject constructor(
    private val frpProfileDao: FrpProfileDao,
    private val forwardDao: ForwardDao,
    private val serviceDao: ServiceDao,
    private val security: SecurityStore,
    private val publicTunnelProvider: PublicTunnelConfigProvider,
    private val runtime: TunnelRuntimeController,
    private val supervisor: ProcessSupervisor,
) : ViewModel() {

    val runtimes: StateFlow<Map<Long, TunnelRuntime>> = runtime.runtimes

    val profiles: StateFlow<List<FrpProfileEntity>> = frpProfileDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val forwards: StateFlow<List<TcpForwardEntity>> = forwardDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 已开启穿透的服务数（公共隧道可用性判断）。 */
    val tunneledServiceCount: StateFlow<Int> = serviceDao.observeAll()
        .map { list -> list.count { it.enableTunnel } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    // ==================== IPv6 公网直连（零服务器真实可用） ====================

    private val _ipv6Addresses = MutableStateFlow<List<String>>(emptyList())
    val ipv6Addresses: StateFlow<List<String>> = _ipv6Addresses.asStateFlow()

    fun refreshIpv6() {
        viewModelScope.launch(Dispatchers.IO) {
            _ipv6Addresses.value = GlobalIpv6.addresses()
        }
    }

    /** 运行中服务（名称/协议/端口）——用于生成直连公网地址。 */
    data class RunningService(val name: String, val protocol: String, val port: Int)

    val runningServices: StateFlow<List<RunningService>> =
        combine(serviceDao.observeAll(), supervisor.states) { services, states ->
            services
                .filter { states["service_${it.id}"] is ProcessState.Running }
                .map { RunningService(it.name, it.protocol, it.port) }
        }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    private val _endpoint = MutableStateFlow<PublicTunnelEndpoint?>(null)
    val endpoint: StateFlow<PublicTunnelEndpoint?> = _endpoint.asStateFlow()

    private val _alert = MutableStateFlow<AppleAlert?>(null)
    val alert: StateFlow<AppleAlert?> = _alert.asStateFlow()

    private val _qrSheet = MutableStateFlow<QrSheetState?>(null)
    val qrSheet: StateFlow<QrSheetState?> = _qrSheet.asStateFlow()

    private val _busyProfileId = MutableStateFlow<Long?>(null)
    val busyProfileId: StateFlow<Long?> = _busyProfileId.asStateFlow()

    /** 公共隧道服务器编辑器（域名/IP、端口、token、外网 host）。 */
    data class EndpointEditor(val server: String, val port: String, val token: String, val host: String)

    private val _endpointEditor = MutableStateFlow<EndpointEditor?>(null)
    val endpointEditor: StateFlow<EndpointEditor?> = _endpointEditor.asStateFlow()

    fun openEndpointEditor() {
        viewModelScope.launch {
            val ep = publicTunnelProvider.endpoint()
            _endpointEditor.value = EndpointEditor(
                server = ep.serverAddr,
                port = ep.serverPort.toString(),
                token = security.frpToken(PROFILE_PUBLIC) ?: "",
                host = ep.hostForUrl,
            )
        }
    }

    fun closeEndpointEditor() {
        _endpointEditor.value = null
    }

    fun saveEndpointEditor(server: String, port: String, token: String, host: String) {
        viewModelScope.launch {
            val srv = server.trim()
            val portNum = port.trim().toIntOrNull() ?: 0
            if (srv.isBlank() || portNum !in 1..65535) {
                _alert.value = AppleAlert("服务器配置无效", "方案：填写服务器域名/IP 与 1~65535 的端口号。")
                return@launch
            }
            publicTunnelProvider.configure(
                srv,
                portNum,
                token.trim().ifBlank { null },
                host.trim().ifBlank { null },
            )
            _endpoint.value = publicTunnelProvider.endpoint()
            _endpointEditor.value = null
            _alert.value = AppleAlert("已保存", "公共隧道端点已更新为 $srv:$portNum，重新启动隧道生效。")
        }
    }

    init {
        viewModelScope.launch { _endpoint.value = publicTunnelProvider.endpoint() }
        refreshIpv6()
        // 公共隧道首个远端端口分配成功后自动生成二维码（REQ 4.3.1 一键出可连接地址）
        viewModelScope.launch {
            runtimes.collect { map ->
                val rt = map[PROFILE_PUBLIC] ?: return@collect
                val mapped = rt.proxies.count { (it.remotePort ?: 0) > 0 }
                if (autoQrPending && rt.connected && mapped > 0) {
                    autoQrPending = false
                    showQr(PROFILE_PUBLIC)
                }
            }
        }
    }

    /** 公共隧道已启动、尚未拿到端口：拿到首个端口时自动弹二维码（每个启动周期一次）。 */
    private var autoQrPending = false

    // ==================== 启停 ====================

    fun startPublic() = startProfile(PROFILE_PUBLIC)

    fun startProfile(profileId: Long) {
        viewModelScope.launch {
            if (profileId == PROFILE_PUBLIC &&
                publicTunnelProvider.endpoint().serverAddr.isBlank()
            ) {
                _alert.value = AppleAlert(
                    "未配置公网中转服务器",
                    "公网中转必须有一台可联网的 frps 服务器（不存在免费可靠公共服务）。方案：①点击下方「公共隧道服务器」填入自有 frps 的地址/token；②或使用「公网直连(IPv6)」零配置卡片（访问端也需支持 IPv6）；③或以自建 FRP 档案连接你的 frps。",
                )
                return@launch
            }
            if (profileId == PROFILE_PUBLIC) autoQrPending = true
            _busyProfileId.value = profileId
            when (val r = runtime.start(profileId)) {
                is TunnelResult.Success -> _endpoint.value = publicTunnelProvider.endpoint()
                is TunnelResult.Failure -> _alert.value = AppleAlert(r.message, r.solution)
            }
            _busyProfileId.value = null
        }
    }

    fun stopProfile(profileId: Long) {
        autoQrPending = false
        viewModelScope.launch { runtime.stop(profileId) }
    }

    fun stopAll() {
        viewModelScope.launch { runtime.stopAll() }
    }

    // ==================== 二维码 / 地址 ====================

    fun showQr(profileId: Long) {
        viewModelScope.launch {
            val content = runtime.qrContent(profileId)
            if (content == null) {
                _alert.value = AppleAlert(
                    title = "暂无外网地址",
                    message = "隧道已启动但尚未获得远端端口。方案：①确认与 frps 服务器网络连通、token 正确；" +
                        "②内置公共服务器不可用时，请在设置中配置自有 frps 或使用自建 FRP 档案；" +
                        "③端口分配通常需 2~5 秒，稍候后重试。",
                )
                return@launch
            }
            _qrSheet.value = QrSheetState(
                title = if (profileId == PROFILE_PUBLIC) "公共隧道访问地址" else "隧道访问地址",
                content = content,
                bitmap = QrCodeEncoder.encode(content),
            )
        }
    }

    fun dismissQr() {
        _qrSheet.value = null
    }

    fun refreshEndpoint() {
        viewModelScope.launch {
            val ok = publicTunnelProvider.refreshFromRemote()
            _endpoint.value = publicTunnelProvider.endpoint()
            if (!ok) {
                _alert.value = AppleAlert(
                    title = "远端配置拉取失败",
                    message = "方案：检查设置中的「公共隧道配置地址」是否可访问；未配置时将始终使用内置默认端点。",
                )
            }
        }
    }

    // ==================== FRP 档案 ====================

    fun deleteProfile(profileId: Long) {
        viewModelScope.launch {
            runtime.stop(profileId)
            frpProfileDao.getById(profileId)?.let { frpProfileDao.delete(it) }
            security.setFrpToken(profileId, null)
        }
    }

    // ==================== TCP 转发 ====================

    fun toggleForward(forwardId: Long, enabled: Boolean) {
        viewModelScope.launch {
            val entity = forwardDao.getById(forwardId) ?: return@launch
            forwardDao.update(entity.copy(enabled = enabled))
        }
    }

    fun deleteForward(forwardId: Long) {
        viewModelScope.launch {
            forwardDao.getById(forwardId)?.let { forwardDao.delete(it) }
        }
    }

    fun dismissAlert() {
        val confirm = _alert.value?.onConfirm
        _alert.value = null
        confirm?.invoke()
    }
}
