package com.dirtunnel.core.engine.process

import com.dirtunnel.core.engine.model.ConfigFile
import com.dirtunnel.core.engine.model.ProcessSpec
import com.dirtunnel.core.engine.model.ProcessState
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * 二进制子进程托管（TECH 二 · 进程管理）：
 * ProcessBuilder 启动、监控 pid、捕获 stdout/stderr、监听崩溃、自动重启（可选开关）。
 * 敏感参数写入 0600 临时配置文件，绝不经命令行明文传递（TECH 六.2）。
 */
@Singleton
class ProcessSupervisor @Inject constructor() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val processes = ConcurrentHashMap<String, SupervisedProcess>()

    private val _states = MutableStateFlow<Map<String, ProcessState>>(emptyMap())
    /** 所有受管进程的实时状态快照（key → state），供目录/仪表盘聚合运行数。 */
    val states: StateFlow<Map<String, ProcessState>> = _states.asStateFlow()

    private val watched = ConcurrentHashMap<SupervisedProcess, Boolean>()

    fun get(key: String): SupervisedProcess? = processes[key]

    fun all(): List<SupervisedProcess> = processes.values.toList()

    fun isRunning(key: String): Boolean =
        processes[key]?.state?.value is ProcessState.Running

    /**
     * 取得（必要时重建）受管进程并按需启动。
     * @param autoRestartProvider 崩溃自动重启开关（读取设置）
     * @param onExit 进程退出回调（expected=true 表示主动停止）
     */
    suspend fun start(
        spec: ProcessSpec,
        autoRestartProvider: () -> Boolean,
        onExit: (SupervisedProcess, Int, Boolean) -> Unit = { _, _, _ -> },
    ): Boolean {
        val existing = processes[spec.key]
        val proc = if (existing != null && existing.spec == spec) {
            existing.updateCallbacks(autoRestartProvider, onExit)
            existing
        } else {
            existing?.stopGraceful(graceMs = 1_500)
            SupervisedProcess(spec, autoRestartProvider, onExit)
                .also { processes[spec.key] = it }
                .also { watchState(it) }
        }
        return proc.start()
    }

    /** 将单进程状态汇入全局快照（每个实例仅注册一次）。 */
    private fun watchState(proc: SupervisedProcess) {
        if (watched.putIfAbsent(proc, true) != null) return
        scope.launch {
            proc.state.collect { st ->
                _states.value = _states.value + (proc.spec.key to st)
            }
        }
    }

    fun stop(key: String, graceMs: Long = 3_000) {
        processes[key]?.stopGraceful(graceMs)
    }

    fun stopAll(graceMs: Long = 3_000) {
        processes.values.forEach { it.stopGraceful(graceMs) }
    }

    fun remove(key: String) {
        processes[key]?.stopGraceful(0)
        processes.remove(key)
        _states.value = _states.value - key
    }
}

/** 单个受管子进程。 */
class SupervisedProcess internal constructor(
    @Volatile var spec: ProcessSpec,
    @Volatile var autoRestartProvider: () -> Boolean,
    @Volatile var onExit: (SupervisedProcess, Int, Boolean) -> Unit,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _state = MutableStateFlow<ProcessState>(ProcessState.Stopped)
    val state: StateFlow<ProcessState> = _state.asStateFlow()

    private val _logs = MutableSharedFlow<String>(
        replay = 0,
        extraBufferCapacity = 2_048,
        onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST,
    )
    /** 进程 stdout/stderr 行流（含 [DirTunnel] 前缀的框架日志）。 */
    val logs: SharedFlow<String> = _logs.asSharedFlow()

    @Volatile
    private var process: Process? = null

    /** 进程内引擎句柄（execNative 时有效；-1 = 未运行）。 */
    @Volatile
    private var nativeHandle: Int = -1

    @Volatile
    private var nativeMonitorJob: Job? = null
    private var nativeLogJob: Job? = null

    @Volatile
    var stopRequested: Boolean = false
        private set

    @Volatile
    private var startedAt: Long = 0L

    internal var restartJob: Job? = null
    private var restartAttempt: Int = 0
    private var lastStartAt: Long = 0L

    val pid: Long
        get() = if (spec.execNative) android.os.Process.myPid().toLong() else process?.pidCompat() ?: -1L

    val isAlive: Boolean
        get() = if (spec.execNative) nativeHandle > 0 else process?.isAlive == true

    internal fun incrementRestartAttempt(): Int {
        val now = System.currentTimeMillis()
        if (now - lastStartAt > 60_000) restartAttempt = 0 // 稳定运行 60s 后重置退避
        restartAttempt += 1
        return restartAttempt
    }

    internal fun updateCallbacks(
        autoRestart: () -> Boolean,
        onExit: (SupervisedProcess, Int, Boolean) -> Unit,
    ) {
        autoRestartProvider = autoRestart
        this.onExit = onExit
    }

    internal fun appendLog(line: String) {
        _logs.tryEmit(line)
    }

    /**
     * 启动子进程并等待内部端口就绪（REQ 五 · 性能：单服务启动 <1s，这里给 8s 上限）。
     * @return true=进入 Running；false=失败（state 为 Failed）
     */
    suspend fun start(): Boolean {
        if (isAlive && !stopRequested) return true
        _state.value = ProcessState.Starting
        stopRequested = false

        val ok = withContext(Dispatchers.IO) {
            try {
                writeConfigFiles()
                if (spec.execNative) startNativeLocked() else startProcessLocked()
            } catch (t: Throwable) {
                _state.value = ProcessState.Failed(
                    message = "启动失败：${t.message ?: t.javaClass.simpleName}",
                    solution = startFailSolution(),
                )
                appendLog("[DirTunnel] 启动失败: $t")
                false
            }
        }
        if (!ok) return false

        // 等待内部端口就绪
        if (spec.readyPort > 0) {
            val deadline = System.currentTimeMillis() + spec.readyTimeoutMs
            while (System.currentTimeMillis() < deadline) {
                if (!isAlive) break
                if (!PortChecker.isAvailable(spec.readyPort)) return true // 正在监听 = 已就绪
                delay(60)
            }
            if (!isAlive || PortChecker.isAvailable(spec.readyPort)) {
                val msg = "服务进程未能在 ${spec.readyTimeoutMs / 1000}s 内监听端口 ${spec.readyPort}"
                _state.value = ProcessState.Failed(
                    message = msg,
                    solution = "① 查看下方运行日志定位报错；② 确认二进制组件完整（设置 → 协议组件 → md5 校验）；③ 检查 SELinux/厂商后台限制（TECH 七.2）。",
                )
                appendLog("[DirTunnel] $msg")
                stopGraceful(graceMs = 500)
                return false
            }
        }
        return true
    }

    /** 进程形态启动（ProcessBuilder 子进程，TECH 二）。 */
    private suspend fun startProcessLocked(): Boolean {
        val pb = ProcessBuilder(listOf(spec.binaryPath) + spec.args)
            .directory(File(spec.workDir))
            .redirectErrorStream(true)
        spec.env.forEach { (k, v) -> pb.environment()[k] = v }

        val p = pb.start()
        process = p
        startedAt = System.currentTimeMillis()
        lastStartAt = startedAt
        restartAttempt = 0
        _state.value = ProcessState.Running(p.pidCompat(), startedAt)
        appendLog("[DirTunnel] 已启动 ${spec.displayLabel} pid=${p.pidCompat()} 端口=${spec.readyPort}")
        pumpOutput(p)
        return true
    }

    /**
     * 进程内引擎启动（JNI：libdirtunneld.so）。
     * Android 10+ 禁止 untrusted 应用 exec 自身数据目录文件（SELinux W^X），
     * 这是 V1.0 协议在真机可跑的正解；引擎仍只绑 127.0.0.1，对外经代理层。
     */
    private fun startNativeLocked(): Boolean {
        val cfg = File(spec.workDir, spec.nativeConfigName)
        if (!cfg.exists()) {
            _state.value = ProcessState.Failed(
                message = "启动失败：引擎配置 ${cfg.name} 未生成",
                solution = "方案：先停止再启动服务（自动重建 0600 配置）；若仍失败，重启应用后重试。",
            )
            appendLog("[DirTunnel] 启动失败: 配置缺失 ${cfg.path}")
            return false
        }
        val h = NativeEngine.start(spec.nativeMode, cfg.absolutePath)
        if (h < 0) {
            val why = NativeEngine.lastErrorText()
            _state.value = ProcessState.Failed(
                message = "内置引擎启动失败：$why",
                solution = "① 端口被占用就再启动一次（会自动换内部端口）；② 设置 → 协议组件 应显示「已校验」；③ 若提示 libdirtunneld.so 未找到，安装 arm64-v8a 官方包。",
            )
            appendLog("[DirTunnel] 内置引擎启动失败: $why")
            return false
        }
        nativeHandle = h
        startedAt = System.currentTimeMillis()
        lastStartAt = startedAt
        restartAttempt = 0
        _state.value = ProcessState.Running(android.os.Process.myPid().toLong(), startedAt)
        appendLog("[DirTunnel] 已启动(进程内) ${spec.displayLabel} mode=${spec.nativeMode} 端口=${spec.readyPort}")
        startNativeLogTail()
        startNativeMonitor()
        return true
    }

    /** 进程内引擎日志尾随（frpc 等经文件日志输出登录/端口信息，宿主解析需要）。 */
    private fun startNativeLogTail() {
        val path = spec.nativeLogPath ?: return
        nativeLogJob?.cancel()
        nativeLogJob = scope.launch(Dispatchers.IO) {
            var offset = 0L
            val file = File(path)
            while (isActive && !stopRequested && nativeHandle > 0) {
                try {
                    if (file.exists() && file.length() > offset) {
                        java.io.RandomAccessFile(file, "r").use { raf ->
                            raf.seek(offset)
                            var line = raf.readLine()
                            while (line != null) {
                                _logs.tryEmit(line)
                                line = raf.readLine()
                            }
                            offset = raf.filePointer
                        }
                    }
                } catch (_: Throwable) {
                }
                delay(700)
            }
        }
    }

    /**
     * 进程内引擎健康监视：内部端口连续消失 ≈ 引擎退出，
     * 走与子进程相同的 Exited/自动重启流程（进程内运行，无 pid 可盯）。
     */
    private fun startNativeMonitor() {
        nativeMonitorJob?.cancel()
        val port = spec.readyPort
        nativeMonitorJob = scope.launch(Dispatchers.IO) {
            // 无内部端口（如 frpc 隧道）：进程内引擎与宿主进程同生，无端口可盯，不做健康监视
            if (port <= 0) return@launch
            var misses = 0
            while (isActive && !stopRequested && nativeHandle > 0) {
                delay(4_000)
                if (stopRequested || nativeHandle <= 0) break
                val listening = port > 0 && !PortChecker.isAvailable(port)
                if (listening) {
                    misses = 0
                    continue
                }
                misses += 1
                if (misses < 2) continue
                val why = NativeEngine.lastErrorText()
                nativeHandle = -1
                appendLog("[DirTunnel] 内置引擎已退出（端口 $port 无监听）：$why")
                val expected = stopRequested
                _state.value = if (expected) {
                    ProcessState.Stopped
                } else {
                    ProcessState.Exited(-1, System.currentTimeMillis())
                }
                onExit(this@SupervisedProcess, -1, expected)
                if (!expected) scheduleAutoRestart(-1)
                break
            }
        }
    }

    private fun startFailSolution(): String = if (spec.execNative) {
        "① 查看 message 中的引擎报错（端口占用、共享目录不可写最常见）；② 设置 → 协议组件 应显示「已校验」；③ 检查厂商后台/省电限制（TECH 七.2）。"
    } else {
        "请到 设置 → 协议组件 确认组件已安装且 md5 校验通过；若仍失败，检查 SELinux 是否拦截子进程（设置中提供检测与引导）。"
    }

    /** 主动停止（SIGTERM，超时强杀）；不会触发自动重启。 */
    fun stopGraceful(graceMs: Long = 3_000) {
        stopRequested = true
        restartJob?.cancel()
        restartJob = null
        if (spec.execNative) {
            stopNative()
            return
        }
        val p = process ?: run {
            _state.value = ProcessState.Stopped
            return
        }
        if (_state.value is ProcessState.Running) _state.value = ProcessState.Stopping
        appendLog("[DirTunnel] 正在停止 ${spec.displayLabel}")
        scope.launch(Dispatchers.IO) {
            try {
                p.destroy()
                val finished = p.waitFor(graceMs, TimeUnit.MILLISECONDS)
                if (!finished) {
                    p.destroyForcibly()
                    p.waitFor(1, TimeUnit.SECONDS)
                }
            } catch (_: Throwable) {
            } finally {
                if (process === p) process = null
                _state.value = ProcessState.Stopped
                appendLog("[DirTunnel] 已停止 ${spec.displayLabel}")
            }
        }
    }

    /** 进程内引擎停止：关闭句柄对应的监听与在途连接（Graceful 等同关闭监听）。 */
    private fun stopNative() {
        val h = nativeHandle
        nativeMonitorJob?.cancel()
        nativeMonitorJob = null
        nativeLogJob?.cancel()
        nativeLogJob = null
        if (h <= 0) {
            _state.value = ProcessState.Stopped
            return
        }
        if (_state.value is ProcessState.Running) _state.value = ProcessState.Stopping
        appendLog("[DirTunnel] 正在停止(进程内) ${spec.displayLabel}")
        scope.launch(Dispatchers.IO) {
            try {
                NativeEngine.stop(h)
            } catch (t: Throwable) {
                appendLog("[DirTunnel] 停止异常: $t")
            } finally {
                if (nativeHandle == h) nativeHandle = -1
                _state.value = ProcessState.Stopped
                appendLog("[DirTunnel] 已停止 ${spec.displayLabel}")
            }
        }
    }

    fun destroyForcibly() {
        stopRequested = true
        restartJob?.cancel()
        nativeMonitorJob?.cancel()
        nativeMonitorJob = null
        nativeLogJob?.cancel()
        nativeLogJob = null
        if (spec.execNative) {
            val h = nativeHandle
            nativeHandle = -1
            if (h > 0) NativeEngine.stop(h)
        } else {
            try {
                process?.destroyForcibly()
            } catch (_: Throwable) {
            }
            process = null
        }
        _state.value = ProcessState.Stopped
    }

    private suspend fun pumpOutput(p: Process) {
        scope.launch(Dispatchers.IO) {
            try {
                p.inputStream.bufferedReader().use { reader ->
                    while (isActive) {
                        val line = reader.readLine() ?: break
                        _logs.tryEmit(line)
                    }
                }
            } catch (_: Throwable) {
            }
            // 读到 EOF：等待退出回调
            val code = try {
                p.waitFor()
            } catch (_: Throwable) {
                -1
            }
            if (process === p) {
                process = null
                val expected = stopRequested
                if (!expected) {
                    _state.value = ProcessState.Exited(code, System.currentTimeMillis())
                } else {
                    _state.value = ProcessState.Stopped
                }
                onExit(this@SupervisedProcess, code, expected)
                if (!expected) scheduleAutoRestart(code)
            }
        }
    }

    /** 崩溃自动重启（可选开关）：指数退避，稳定 60s 后重置。 */
    private fun scheduleAutoRestart(exitCode: Int) {
        if (!autoRestartProvider()) return
        if (restartJob?.isActive == true) return
        val attempt = incrementRestartAttempt()
        val backoff = (500L * (1L shl (attempt - 1).coerceAtMost(6))).coerceAtMost(30_000L)
        restartJob = scope.launch {
            delay(backoff)
            if (stopRequested) return@launch
            appendLog("[DirTunnel] 进程异常退出(code=$exitCode)，${backoff}ms 后自动重启(第 $attempt 次)")
            start()
        }
    }

    /** 按 0600 权限写入配置文件（TECH 六.2）。 */
    private fun writeConfigFiles() {
        if (spec.configFiles.isEmpty()) return
        val dir = File(spec.workDir).apply { mkdirs() }
        spec.configFiles.forEach { cfg ->
            val f = File(dir, cfg.name)
            f.writeText(cfg.content)
            // rw------- (0600)
            f.setReadable(false, false)
            f.setWritable(false, false)
            f.setReadable(true, true)
            f.setWritable(true, true)
            f.setExecutable(false, false)
        }
    }

    /** Process.pid() 在部分运行时不可用，反射兜底。 */
    private fun Process.pidCompat(): Long = try {
        val m = java.lang.Process::class.java.getMethod("pid")
        (m.invoke(this) as Number).toLong()
    } catch (_: Throwable) {
        -1L
    }
}
