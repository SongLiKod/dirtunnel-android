package com.dirtunnel.core.engine.network

import java.net.Inet6Address
import java.net.NetworkInterface

/**
 * 全局单播 IPv6（2000::/3）探测。
 * 蜂窝网络普遍分配全局 IPv6：无 NAT、直达——是不依赖任何服务器的
 * 「真实可用公网直连」来源（访问端也需支持 IPv6）。
 */
object GlobalIpv6 {
    fun addresses(): List<String> = try {
        val out = mutableListOf<String>()
        NetworkInterface.getNetworkInterfaces()?.toList()?.forEach { nif ->
            if (!nif.isUp || nif.isLoopback) return@forEach
            nif.inetAddresses?.toList()?.forEach { addr ->
                if (addr is Inet6Address &&
                    !addr.isLoopbackAddress &&
                    !addr.isLinkLocalAddress &&
                    (addr.address[0].toInt() and 0xE0) == 0x20
                ) {
                    out += addr.hostAddress.substringBefore('%')
                }
            }
        }
        out.distinct()
    } catch (_: Throwable) {
        emptyList()
    }
}
