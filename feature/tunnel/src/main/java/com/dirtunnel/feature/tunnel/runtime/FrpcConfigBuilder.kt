package com.dirtunnel.feature.tunnel.runtime

/**
 * frpc 配置组装（REQ 4.3：图形化配置，无需手写 ini；内嵌 frpc 客户端）。
 * 生成 frpc TOML；auth.token 属敏感信息 → 由 ProcessSupervisor 以 0600 落盘（TECH 六.2）。
 */
object FrpcConfigBuilder {

    data class FrpcProxy(
        /** frp 代理名（日志解析用，需稳定且合法） */
        val name: String,
        val localPort: Int,
        /** 0 = 由服务器随机分配 */
        val remotePort: Int,
        val description: String = "",
    )

    fun build(
        serverAddr: String,
        serverPort: Int,
        token: String,
        useTls: Boolean,
        proxies: List<FrpcProxy>,
        logFile: String,
        adminPort: Int = 0,
    ): String = buildString {
        appendLine("# DirTunnel 自动生成 frpc 配置（无需手写 ini）")
        appendLine("serverAddr = \"$serverAddr\"")
        appendLine("serverPort = $serverPort")
        appendLine("auth.token = \"$token\"")
        appendLine("transport.tlsEnable = ${if (useTls) "true" else "false"}")
        appendLine("transport.protocol = \"tcp\"")
        appendLine("log.to = \"$logFile\"")
        appendLine("log.level = \"info\"")
        appendLine("loginFailExit = false")
        if (adminPort > 0) {
            // frp v0.61 起客户端日志不再打印分配端口：开本地 admin API 供宿主查询（仅回环可达）
            appendLine("webServer.addr = \"127.0.0.1\"")
            appendLine("webServer.port = $adminPort")
        }
        appendLine("")
        proxies.forEach { p ->
            appendLine("[[proxies]]")
            appendLine("name = \"${p.name}\"")
            appendLine("type = \"tcp\"")
            appendLine("localIP = \"127.0.0.1\"")
            appendLine("localPort = ${p.localPort}")
            if (p.remotePort > 0) {
                appendLine("remotePort = ${p.remotePort}")
            } else {
                appendLine("remotePort = 0")
            }
            appendLine("")
        }
    }

    /** 从 frpc 日志行解析服务器分配的远端端口。 */
    private val remotePortRegex = Regex("""\[([A-Za-z0-9_\-]+)\].*?proxy listen on [\d.]+:(\d+)""")

    /** login 成功标志。 */
    fun isLoginSuccess(line: String): Boolean =
        line.contains("login to server success", ignoreCase = true)

    /** 解析 "xxx tcp proxy listen on 0.0.0.0:12345" → name to port。 */
    fun parseRemotePort(line: String): Pair<String, Int>? {
        val m = remotePortRegex.find(line) ?: return null
        val name = m.groupValues[1]
        val port = m.groupValues[2].toIntOrNull() ?: return null
        return name to port
    }

    /** 是否错误行。 */
    fun isError(line: String): Boolean =
        line.contains("[E]") || line.contains("error", ignoreCase = true)

    /** 合法的 frp 代理名（字母数字_-）。 */
    fun proxyName(prefix: String, id: Long): String =
        "${prefix}_${id}".replace(Regex("[^A-Za-z0-9_-]"), "_")
}
