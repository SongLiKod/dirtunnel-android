package com.dirtunnel.feature.tunnel

import android.graphics.Bitmap
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
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

    private val _endpoint = MutableStateFlow<PublicTunnelEndpoint?>(null)
    val endpoint: StateFlow<PublicTunnelEndpoint?> = _endpoint.asStateFlow()

    private val _alert = MutableStateFlow<AppleAlert?>(null)
    val alert: StateFlow<AppleAlert?> = _alert.asStateFlow()

    private val _qrSheet = MutableStateFlow<QrSheetState?>(null)
    val qrSheet: StateFlow<QrSheetState?> = _qrSheet.asStateFlow()

    private val _busyProfileId = MutableStateFlow<Long?>(null)
    val busyProfileId: StateFlow<Long?> = _busyProfileId.asStateFlow()

    init {
        viewModelScope.launch { _endpoint.value = publicTunnelProvider.endpoint() }
    }

    // ==================== 启停 ====================

    fun startPublic() = startProfile(PROFILE_PUBLIC)

    fun startProfile(profileId: Long) {
        viewModelScope.launch {
            _busyProfileId.value = profileId
            when (val r = runtime.start(profileId)) {
                is TunnelResult.Success -> _endpoint.value = publicTunnelProvider.endpoint()
                is TunnelResult.Failure -> _alert.value = AppleAlert(r.message, r.solution)
            }
            _busyProfileId.value = null
        }
    }

    fun stopProfile(profileId: Long) {
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
                    message = "方案：先启动隧道，等待 frpc 连接成功并分配远端端口后再生成二维码。",
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
