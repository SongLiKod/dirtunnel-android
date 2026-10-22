package com.dirtunnel.core.engine.proxy

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.concurrent.thread

/** 代理配置：对外端口 → 127.0.0.1 内部服务端口。 */
data class ProxyConfig(
    /** 实例 key（service_<id> / forward_<id>） */
    val key: String,
    /** 对外监听端口（= 用户配置的端口） */
    val publicPort: Int,
    /** 子进程内部端口 */
    val backendPort: Int,
    val backendHost: String = "127.0.0.1",
    /** IP 白名单，空 = 不限制（REQ 4.4） */
    val whitelist: List<String> = emptyList(),
    /** 最大连接数，0 = 不限制（V1.2） */
    val maxConnections: Int = 0,
    /** 服务级限速 KB/s，0 = 不限速（V1.2） */
    val rateLimitKbps: Int = 0,
)

enum class ProxyEventType { CONNECT, DENY, DISCONNECT, ERROR }

/** 代理事件 → 写入连接日志 / 上传下载日志（REQ 4.4）。 */
data class ProxyEvent(
    val key: String,
    val type: ProxyEventType,
    val clientIp: String,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val reason: String? = null,
    val at: Long = System.currentTimeMillis(),
)

data class ProxyStats(
    val active: Int = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val accepted: Long = 0,
    val denied: Long = 0,
    val rateUp: Long = 0,
    val rateDown: Long = 0,
)

fun interface ProxyEventListener {
    fun onEvent(event: ProxyEvent)
}

/**
 * 本地 TCP 代理（REQ 4.3 隧道兼容 TCP / REQ 4.4 IP 白名单 / V1.2 限速与连接数限制）。
 *
 * 架构：服务子进程只监听 127.0.0.1:[backendPort]，
 * 本代理监听 0.0.0.0:[publicPort] 并统一执行：
 * IP 白名单过滤 → 连接数限制 → 双向转发（服务级令牌桶限速）→ 流量记账。
 */
class TcpProxyServer(
    private val config: ProxyConfig,
    private val listener: ProxyEventListener,
) {
    private val _stats = kotlinx.coroutines.flow.MutableStateFlow(ProxyStats())
    val stats: kotlinx.coroutines.flow.StateFlow<ProxyStats> = _stats.asStateFlow()

    private val _running = kotlinx.coroutines.flow.MutableStateFlow(false)
    val running: kotlinx.coroutines.flow.StateFlow<Boolean> = _running.asStateFlow()

    private var serverSocket: ServerSocket? = null
    private var acceptThread: Thread? = null
    private val liveSockets = ConcurrentHashMap<Socket, Unit>()
    private val active = AtomicLong(0)
    private val acceptedTotal = AtomicLong(0)
    private val deniedTotal = AtomicLong(0)
    private val totalUp = AtomicLong(0)
    private val totalDown = AtomicLong(0)
    private val rateLimiter = RateLimiter(config.rateLimitKbps.toLong() * 1024)

    // 速率采样（仪表盘实时流量）
    private var sampleUp = AtomicLong(0)
    private var sampleDown = AtomicLong(0)
    @Volatile private var lastSampleAt = System.currentTimeMillis()
    @Volatile private var sampleRateUp = 0L
    @Volatile private var sampleRateDown = 0L

    fun start(): Boolean {
        if (_running.value) return true
        return try {
            val ss = ServerSocket()
            ss.reuseAddress = true
            ss.bind(InetSocketAddress("0.0.0.0", config.publicPort), 64)
            serverSocket = ss
            _running.value = true
            acceptThread = thread(name = "dirtunnel-proxy-${config.key}", isDaemon = true) { acceptLoop(ss) }
            true
        } catch (t: Throwable) {
            _running.value = false
            false
        }
    }

    fun stop() {
        _running.value = false
        try {
            serverSocket?.close()
        } catch (_: Throwable) {
        }
        serverSocket = null
        liveSockets.keys.forEach { s ->
            try {
                s.close()
            } catch (_: Throwable) {
            }
        }
        liveSockets.clear()
        acceptThread?.interrupt()
        acceptThread = null
        active.set(0)
        updateStats()
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (_running.value) {
            val client = try {
                ss.accept()
            } catch (_: Throwable) {
                break
            }
            val ip = IpMatcher.normalize(client.inetAddress?.hostAddress ?: "unknown")

            // 1) IP 白名单（REQ 4.4）
            if (config.whitelist.isNotEmpty() && !IpMatcher.matches(ip, config.whitelist)) {
                deniedTotal.incrementAndGet()
                closeQuietly(client)
                emit(ProxyEvent(config.key, ProxyEventType.DENY, ip, reason = "IP 不在白名单"))
                continue
            }
            // 2) 连接数限制（V1.2）
            if (config.maxConnections > 0 && active.get() >= config.maxConnections) {
                deniedTotal.incrementAndGet()
                closeQuietly(client)
                emit(ProxyEvent(config.key, ProxyEventType.DENY, ip, reason = "连接数已达上限 ${config.maxConnections}"))
                continue
            }

            acceptedTotal.incrementAndGet()
            liveSockets[client] = Unit
            active.incrementAndGet()
            updateStats()

            thread(name = "dirtunnel-conn-${config.key}-$ip", isDaemon = true) {
                handleConnection(client, ip)
            }
        }
    }

    private fun handleConnection(client: Socket, ip: String) {
        var up = 0L
        var down = 0L
        var deniedReason: String? = null
        var backend: Socket? = null
        try {
            client.tcpNoDelay = true
            val b = Socket()
            b.tcpNoDelay = true
            b.connect(InetSocketAddress(config.backendHost, config.backendPort), 5_000)
            backend = b
            liveSockets[b] = Unit
            emit(ProxyEvent(config.key, ProxyEventType.CONNECT, ip))

            val downThread = thread(name = "relay-dl-$ip", isDaemon = true) {
                down = relay(b.getInputStream(), client.getOutputStream(), countDown = true)
                try {
                    client.shutdownOutput()
                } catch (_: Throwable) {
                }
            }
            up = relay(client.getInputStream(), b.getOutputStream(), countDown = false)
            try {
                b.shutdownOutput()
            } catch (_: Throwable) {
            }
            downThread.join(10_000)
        } catch (t: Throwable) {
            deniedReason = t.message ?: t.javaClass.simpleName
            emit(ProxyEvent(config.key, ProxyEventType.ERROR, ip, reason = deniedReason))
        } finally {
            closeQuietly(backend)
            closeQuietly(client)
            liveSockets.remove(client)
            backend?.let { liveSockets.remove(it) }
            val prevActive = active.getAndDecrement()
            if (prevActive <= 0) active.set(0)
            updateStats()
            emit(ProxyEvent(config.key, ProxyEventType.DISCONNECT, ip, bytesUp = up, bytesDown = down))
        }
    }

    /** 单向转发：读 → 写，带服务级限速与流量记账。返回转发字节数。 */
    private fun relay(input: InputStream, output: OutputStream, countDown: Boolean): Long {
        val buf = ByteArray(16 * 1024)
        var total = 0L
        try {
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                if (n == 0) continue
                rateLimiter.acquire(n)
                output.write(buf, 0, n)
                output.flush()
                total += n
                if (countDown) {
                    totalDown.addAndGet(n.toLong())
                    sampleDown.addAndGet(n.toLong())
                } else {
                    totalUp.addAndGet(n.toLong())
                    sampleUp.addAndGet(n.toLong())
                }
            }
        } catch (_: Throwable) {
        }
        return total
    }

    private fun updateStats() {
        val now = System.currentTimeMillis()
        val elapsed = now - lastSampleAt
        if (elapsed >= 1000) {
            sampleRateUp = sampleUp.getAndSet(0) * 1000 / elapsed
            sampleRateDown = sampleDown.getAndSet(0) * 1000 / elapsed
            lastSampleAt = now
        }
        _stats.value = ProxyStats(
            active = active.get().coerceAtLeast(0L).toInt(),
            bytesUp = totalUp.get(),
            bytesDown = totalDown.get(),
            accepted = acceptedTotal.get(),
            denied = deniedTotal.get(),
            rateUp = sampleRateUp,
            rateDown = sampleRateDown,
        )
    }

    /** 供仪表盘轮询实时速率。 */
    fun sampleRates(): ProxyStats {
        updateStats()
        return _stats.value
    }

    private fun emit(event: ProxyEvent) {
        try {
            listener.onEvent(event)
        } catch (_: Throwable) {
        }
    }

    private fun closeQuietly(socket: Socket?) {
        try {
            socket?.close()
        } catch (_: Throwable) {
        }
    }
}

