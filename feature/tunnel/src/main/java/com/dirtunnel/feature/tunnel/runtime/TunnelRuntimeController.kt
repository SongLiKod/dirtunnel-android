package com.dirtunnel.feature.tunnel.runtime

import android.content.Context
import android.os.PowerManager
import com.dirtunnel.core.engine.model.ConfigFile
import com.dirtunnel.core.engine.model.ProcessSpec
import com.dirtunnel.core.engine.model.ProcessState
import com.dirtunnel.core.engine.process.BinaryManager
import com.dirtunnel.core.engine.process.ProcessSupervisor
import com.dirtunnel.core.network.PublicTunnelConfigProvider
import com.dirtunnel.core.network.DdnsUpdater
import com.dirtunnel.core.storage.dao.ForwardDao
import com.dirtunnel.core.storage.dao.FrpProfileDao
import com.dirtunnel.core.storage.dao.LogDao
import com.dirtunnel.core.storage.dao.ServiceDao
import com.dirtunnel.core.storage.data.SecurityStore
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.core.storage.entity.ConnectionEvent
import com.dirtunnel.core.storage.entity.ConnectionLogEntity
import com.dirtunnel.core.storage.model.PROFILE_PUBLIC
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.launch

/** 启动隧道结果（失败必带解决方案）。 */
sealed interface TunnelResult {
    data object Success : TunnelResult
    data class Failure(val message: String, val solution: String) : TunnelResult
}

/** 单条代理的公网映射。 */
data class TunnelProxyInfo(
    val name: String,
    /** 来源描述（服务名 / 转发名） */
    val label: String,
    val localPort: Int,
    /** null = 尚未分配 */
    val remotePort: Int? = null,
)

/** 一个隧道档案（公共 / 自建）的运行时。 */
data class TunnelRuntime(
    val profileId: Long,
    val label: String,
    val server: String,
    val state: ProcessState = ProcessState.Stopped,
    val connected: Boolean = false,
    val lastError: String? = null,
    val proxies: List<TunnelProxyInfo> = emptyList(),
    val startedAt: Long = 0L,
)

/**
 * FRP 隧道运行时（REQ 4.3：公共隧道一键使用 + 自建 FRP 图形化配置；
 * REQ 三 · 穿透层独立能力：自定义本地 TCP 端口转发；REQ 4.6 隧道状态）。
 */
@Singleton
class TunnelRuntimeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serviceDao: ServiceDao,
    private val forwardDao: ForwardDao,
    private val frpProfileDao: FrpProfileDao,
    private val logDao: LogDao,
    private val security: SecurityStore,
    private val settingsStore: SettingsStore,
    private val supervisor: ProcessSupervisor,
    private val binaryManager: BinaryManager,
    private val publicTunnelProvider: PublicTunnelConfigProvider,
    private val ddnsUpdater: DdnsUpdater,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _runtimes = MutableStateFlow<Map<Long, TunnelRuntime>>(emptyMap())
    val runtimes: StateFlow<Map<Long, TunnelRuntime>> = _runtimes.asStateFlow()

    val anyConnected: StateFlow<Boolean> = _runtimes
        .map { map -> map.values.any { it.connected } }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val logJobs = mutableMapOf<Long, Job>()
    private val stateJobs = mutableMapOf<Long, Job>()
    private var wakeLock: PowerManager.WakeLock? = null

    @Volatile private var autoRestartCache = true
    @Volatile private var wakeLockEnabledCache = false
    @Volatile private var ddnsEnabledCache = false

    init {
        scope.launch {
            settingsStore.settings.collect { s ->
                autoRestartCache = s.autoRestartProcess
                wakeLockEnabledCache = s.wakeLockEnabled
                ddnsEnabledCache = s.ddnsEnabled
            }
        }
    }

    // ==================== 代理条目收集 ====================

    private suspend fun collectProxies(profileId: Long): List<FrpcConfigBuilder.FrpcProxy> {
        val services = serviceDao.getTunneled()
            .filter { it.tunnelProfileId == profileId }
            .map {
                FrpcConfigBuilder.FrpcProxy(
                    name = FrpcConfigBuilder.proxyName("svc", it.id),
                    localPort = it.port,
                    // 公共隧道：远端端口由服务器随机分配；自建：沿用同号端口便于记忆
                    remotePort = if (profileId == PROFILE_PUBLIC) 0 else it.port,
                    description = it.name,
                )
            }
        val forwards = forwardDao.getEnabled()
            .filter { it.profileId == profileId }
            .map {
                FrpcConfigBuilder.FrpcProxy(
                    name = FrpcConfigBuilder.proxyName("fwd", it.id),
                    localPort = it.localPort,
                    remotePort = if (profileId == PROFILE_PUBLIC) it.remotePort.coerceAtLeast(0) else it.remotePort,
                    description = it.name,
                )
            }
        return services + forwards
    }

    // ==================== 启动 ====================

    suspend fun start(profileId: Long): TunnelResult {
        val proxies = collectProxies(profileId)
        if (proxies.isEmpty()) {
            return TunnelResult.Failure(
                "没有可穿透的目标",
                "方案：为服务开启“启用穿透”，或在下方添加一条自定义 TCP 转发。",
            )
        }

        // 二进制
        binaryManager.ensureForProtocol("frpc")?.let { msg ->
            return TunnelResult.Failure("FRP 客户端组件不可用", msg)
        }
        val frpc = binaryManager.executableFile("frpc")
            ?: return TunnelResult.Failure("FRP 客户端组件缺失", "方案：设置 → 协议组件 → 恢复默认组件。")

        // 端点与 token
        val label: String
        val serverAddr: String
        val serverPort: Int
        val token: String
        val useTls: Boolean
        if (profileId == PROFILE_PUBLIC) {
            val endpoint = publicTunnelProvider.endpoint()
            label = "公共隧道"
            serverAddr = endpoint.serverAddr
            serverPort = endpoint.serverPort
            token = endpoint.token
            useTls = true
        } else {
            val profile = frpProfileDao.getById(profileId)
                ?: return TunnelResult.Failure("FRP 档案不存在", "方案：返回后重新编辑该档案。")
            label = profile.name
            serverAddr = profile.serverAddr
            serverPort = profile.serverPort
            token = security.frpToken(profileId).orEmpty()
            useTls = profile.useTls
            if (serverAddr.isBlank()) {
                return TunnelResult.Failure("未填写 FRP 服务器地址", "方案：编辑档案填写 frps 服务器地址与端口。")
            }
            if (token.isBlank()) {
                return TunnelResult.Failure(
                    "未填写 token",
                    "方案：编辑该 FRP 档案，填入与 frps.toml 中 auth.token 一致的令牌（加密保存）。",
                )
            }
        }

        // 生成配置（token 落 0600 文件，不进命令行）
        val configDir = File(File(context.filesDir, "configs"), "tunnel_$profileId")
        configDir.deleteRecursively()
        configDir.mkdirs()
        val logFile = File(configDir, "frpc.log")
        val cfgFile = File(configDir, "frpc.toml")
        val content = FrpcConfigBuilder.build(
            serverAddr = serverAddr,
            serverPort = serverPort,
            token = token,
            useTls = useTls,
            proxies = proxies,
            logFile = logFile.path,
        )

        val serverDisplay = "$serverAddr:$serverPort"
        updateRuntime(profileId) {
            it.copy(
                label = label,
                server = serverDisplay,
                state = ProcessState.Starting,
                proxies = proxies.map { p -> TunnelProxyInfo(p.name, p.description, p.localPort, p.remotePort.takeIf { r -> r > 0 }) },
                lastError = null,
                connected = false,
            )
        }

        val spec = ProcessSpec(
            key = tunnelKey(profileId),
            displayLabel = "$label ($serverDisplay)",
            binaryPath = frpc.path,
            args = listOf("-c", cfgFile.path),
            workDir = configDir.path,
            configFiles = listOf(ConfigFile(cfgFile.name, content)),
            readyPort = 0,
        )

        val started = supervisor.start(
            spec = spec,
            autoRestartProvider = { autoRestartCache },
            onExit = { _, code, expected ->
                if (!expected) {
                    appendError(profileId, "frpc 退出(code=$code)")
                }
            },
        )
        if (!started) {
            val st = supervisor.get(tunnelKey(profileId))?.state?.value as? ProcessState.Failed
            updateRuntime(profileId) {
                it.copy(
                    state = st ?: ProcessState.Failed("frpc 启动失败", "方案：检查协议组件 md5 与网络连通性后重试。"),
                    connected = false,
                )
            }
            return TunnelResult.Failure(
                st?.message ?: "frpc 启动失败",
                st?.solution ?: "方案：确认 frpc 组件完整、服务器地址可达后重试。",
            )
        }

        // 状态订阅
        stateJobs[profileId]?.cancel()
        supervisor.get(tunnelKey(profileId))?.let { proc ->
            stateJobs[profileId] = scope.launch {
                proc.state.collect { st ->
                    updateRuntime(profileId) {
                        it.copy(state = st, connected = if (st is ProcessState.Running) it.connected else false)
                    }
                }
            }
        }

        // 日志解析：login 成功 / 远端端口 / 错误
        logJobs[profileId]?.cancel()
        logJobs[profileId] = scope.launch {
            supervisor.get(tunnelKey(profileId))?.logs?.collect { line ->
                if (FrpcConfigBuilder.isLoginSuccess(line)) {
                    onConnected(profileId, label, serverDisplay)
                }
                FrpcConfigBuilder.parseRemotePort(line)?.let { (name, port) ->
                    updateRuntime(profileId) { rt ->
                        rt.copy(proxies = rt.proxies.map { p -> if (p.name == name) p.copy(remotePort = port) else p })
                    }
                }
                if (FrpcConfigBuilder.isError(line) && line.contains("connect", ignoreCase = true)) {
                    appendError(profileId, line)
                }
            }
        }

        updateRuntime(profileId) { it.copy(state = supervisor.get(tunnelKey(profileId))?.state?.value ?: ProcessState.Starting) }
        maybeAcquireWakeLock()
        return TunnelResult.Success
    }

    /** 一键启动所有已配置的隧道（服务穿透 + 转发）。 */
    suspend fun startAllEnabled(): Int {
        var ok = 0
        val ids = buildSet {
            serviceDao.getTunneled().forEach { add(it.tunnelProfileId) }
            forwardDao.getEnabled().forEach { add(it.profileId) }
        }
        ids.forEach { id -> if (start(id) is TunnelResult.Success) ok++ }
        return ok
    }

    private fun onConnected(profileId: Long, label: String, server: String) {
        val was = _runtimes.value[profileId]?.connected == true
        updateRuntime(profileId) { it.copy(connected = true, lastError = null) }
        if (!was) {
            scope.launch {
                logDao.insertConnection(
                    ConnectionLogEntity(
                        serviceId = 0,
                        serviceName = "$label 隧道",
                        protocol = "FRP",
                        event = ConnectionEvent.CONNECT.name,
                        message = "已连接 $server",
                    ),
                )
            }
            if (ddnsEnabledCache) {
                scope.launch {
                    val r = ddnsUpdater.update()
                    if (r.success) settingsStore.setLastDdnsUpdate(System.currentTimeMillis())
                }
            }
        }
        maybeAcquireWakeLock()
    }

    // ==================== 停止 ====================

    fun stop(profileId: Long) {
        logJobs[profileId]?.cancel()
        stateJobs[profileId]?.cancel()
        supervisor.stop(tunnelKey(profileId))
        val rt = _runtimes.value[profileId]
        if (rt?.connected == true) {
            scope.launch {
                logDao.insertConnection(
                    ConnectionLogEntity(
                        serviceId = 0,
                        serviceName = "${rt.label} 隧道",
                        protocol = "FRP",
                        event = ConnectionEvent.DISCONNECT.name,
                        message = "隧道已断开 ${rt.server}",
                    ),
                )
            }
        }
        updateRuntime(profileId) { it.copy(state = ProcessState.Stopped, connected = false) }
        maybeReleaseWakeLock()
    }

    suspend fun stopAll() {
        _runtimes.value.keys.toList().forEach { stop(it) }
        supervisor.all()
            .filter { it.spec.key.startsWith("tunnel_") }
            .forEach { it.stopGraceful(2_000) }
        releaseWakeLock()
    }

    // ==================== 展示数据 ====================

    /** 已分配的公网地址列表：label → tcp://host:port。 */
    suspend fun remoteAddresses(profileId: Long): List<Pair<String, String>> {
        val rt = _runtimes.value[profileId] ?: return emptyList()
        val host = if (profileId == PROFILE_PUBLIC) {
            publicTunnelProvider.endpoint().hostForUrl
                .ifBlank { publicTunnelProvider.endpoint().serverAddr }
        } else {
            frpProfileDao.getById(profileId)?.serverAddr ?: ""
        }
        return rt.proxies.mapNotNull { p ->
            val port = p.remotePort ?: return@mapNotNull null
            p.label to publicTunnelProvider.formatAddress(host, port)
        }
    }

    /** 二维码内容：全部地址（多行文本）。 */
    suspend fun qrContent(profileId: Long): String? {
        val addrs = remoteAddresses(profileId)
        return if (addrs.isEmpty()) null else addrs.joinToString("\n") { (label, addr) -> "$label: $addr" }
    }

    fun runtime(profileId: Long): TunnelRuntime? = _runtimes.value[profileId]

    // ==================== 内部工具 ====================

    private fun tunnelKey(profileId: Long) = "tunnel_$profileId"

    private fun updateRuntime(profileId: Long, transform: (TunnelRuntime) -> TunnelRuntime) {
        val cur = _runtimes.value[profileId] ?: TunnelRuntime(
            profileId = profileId,
            label = if (profileId == PROFILE_PUBLIC) "公共隧道" else "自建 FRP",
            server = "",
        )
        _runtimes.value = _runtimes.value + (profileId to transform(cur))
    }

    private fun appendError(profileId: Long, msg: String) {
        updateRuntime(profileId) { it.copy(lastError = msg.take(300)) }
    }

    // ===== WakeLock（WAKE_LOCK 可选开关，防止息屏断流） =====

    private fun maybeAcquireWakeLock() {
        if (!wakeLockEnabledCache) return
        if (wakeLock?.isHeld == true) return
        val running = _runtimes.value.values.any { it.state is ProcessState.Running }
        if (!running) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DirTunnel:tunnel").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L)
            }
        } catch (t: Throwable) {
        }
    }

    private fun maybeReleaseWakeLock() {
        val running = _runtimes.value.values.any { it.state is ProcessState.Running }
        if (!running) releaseWakeLock()
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (t: Throwable) {
        }
        wakeLock = null
    }
}
