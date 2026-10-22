package com.dirtunnel.core.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** 网络类型（REQ 兼容性：WiFi / 蜂窝网络 / 热点）。 */
enum class NetworkType { WIFI, CELLULAR, HOTSPOT, ETHERNET, OTHER, OFFLINE }

data class NetworkState(
    val isConnected: Boolean = false,
    val type: NetworkType = NetworkType.OFFLINE,
    val transports: List<String> = emptyList(),
    val isMetered: Boolean = false,
)

/**
 * 网络状态监听（TECH 模块拆分 9 · core:network：网络状态监听）。
 * 覆盖 WiFi / 蜂窝 / 热点（热点通过 WifiManager 反射检测，失败自动降级）。
 */
@Singleton
class NetworkStatusMonitor @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val connectivity =
        appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager = appContext.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    private val _state = MutableStateFlow(readCurrent())
    val state: StateFlow<NetworkState> = _state.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _state.value = readCurrent()
        }

        override fun onLost(network: Network) {
            _state.value = readCurrent()
        }

        override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
            _state.value = readCurrent()
        }
    }

    init {
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (t: Throwable) {
            // 个别 ROM 不支持，退化为手动刷新
        }
    }

    fun refresh() {
        _state.value = readCurrent()
    }

    fun isHotspotActive(): Boolean = try {
        val method = WifiManager::class.java.getMethod("isWifiApEnabled")
        method.invoke(wifiManager) as Boolean
    } catch (t: Throwable) {
        false
    }

    private fun readCurrent(): NetworkState {
        val network = connectivity.activeNetwork
        val caps = network?.let { connectivity.getNetworkCapabilities(it) }
        if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            return NetworkState(isConnected = false, type = NetworkType.OFFLINE)
        }
        val transports = buildList {
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
        }
        val type = when {
            isHotspotActive() -> NetworkType.HOTSPOT
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }
        val metered = try {
            !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
        } catch (t: Throwable) {
            false
        }
        return NetworkState(
            isConnected = true,
            type = type,
            transports = transports,
            isMetered = metered && Build.VERSION.SDK_INT >= 28,
        )
    }
}
