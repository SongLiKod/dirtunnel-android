package com.dirtunnel.core.engine.network

/**
 * 本机可用局域网 IPv4 探测（连接地址展示与 FTP PASV 广告地址共用同一逻辑，保证客户端两条连接用同一 IP）。
 *
 * 规则：
 * - 跳过回环 / 链路本地（169.254.x）/ 未指定地址；
 * - 跳过 VPN/拨号/隧道虚拟口（tun/ppp/wg…，其 IP 客户端不可达）；
 * - **接受任意单播 IPv4**（含运营商 CGNAT 100.64/10 与公网地址——同一二层/路由可达网络内均可直连）；
 * - 排序：WiFi（wlan）与有线（eth）优先，其次 RFC1918 私网地址，再按网卡名。
 */
object LanAddresses {
    private val VIRTUAL_IFACE_PREFIXES =
        listOf("tun", "tap", "ppp", "wg", "ipsec", "utun", "rmnet", "dummy", "veth")

    fun all(): List<String> = try {
        java.net.NetworkInterface.getNetworkInterfaces()?.toList().orEmpty()
            .filter { it.isUp && !it.isLoopback }
            .filterNot { n -> VIRTUAL_IFACE_PREFIXES.any { n.name.startsWith(it) } }
            .flatMap { n ->
                n.inetAddresses.toList().mapNotNull { a ->
                    (a as? java.net.Inet4Address)?.takeIf {
                        !it.isLoopbackAddress && !it.isLinkLocalAddress && !it.isAnyLocalAddress
                    }?.let { addr -> n.name to addr }
                }
            }
            .sortedWith(
                compareBy(
                    { if (it.first.startsWith("wlan") || it.first.startsWith("eth")) 0 else 1 },
                    { if (it.second.isSiteLocalAddress) 0 else 1 },
                    { it.first },
                ),
            )
            .map { it.second.hostAddress }
            .distinct()
    } catch (_: Throwable) {
        emptyList()
    }
}
