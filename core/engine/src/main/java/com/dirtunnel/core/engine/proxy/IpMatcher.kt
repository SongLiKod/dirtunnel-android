package com.dirtunnel.core.engine.proxy

/**
 * IP 白名单匹配（REQ 4.4 · IP白名单）。
 * 支持：
 * - 精确 IP：`192.168.1.10`
 * - 通配前缀：`192.168.1.*`
 * - CIDR：`192.168.1.0/24`
 * - `*` 放行所有
 * 规则为空表示不限制。
 */
object IpMatcher {

    fun matches(ip: String, rules: List<String>): Boolean {
        val clean = normalize(ip)
        if (rules.none { it.isNotBlank() }) return true
        return rules.any { rule -> matchRule(clean, rule.trim()) }
    }

    fun normalize(ip: String): String {
        var s = ip.trim()
        if (s.startsWith("/")) s = s.substring(1) // Java InetAddress 形如 /192.168.1.5
        if (s.startsWith("::ffff:")) s = s.removePrefix("::ffff:")
        return s
    }

    private fun matchRule(ip: String, rule: String): Boolean = when {
        rule.isEmpty() -> false
        rule == "*" -> true
        rule.endsWith(".*") -> ip.startsWith(rule.dropLast(1)) // 192.168.1.* → 前缀 192.168.1.
        rule.contains("/") -> matchCidr(ip, rule)
        else -> rule.equals(ip, ignoreCase = true)
    }

    private fun matchCidr(ip: String, rule: String): Boolean {
        val (net, prefixStr) = rule.split("/", limit = 2)
        val prefix = prefixStr.toIntOrNull() ?: return false
        if (prefix !in 0..32) return false
        val ipVal = ipv4ToLong(ip) ?: return false
        val netVal = ipv4ToLong(net) ?: return false
        val mask = if (prefix == 0) 0L else (-1L shl (32 - prefix)) and 0xFFFFFFFFL
        return (ipVal and mask) == (netVal and mask)
    }

    private fun ipv4ToLong(s: String): Long? {
        val parts = s.split(".")
        if (parts.size != 4) return null
        var v = 0L
        for (p in parts) {
            val n = p.toIntOrNull() ?: return null
            if (n !in 0..255) return null
            v = (v shl 8) or n.toLong()
        }
        return v and 0xFFFFFFFFL
    }
}

/**
 * 全局（服务级）令牌桶限速（REQ 六 · V1.2 流量限速）。
 * 所有连接共享一个桶，sleep 在锁外进行避免串行化。
 */
class RateLimiter(bytesPerSecond: Long) {
    private val bps = bytesPerSecond.coerceAtLeast(0)
    private var tokens: Double = bps.toDouble()
    private var lastRefill = System.nanoTime()

    fun acquire(byteCount: Int) {
        if (bps <= 0 || byteCount <= 0) return
        val waitMs: Long
        synchronized(this) {
            val now = System.nanoTime()
            val elapsedSec = (now - lastRefill) / 1_000_000_000.0
            lastRefill = now
            tokens = kotlin.math.min(bps.toDouble(), tokens + elapsedSec * bps)
            if (tokens >= byteCount) {
                tokens -= byteCount
                return
            }
            val deficit = byteCount - tokens
            tokens = 0.0
            waitMs = (deficit * 1000.0 / bps).toLong()
        }
        if (waitMs > 0) {
            try {
                Thread.sleep(waitMs.coerceAtMost(500))
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }
    }
}
