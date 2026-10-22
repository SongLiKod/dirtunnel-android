package com.dirtunnel.core.engine.process

import com.dirtunnel.core.engine.model.PortConflict
import java.net.InetSocketAddress
import java.net.ServerSocket

/**
 * 端口检测（TECH 二 · 端口监听检测 / TECH 七.3 端口冲突规避）：
 * 启动前扫描端口，提示更换端口。
 */
object PortChecker {

    /** 该端口是否可被绑定（0.0.0.0 视角，与服务一致）。 */
    fun isAvailable(port: Int): Boolean {
        return try {
            ServerSocket().use { s ->
                s.reuseAddress = true
                s.bind(InetSocketAddress("0.0.0.0", port))
            }
            // 还需确认 loopback 视角（少数系统双栈分离）
            try {
                ServerSocket().use { s ->
                    s.reuseAddress = true
                    s.bind(InetSocketAddress("127.0.0.1", port))
                }
            } catch (_: Throwable) {
                return false
            }
            true
        } catch (_: Throwable) {
            false
        }
    }

    fun conflict(port: Int): PortConflict =
        PortConflict(port = port, inUse = !isAvailable(port), solution = occupySolution(port))

    fun occupySolution(port: Int): String =
        "端口 $port 已被占用。方案：① 在服务设置中更换一个未占用端口；② 关闭占用该端口的应用；③ 返回后重试。"

    /** 在区间内找一个空闲端口。 */
    fun findFreePort(range: IntRange = 25000..45000): Int? {
        for (p in range) {
            if (isAvailable(p)) return p
        }
        return null
    }

    /** 是否特权端口（无 root/CAP_NET_BIND_SERVICE 无法绑定）。 */
    fun isPrivileged(port: Int): Boolean = port < 1024

    fun privilegedSolution(port: Int): String =
        "端口 $port 属于特权端口（<1024），普通应用无法绑定。方案：改用 ≥1024 的端口，例如 ${port + 1000}。"
}
