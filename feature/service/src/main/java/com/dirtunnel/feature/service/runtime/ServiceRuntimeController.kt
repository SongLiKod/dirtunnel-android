package com.dirtunnel.feature.service.runtime

import android.content.Context
import android.os.PowerManager
import com.dirtunnel.core.engine.config.EngineConfigBuilder
import com.dirtunnel.core.engine.network.LanAddresses
import com.dirtunnel.core.engine.model.ProcessState
import com.dirtunnel.core.engine.model.ServiceRuntimeStatus
import com.dirtunnel.core.engine.model.ServiceSpec
import com.dirtunnel.core.engine.process.BinaryManager
import com.dirtunnel.core.engine.process.PortChecker
import com.dirtunnel.core.engine.process.ProcessSupervisor
import com.dirtunnel.core.engine.proxy.ProxyConfig
import com.dirtunnel.core.engine.proxy.ProxyEvent
import com.dirtunnel.core.engine.proxy.ProxyEventListener
import com.dirtunnel.core.engine.proxy.ProxyEventType
import com.dirtunnel.core.engine.proxy.TcpProxyServer
import com.dirtunnel.core.storage.dao.DirectoryDao
import com.dirtunnel.core.storage.dao.LogDao
import com.dirtunnel.core.storage.dao.ServiceDao
import com.dirtunnel.core.storage.data.SecurityStore
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.core.storage.entity.ConnectionEvent
import com.dirtunnel.core.storage.entity.ConnectionLogEntity
import com.dirtunnel.core.storage.entity.DirectoryEntity
import com.dirtunnel.core.storage.entity.ServiceInstanceEntity
import com.dirtunnel.core.storage.entity.TransferDirection
import com.dirtunnel.core.storage.model.AccessPermission
import com.dirtunnel.core.storage.model.ProtocolType
import com.dirtunnel.core.storage.entity.TransferLogEntity
import com.dirtunnel.core.storage.model.PROFILE_PUBLIC
import com.dirtunnel.core.storage.saf.SaFDocumentTree
import com.dirtunnel.core.ui.components.AppleInfoDialogSpec
import com.dirtunnel.core.ui.components.AppleInfoField
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 启动结果：失败必带解决方案（REQ 五 · 易用性）。 */
sealed interface StartResult {
    data object Success : StartResult
    data class Failure(val message: String, val solution: String) : StartResult
}

/** 结构化连接信息（一键启动成功后展示；字段可复制，REQ 五 · 易用性）。 */
data class ConnectionInfo(
    /** 完整连接地址，如 ftp://192.168.1.5:2121 */
    val address: String,
    /** 协议 scheme（ftp/ftps/sftp/http，空串=未知） */
    val scheme: String,
    val host: String,
    val port: Int,
    val anonymous: Boolean,
    val username: String,
    val password: String,
    /** 共享目录展示名与路径（空 = 未知，如 SAF 目录无法映射文件系统路径） */
    val sharedDirName: String = "",
    val sharedDirPath: String = "",
    /** 其余可用地址（多网卡时，连不上换这个） */
    val alternatives: List<String> = emptyList(),
    /** 警示条（环境/模式提示，必带方案） */
    val notices: List<String> = emptyList(),
    val tip: String = "",
)

/** 映射为可复制信息弹窗（core:ui · AppleInfoDialog）。 */
fun ConnectionInfo.toDialogSpec(
    title: String,
    subtitle: String = "同一 WiFi 下即可连接",
): AppleInfoDialogSpec = AppleInfoDialogSpec(
    title = title,
    subtitle = subtitle,
    fields = buildList {
        add(AppleInfoField("连接地址", address))
        if (!anonymous) {
            add(AppleInfoField("登录账号", username))
            add(AppleInfoField("登录密码", password))
        }
        add(AppleInfoField("监听端口", port.toString()))
        if (sharedDirPath.isNotBlank()) {
            add(AppleInfoField("共享目录", "$sharedDirName · $sharedDirPath"))
        }
        alternatives.forEach { add(AppleInfoField("备选地址", it)) }
    },
    notices = notices,
    tip = tip,
    copyAllText = buildString {
        appendLine("地址：$address")
        if (!anonymous) {
            appendLine("账号：$username")
            appendLine("密码：$password")
        }
        append("端口：$port")
        if (sharedDirPath.isNotBlank()) {
            appendLine()
            append("共享目录：$sharedDirName $sharedDirPath")
        }
        alternatives.forEach { appendLine(); append("备选：$it") }
    },
)

/**
 * 服务实例运行时（REQ 4.2：一键启动、停止、重启、查看状态；
 * REQ 4.4：IP白名单 / 连接与流量日志；REQ 4.6：仪表盘数据源）。
 *
 * 架构：子进程只监听 127.0.0.1:internalPort，
 * TcpProxyServer 绑定用户端口做白名单/限速/连接数/记账后转发。
 */
@Singleton
class ServiceRuntimeController @Inject constructor(
    @ApplicationContext private val context: Context,
    private val serviceDao: ServiceDao,
    private val directoryDao: DirectoryDao,
    private val logDao: LogDao,
    private val security: SecurityStore,
    private val settingsStore: SettingsStore,
    private val supervisor: ProcessSupervisor,
    private val binaryManager: BinaryManager,
    private val saf: SaFDocumentTree,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val proxies = LinkedHashMap<Long, TcpProxyServer>()
    private val stateJobs = mutableMapOf<Long, Job>()

    private val _statuses = MutableStateFlow<Map<Long, ServiceRuntimeStatus>>(emptyMap())
    val statuses: StateFlow<Map<Long, ServiceRuntimeStatus>> = _statuses.asStateFlow()

    private val _runtimeLogs = MutableStateFlow<Map<Long, List<String>>>(emptyMap())
    /** 每实例运行日志（最近 300 行，服务详情展示）。 */
    val runtimeLogs: StateFlow<Map<Long, List<String>>> = _runtimeLogs.asStateFlow()

    private val _anyRunning = MutableStateFlow(false)
    val anyRunning: StateFlow<Boolean> = _anyRunning.asStateFlow()

    private var wakeLock: PowerManager.WakeLock? = null

    // 设置缓存（子进程回调线程同步读取）
    @Volatile private var autoRestartCache = true
    @Volatile private var recordSecretsCache = false
    @Volatile private var wakeLockEnabledCache = false
    @Volatile private var certPathCache = ""
    @Volatile private var globalWhitelistCache: List<String> = emptyList()

    init {
        scope.launch {
            settingsStore.settings.collect { s ->
                autoRestartCache = s.autoRestartProcess
                recordSecretsCache = s.recordSecretsInLog
                wakeLockEnabledCache = s.wakeLockEnabled
                certPathCache = s.customCertPath
                globalWhitelistCache = s.globalWhitelist.toList()
            }
        }
        // 1s 采样：在线连接数、实时流量（REQ 4.6）
        scope.launch {
            while (isActive) {
                delay(1_000)
                sampleStats()
            }
        }
    }

    // ==================== 启动 ====================

    suspend fun start(serviceId: Long): StartResult {
        val entity = serviceDao.getById(serviceId)
            ?: return StartResult.Failure("服务不存在", "可能已被删除，请返回列表刷新后重试。")
        return startInternal(entity)
    }

    suspend fun startAll(ids: List<Long>): Int {
        var ok = 0
        ids.forEach { id ->
            if (start(id) is StartResult.Success) ok++
        }
        return ok
    }

    private suspend fun startInternal(entity: ServiceInstanceEntity): StartResult {
        val id = entity.id

        // 若已在运行：先等价重启（端口/参数变更场景）
        if (_statuses.value[id]?.isRunning == true || proxies.containsKey(id)) {
            stopInternal(id, clearStatus = false)
        }

        updateStatus(id, ServiceRuntimeStatus(id, ProcessState.Starting))

        // 1) 目录检查
        val dir = directoryDao.getById(entity.directoryId)
            ?: return fail(id, "未绑定目录", "该服务的绑定目录已被删除。方案：重新编辑服务选择目录。")
        // 纯文件系统路径目录（treeUri 为空，如一键启动自动创建的共享目录）无需 SAF 授权
        val needsSaF = dir.treeUri.isNotBlank()
        if (needsSaF && (!saf.hasPersistable(dir.treeUri) || !saf.isStillValid(dir.treeUri))) {
            return fail(
                id, "SAF 目录授权已失效",
                "重启或系统回收后授权可能丢失。方案：在目录页重新选择并授权该文件夹（持久化授权）。",
            )
        }
        val fsPath = dir.fsPath ?: saf.resolveFsPath(dir.treeUri)
            ?: return fail(
                id, "该分区无法直接映射文件系统路径",
                "子进程协议无法挂载此 URI 分区。方案：选择内部存储/SD 卡标准目录（如 Download）后重试。",
            )
        // 安卓 11+ 分区存储：共享存储目录须有「所有文件访问」，否则引擎只能看到文件夹、读不到文件
        if (!isAppOwnPath(fsPath) && !hasAllFilesAccess()) {
            return fail(
                id,
                "缺少「所有文件访问」权限（安卓 11+ 限制）",
                "方案：到「目录」页重新添加该文件夹并按提示开启「允许访问所有文件」；" +
                    "或系统设置→应用→DirTunnel→权限→所有文件访问 打开后，重启本服务。",
            )
        }

        // 2) 端口检查（TECH 七.3 启动前扫描端口）
        val port = entity.port
        if (PortChecker.isPrivileged(port)) {
            return fail(id, PortChecker.privilegedSolution(port).substringBefore("。方案") + "（特权端口）", PortChecker.privilegedSolution(port))
        }
        if (!PortChecker.isAvailable(port)) {
            return fail(id, "端口 $port 已被占用", PortChecker.occupySolution(port))
        }
        val portOwner = serviceDao.getByPort(port, excludeId = id)
        if (portOwner != null && _statuses.value[portOwner.id]?.isRunning == true) {
            return fail(
                id, "端口 $port 已被本应用其他服务占用（${portOwner.name}）",
                "方案：先停止「${portOwner.name}」，或为本服务更换端口。",
            )
        }

        // 3) 账号密码（默认禁止匿名 TECH 六.3）
        val password = if (entity.anonymous) {
            null
        } else {
            if (entity.username.isBlank()) {
                return fail(id, "未配置账号", "所有服务默认禁止匿名访问。方案：填写登录账号与密码，或手动开启“匿名访问”。")
            }
            security.servicePassword(id)?.takeIf { it.isNotEmpty() }
                ?: return fail(id, "未配置密码", "方案：编辑该服务设置登录密码，或开启“匿名访问”开关。")
        }

        // 4) 自定义证书（V1.1）
        var certDir: String? = null
        if (entity.useCustomCert) {
            val certPath = certPathCache
            val certFile = File(certPath, "cert.pem")
            val keyFile = File(certPath, "key.pem")
            if (certPath.isBlank() || !certFile.exists() || !keyFile.exists()) {
                return fail(
                    id, "未导入自定义证书",
                    "方案：设置 → HTTPS 证书 → 导入 cert.pem 与 key.pem（自签或 CA 证书）后重试。",
                )
            }
            certDir = certPath
        }

        // 5) 二进制组件 + md5 校验（TECH 六.6）；首次释放为 assets 拷贝，放 IO 线程
        val protocol = entity.protocolType
        withContext(Dispatchers.IO) {
            binaryManager.ensureForProtocol(protocol.binary)
        }?.let { msg ->
            return fail(id, "协议组件不可用", msg)
        }
        val binaries = buildBinaries(protocol.binary)

        // 6) 内部端口
        val internalPort = PortChecker.findFreePort()
            ?: return fail(id, "找不到可用的内部端口", "方案：重启应用后重试；若仍失败，请关闭占用大量端口的应用。")

        // 7) 生成配置（0600，敏感信息不进命令行）
        val spec = ServiceSpec(
            serviceId = id,
            name = entity.name,
            protocol = protocol,
            rootPath = fsPath,
            internalPort = internalPort,
            username = entity.username,
            password = password,
            anonymous = entity.anonymous,
            permission = entity.accessPermission,
            useCustomCert = entity.useCustomCert,
            certDir = certDir,
        )
        val configDir = File(File(context.filesDir, "configs"), "service_$id")
        configDir.deleteRecursively()
        configDir.mkdirs()

        val processSpec = EngineConfigBuilder.build(spec, binaries, configDir)
            .getOrElse { t ->
                return fail(id, "参数组装失败", "${t.message}。方案：在设置 → 协议组件执行完整性校验后重试。")
            }

        // 8) 先绑定对外端口（代理），再启动子进程
        val effectiveWhitelist = (globalWhitelistCache + entity.whitelistSet()).distinct()
        val proxy = TcpProxyServer(
            config = ProxyConfig(
                key = "service_$id",
                publicPort = port,
                backendPort = internalPort,
                whitelist = effectiveWhitelist,
                maxConnections = entity.maxConnections,
                rateLimitKbps = entity.rateLimitKbps,
            ),
            listener = ProxyEventListener { event -> onProxyEvent(entity, event) },
        )
        if (!proxy.start()) {
            return fail(id, "对外端口 $port 绑定失败", PortChecker.occupySolution(port))
        }
        proxies[id] = proxy

        // 9) 启动子进程（等待内部端口就绪）
        val key = "service_$id"
        val started = supervisor.start(
            spec = processSpec,
            autoRestartProvider = { autoRestartCache },
            onExit = { proc, code, expected ->
                if (!expected) {
                    appendRuntimeLog(id, "[DirTunnel] 进程退出 code=$code")
                }
            },
        )
        if (!started) {
            proxy.stop()
            proxies.remove(id)
            val st = supervisor.get(key)?.state?.value
            val failed = st as? ProcessState.Failed
            return fail(
                id,
                failed?.message ?: "子进程启动失败",
                failed?.solution ?: "方案：查看运行日志、校验协议组件 md5、检查 SELinux 拦截。",
            )
        }

        // 订阅进程状态
        stateJobs[id]?.cancel()
        val proc = supervisor.get(key)
        proc?.let { p ->
            stateJobs[id] = scope.launch {
                p.state.collect { st -> updateProcessState(id, st) }
            }
        }
        proc?.logs?.let { flow ->
            scope.launch {
                flow.collect { line -> appendRuntimeLog(id, line) }
            }
        }

        updateStatus(
            id,
            ServiceRuntimeStatus(
                serviceId = id,
                state = supervisor.get(key)?.state?.value ?: ProcessState.Stopped,
                internalPort = internalPort,
                proxyPort = port,
            ),
        )
        maybeAcquireWakeLock()
        refreshAnyRunning()
        return StartResult.Success
    }

    private fun buildBinaries(needed: String): Map<String, java.io.File> {
        val names = when (needed) {
            "sftp-server" -> listOf("sftp-server", "sshd")
            else -> listOf(needed)
        }
        return names.mapNotNull { n -> binaryManager.executableFile(n)?.let { n to it } }.toMap()
    }

    // ==================== 停止 / 重启 ====================

    suspend fun stop(serviceId: Long) = stopInternal(serviceId, clearStatus = true)

    private fun stopInternal(serviceId: Long, clearStatus: Boolean) {
        proxies.remove(serviceId)?.stop()
        supervisor.stop("service_$serviceId")
        stateJobs[serviceId]?.cancel()
        stateJobs.remove(serviceId)
        if (clearStatus) {
            updateStatus(serviceId, ServiceRuntimeStatus(serviceId, ProcessState.Stopped))
            removeRuntimeLog(serviceId)
        }
        maybeReleaseWakeLock()
        refreshAnyRunning()
    }

    suspend fun restart(serviceId: Long): StartResult {
        stop(serviceId)
        delay(300)
        return start(serviceId)
    }

    suspend fun stopAll() {
        proxies.keys.toList().forEach { stopInternal(it, clearStatus = true) }
        supervisor.stopAll()
        stateJobs.values.forEach { it.cancel() }
        stateJobs.clear()
        _statuses.value = _statuses.value.mapValues { (_, v) -> v.copy(state = ProcessState.Stopped) }
        _runtimeLogs.value = emptyMap()
        releaseWakeLock()
        refreshAnyRunning()
    }

    /** 自动启动（REQ 4.2 是否自动启动 / 开机恢复）。 */
    suspend fun startAutoStartServices(): Int {
        val list = serviceDao.getAutoStart()
        var ok = 0
        list.forEach { if (startInternal(it) is StartResult.Success) ok++ }
        return ok
    }

    // ==================== 事件 / 日志 ====================

    private fun onProxyEvent(entity: ServiceInstanceEntity, event: ProxyEvent) {
        val account = if (recordSecretsCache) entity.username else "***"
        when (event.type) {
            ProxyEventType.CONNECT -> scope.launch {
                logDao.insertConnection(
                    ConnectionLogEntity(
                        serviceId = entity.id,
                        serviceName = entity.name,
                        protocol = entity.protocol,
                        clientIp = event.clientIp,
                        account = account,
                        event = ConnectionEvent.CONNECT.name,
                        message = "连接到 ${entity.name}",
                    ),
                )
            }
            ProxyEventType.DENY -> scope.launch {
                logDao.insertConnection(
                    ConnectionLogEntity(
                        serviceId = entity.id,
                        serviceName = entity.name,
                        protocol = entity.protocol,
                        clientIp = event.clientIp,
                        account = account,
                        event = ConnectionEvent.DENY.name,
                        message = event.reason ?: "被拒绝",
                    ),
                )
            }
            ProxyEventType.ERROR -> scope.launch {
                logDao.insertConnection(
                    ConnectionLogEntity(
                        serviceId = entity.id,
                        serviceName = entity.name,
                        protocol = entity.protocol,
                        clientIp = event.clientIp,
                        account = account,
                        event = ConnectionEvent.ERROR.name,
                        message = event.reason ?: "传输错误",
                    ),
                )
            }
            ProxyEventType.DISCONNECT -> scope.launch {
                logDao.insertConnection(
                    ConnectionLogEntity(
                        serviceId = entity.id,
                        serviceName = entity.name,
                        protocol = entity.protocol,
                        clientIp = event.clientIp,
                        account = account,
                        event = ConnectionEvent.DISCONNECT.name,
                        message = "断开，上传 ${event.bytesUp}B / 下载 ${event.bytesDown}B",
                    ),
                )
                if (event.bytesUp > 0) {
                    logDao.insertTransfer(
                        TransferLogEntity(
                            serviceId = entity.id,
                            serviceName = entity.name,
                            protocol = entity.protocol,
                            clientIp = event.clientIp,
                            direction = TransferDirection.UPLOAD.name,
                            bytes = event.bytesUp,
                            account = account,
                        ),
                    )
                }
                if (event.bytesDown > 0) {
                    logDao.insertTransfer(
                        TransferLogEntity(
                            serviceId = entity.id,
                            serviceName = entity.name,
                            protocol = entity.protocol,
                            clientIp = event.clientIp,
                            direction = TransferDirection.DOWNLOAD.name,
                            bytes = event.bytesDown,
                            account = account,
                        ),
                    )
                }
            }
        }
    }

    // ==================== 状态聚合 ====================

    private fun fail(id: Long, message: String, solution: String): StartResult {
        updateStatus(id, ServiceRuntimeStatus(id, ProcessState.Failed(message, solution)))
        return StartResult.Failure(message, solution)
    }

    private fun updateStatus(id: Long, status: ServiceRuntimeStatus) {
        _statuses.value = _statuses.value + (id to status)
        refreshAnyRunning()
    }

    private fun updateProcessState(id: Long, st: ProcessState) {
        val current = _statuses.value[id] ?: ServiceRuntimeStatus(id, ProcessState.Stopped)
        _statuses.value = _statuses.value + (id to current.copy(state = st))
        refreshAnyRunning()
        if (st is ProcessState.Failed) {
            // 子进程退出后释放对外端口
            proxies.remove(id)?.stop()
        }
    }

    private fun sampleStats() {
        val snapshot = proxies.toMap()
        if (snapshot.isEmpty()) return
        var changed = false
        val next = _statuses.value.toMutableMap()
        snapshot.forEach { (id, proxy) ->
            val stats = proxy.sampleRates()
            val cur = next[id] ?: return@forEach
            if (cur.activeConnections != stats.active ||
                cur.bytesUp != stats.bytesUp ||
                cur.bytesDown != stats.bytesDown
            ) {
                next[id] = cur.copy(
                    activeConnections = stats.active,
                    bytesUp = stats.bytesUp,
                    bytesDown = stats.bytesDown,
                )
                changed = true
            }
        }
        if (changed) _statuses.value = next
    }

    private fun refreshAnyRunning() {
        val any = proxies.isNotEmpty() || supervisor.all().any { it.state.value is ProcessState.Running }
        _anyRunning.value = any
        if (any) maybeAcquireWakeLock() else maybeReleaseWakeLock()
    }

    fun proxyStats(serviceId: Long): ProxyStatsHolder? = proxies[serviceId]?.let { p ->
        ProxyStatsHolder(p.stats.value.active, p.stats.value.bytesUp, p.stats.value.bytesDown)
    }

    data class ProxyStatsHolder(val active: Int, val bytesUp: Long, val bytesDown: Long)

    // ==================== 运行日志缓冲 ====================

    private fun appendRuntimeLog(id: Long, line: String) {
        val cur = _runtimeLogs.value[id].orEmpty()
        val next = (cur + line).takeLast(300)
        _runtimeLogs.value = _runtimeLogs.value + (id to next)
    }

    private fun removeRuntimeLog(id: Long) {
        _runtimeLogs.value = _runtimeLogs.value - id
    }

    // ==================== WakeLock（TECH 三 · WAKE_LOCK 可选开关） ====================

    private fun maybeAcquireWakeLock() {
        if (!wakeLockEnabledCache) return
        if (wakeLock?.isHeld == true) return
        if (!_anyRunning.value) return
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DirTunnel:service").apply {
                setReferenceCounted(false)
                acquire(12 * 60 * 60 * 1000L) // 最长 12h 自动释放，防泄漏
            }
        } catch (t: Throwable) {
        }
    }

    private fun maybeReleaseWakeLock() {
        if (!_anyRunning.value) releaseWakeLock()
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (t: Throwable) {
        }
        wakeLock = null
    }

    /** 供设置页/仪表盘读取（一次性查询）。 */
    suspend fun isAnyRunning(): Boolean {
        refreshAnyRunning()
        return _anyRunning.value
    }

    // ==================== 一键启动（REQ 五 · 易用性：零配置可用） ====================

    /** 一键启动结果：成功携带可直接展示的连接信息。 */
    sealed interface QuickResult {
        data class Success(
            val serviceName: String,
            val info: ConnectionInfo,
            val serviceId: Long,
        ) : QuickResult

        data class Failure(val message: String, val solution: String) : QuickResult
    }

    /**
     * 确保默认共享目录存在：应用专属外部目录（/storage/.../Android/data/.../files/DirTunnel）。
     * 子进程同 UID 可直接读写，无需 SAF 授权、无需任何权限（treeUri 为空，fsPath 直给）。
     */
    suspend fun ensureDefaultDirectory(): DirectoryEntity {
        directoryDao.getAll().firstOrNull { it.name == QUICK_DIR_NAME }?.let { return it }
        val base = context.getExternalFilesDir(null) ?: File(context.filesDir, "shared")
        val root = File(base, "DirTunnel").apply { mkdirs() }
        val entity = DirectoryEntity(
            name = QUICK_DIR_NAME,
            treeUri = "",
            fsPath = root.absolutePath,
            favorite = true,
        )
        val id = directoryDao.insert(entity)
        return entity.copy(id = id)
    }

    /**
     * 已添加过的可用目录（收藏优先，其次列表顺序）：一键启动直接共享用户自己选过的真实文件夹；
     * 一个都没有才回落到空的专用目录。
     */
    private suspend fun bestExistingDirectory(): DirectoryEntity? {
        val usable = directoryDao.getAll().filter { d ->
            when {
                d.fsPath != null -> true
                d.treeUri.isNotBlank() -> saf.hasPersistable(d.treeUri)
                else -> false
            }
        }
        return usable.sortedByDescending { it.favorite }.firstOrNull()
    }

    /**
     * 一键启动：默认/指定目录 + 零配置服务实例（自动选空闲端口、随机强密码、读写权限）+ 立即启动。
     * @param directoryId 指定绑定目录（服务列表页"一键添加"绑定当前目录），null 用默认目录。
     */
    suspend fun quickStart(protocol: ProtocolType, directoryId: Long? = null): QuickResult {
        val dir = directoryId?.let { directoryDao.getById(it) }
            ?: bestExistingDirectory()
            ?: ensureDefaultDirectory()
        var entity = serviceDao.getAll().firstOrNull {
            it.directoryId == dir.id && it.protocol == protocol.name && it.name.startsWith(QUICK_PREFIX)
        }
        val password: String
        if (entity == null) {
            var chosen = -1
            for (p in protocol.defaultPort..protocol.defaultPort + 19) {
                if (serviceDao.getByPort(p) == null && PortChecker.isAvailable(p)) {
                    chosen = p
                    break
                }
            }
            if (chosen < 0) {
                return QuickResult.Failure(
                    "${protocol.label} 默认端口段（${protocol.defaultPort}–${protocol.defaultPort + 19}）已被占满",
                    "方案：释放占用端口的应用，或手动新建服务并指定其他端口。",
                )
            }
            password = randomPassword()
            val id = serviceDao.insert(
                ServiceInstanceEntity(
                    directoryId = dir.id,
                    name = "$QUICK_PREFIX${protocol.label}",
                    protocol = protocol.name,
                    port = chosen,
                    username = QUICK_USER,
                    anonymous = false,
                    permission = AccessPermission.READ_WRITE.name,
                ),
            )
            security.setServicePassword(id, password)
            entity = serviceDao.getById(id)
                ?: return QuickResult.Failure("创建服务实例失败", "方案：重试一次；若仍失败请重启应用。")
        } else {
            password = security.servicePassword(entity.id)
                ?: randomPassword().also { security.setServicePassword(entity.id, it) }
        }
        return when (val r = start(entity.id)) {
            is StartResult.Success -> {
                // 自测（REQ 五 · 提示必附解决方案）：并发探一次对外端口，
                // 把“服务链路异常”与“网络环境不通”两类问题在弹窗里直接分流。
                val probes = withContext(Dispatchers.IO) {
                    coroutineScope {
                        (listOf("127.0.0.1") + lanAddresses()).map { h ->
                            async { h to probeTcp(h, entity.port) }
                        }.awaitAll()
                    }
                }
                val dirEmpty = dir.fsPath?.let { p ->
                    try {
                        File(p).list()?.isEmpty() ?: false
                    } catch (_: Throwable) {
                        false
                    }
                } ?: false
                QuickResult.Success(
                    serviceName = entity.name,
                    info = connectionInfo(
                        protocol, entity.port, entity.username, password, entity.anonymous,
                        probes, dir.name, dir.fsPath.orEmpty(), dirEmpty,
                    ),
                    serviceId = entity.id,
                )
            }
            is StartResult.Failure -> QuickResult.Failure(r.message, r.solution)
        }
    }

    /** 生成结构化连接信息（地址 / 账号 / 密码，REQ 五 · 异常与提示必附解决方案）。 */
    private fun connectionInfo(
        protocol: ProtocolType,
        port: Int,
        username: String,
        password: String,
        anonymous: Boolean,
        probes: List<Pair<String, Boolean>> = emptyList(),
        dirName: String = "",
        dirPath: String = "",
        dirEmpty: Boolean = false,
    ): ConnectionInfo {
        val hosts = lanAddresses()
        val host = hosts.firstOrNull() ?: "127.0.0.1"
        val scheme = when (protocol) {
            ProtocolType.FTP -> "ftp"
            ProtocolType.FTPS -> "ftps"
            ProtocolType.SFTP -> "sftp"
            ProtocolType.HTTP, ProtocolType.WEBDAV -> "http"
            else -> ""
        }
        val notices = buildList {
            if (hosts.isEmpty()) {
                add("未检测到局域网 IP（手机可能只有移动网络）。方案：手机与客户端连同一 WiFi 后，停止并重新打开本服务再查看地址。")
            }
            // 自测结果分流（REQ 五 · 提示必附解决方案）
            val loopbackOk = probes.firstOrNull { it.first == "127.0.0.1" }?.second
            val lanProbes = probes.filter { it.first != "127.0.0.1" }
            when {
                loopbackOk == false ->
                    add("自测失败：本机 $port 端口连不上（服务链路异常，客户端必然连不上）。方案：停止并重新启动本服务；仍失败则强制停止应用后重新打开。")
                lanProbes.isNotEmpty() && lanProbes.none { it.second } ->
                    add("自测：本机 ✓，但所有局域网 IP 都连不上（地址可能已失效）。方案：关闭再打开 WiFi，然后重新启动本服务。")
                lanProbes.any { it.second } ->
                    add(
                        "自测：本机 ✓、" +
                            lanProbes.filter { it.second }.joinToString("、") { it.first } +
                            " ✓，服务正常。若客户端仍连不上：确认客户端与手机连的是同一个 WiFi，且手机未开热点/VPN。",
                    )
            }
            if (dirEmpty) {
                add(
                    "共享目录「$dirName」当前没有文件，客户端会看到空目录。方案：" +
                        "把文件拷进上方「共享目录」路径；或到「目录」页添加想共享的文件夹后，在服务列表用「一键添加」。",
                )
            }
            when (protocol) {
                ProtocolType.FTPS -> add("FTPS 为隐式 TLS：客户端需选「FTP over TLS (Implicit)」模式，端口照填。")
                ProtocolType.WEBDAV -> add("WebDAV：客户端协议选 HTTP，路径根目录为 / 。")
                else -> {}
            }
        }
        return ConnectionInfo(
            address = "$scheme://$host:$port",
            scheme = scheme,
            host = host,
            port = port,
            anonymous = anonymous,
            username = username,
            password = password,
            alternatives = hosts.drop(1).map { "$it:$port" },
            sharedDirName = dirName,
            sharedDirPath = dirPath,
            notices = notices,
            tip = "手机与客户端须连同一 WiFi；连不上先关闭手机 VPN/热点。客户端端口要填「监听端口」（不是 21），密码之后可在服务编辑页查看。",
        )
    }

    /** 本机局域网 IPv4（用于拼连接地址）。 */
    fun lanAddress(): String = lanAddresses().firstOrNull() ?: "127.0.0.1"

    /**
     * 全部可用局域网 IPv4（见 core:engine [LanAddresses]）：
     * 跳过 VPN/拨号虚拟口与链路本地地址，**接受 CGNAT/公网单播地址**（旧的 isSiteLocal 过滤会把
     * 运营商 100.64/10 等网络误杀成“无局域网 IP”只剩 127.0.0.1，实测容器/部分宽带上必现）。
     */
    fun lanAddresses(): List<String> = LanAddresses.all()

    /** 路径是否属于应用自己的目录（同 UID 直读，无需存储权限）。 */
    private fun isAppOwnPath(path: String): Boolean {
        val own = context.getExternalFilesDir(null)?.absolutePath
        return path.startsWith(context.filesDir.absolutePath) ||
            (own != null && path.startsWith(own)) ||
            path.contains("/Android/data/${context.packageName}/")
    }

    /** 安卓 11+：原始路径读写共享存储需「所有文件访问」；以下版本由存储运行时权限覆盖。 */
    private fun hasAllFilesAccess(): Boolean =
        android.os.Build.VERSION.SDK_INT < 30 || android.os.Environment.isExternalStorageManager()

    /**
     * 服务详情（卡片「详情」入口，REQ 五）：任何时候重建完整连接信息；
     * 运行中附回环+局域网自测，未运行/白名单/隧道状态一并提示。
     */
    suspend fun detailInfo(entity: ServiceInstanceEntity): ConnectionInfo {
        val running = _statuses.value[entity.id]?.state is ProcessState.Running ||
            proxies.containsKey(entity.id)
        val password = security.servicePassword(entity.id) ?: ""
        val dir = directoryDao.getById(entity.directoryId)
        val probes = if (running) {
            withContext(Dispatchers.IO) {
                coroutineScope {
                    (listOf("127.0.0.1") + lanAddresses()).map { h ->
                        async { h to probeTcp(h, entity.port) }
                    }.awaitAll()
                }
            }
        } else {
            emptyList()
        }
        val dirEmpty = dir?.fsPath?.let { p ->
            try {
                File(p).list()?.isEmpty() ?: false
            } catch (_: Throwable) {
                false
            }
        } ?: false
        val info = connectionInfo(
            entity.protocolType, entity.port, entity.username, password, entity.anonymous,
            probes, dir?.name.orEmpty(), dir?.fsPath.orEmpty(), dirEmpty,
        )
        val extra = buildList {
            if (!running) add("服务当前未启动：先点「启动」，连接信息才生效。")
            if (entity.whitelist.isNotBlank()) add("已启用 IP 白名单：仅 ${entity.whitelist} 可连接。")
            if (entity.enableTunnel) add("已启用隧道：外网访问地址在「隧道」页对应档案查看（二维码/复制地址）。")
        }
        return info.copy(notices = extra + info.notices)
    }

    /** TCP 自测探针：能连上即代理已受理（1.5s 超时）。 */
    private fun probeTcp(host: String, port: Int): Boolean = try {
        java.net.Socket().use { s ->
            s.connect(java.net.InetSocketAddress(host, port), 1_500)
            true
        }
    } catch (_: Throwable) {
        false
    }

    /** 随机强密码（去易混淆字符，10 位）。 */
    private fun randomPassword(): String {
        val chars = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnpqrstuvwxyz23456789"
        val rnd = java.security.SecureRandom()
        return (1..10).map { chars[rnd.nextInt(chars.length)] }.joinToString("")
    }
}

private const val QUICK_DIR_NAME = "快速启动共享目录"
private const val QUICK_PREFIX = "一键·"
private const val QUICK_USER = "dirtunnel"
