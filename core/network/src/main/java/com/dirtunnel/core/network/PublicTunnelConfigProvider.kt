package com.dirtunnel.core.network

import android.content.Context
import com.dirtunnel.core.storage.data.SecurityStore
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.core.storage.model.PROFILE_PUBLIC
import dagger.hilt.android.qualifiers.ApplicationContext
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/** 公共隧道端点（REQ 4.3.1：内置服务，一键穿透）。 */
data class PublicTunnelEndpoint(
    val serverAddr: String,
    val serverPort: Int,
    val token: String,
    /** 生成外网访问地址使用的域名/IP */
    val hostForUrl: String,
    /** 配置来源：未配置 / 手动配置 / 远端下发 */
    val source: String,
)

/**
 * 公共隧道端点提供者：
 * - 内置默认服务器（一键使用）；
 * - 设置页可手动覆盖（服务器地址/端口）；
 * - 可选远端 JSON 下发：{"server":"","port":7000,"token":"","host":""}。
 * token 始终存 EncryptedSharedPreferences（TECH 六.1）。
 */
@Singleton
class PublicTunnelConfigProvider @Inject constructor(
    private val settings: SettingsStore,
    private val security: SecurityStore,
    @ApplicationContext private val context: Context,
) {
    suspend fun endpoint(): PublicTunnelEndpoint {
        val s = settings.settings.first()
        val serverAddr = s.publicTunnelServer.trim()
        val serverPort = if (s.publicTunnelPort in 1..65535) s.publicTunnelPort else DEFAULT_PORT
        val token = security.frpToken(PROFILE_PUBLIC) ?: ""
        val host = s.publicTunnelHost.trim().ifBlank { serverAddr }
        return PublicTunnelEndpoint(
            serverAddr = serverAddr,
            serverPort = serverPort,
            token = token,
            hostForUrl = host,
            source = if (serverAddr.isBlank()) "未配置" else "手动配置",
        )
    }

    /** 覆盖公共隧道服务器（设置页）。token 落加密存储。 */
    suspend fun configure(server: String, port: Int, token: String?, host: String?) {
        settings.setPublicTunnelServer(server, port)
        settings.setPublicTunnelHost(host ?: "")
        if (!token.isNullOrBlank()) security.setFrpToken(PROFILE_PUBLIC, token)
    }

    /** 拉取远端 JSON 覆盖内置端点（可选增强）。返回是否成功。 */
    suspend fun refreshFromRemote(): Boolean = withContext(Dispatchers.IO) {
        val url = settings.settings.first().publicTunnelConfigUrl
        if (url.isBlank()) return@withContext false
        try {
            val conn = URL(url).openConnection() as HttpURLConnection
            conn.connectTimeout = 5_000
            conn.readTimeout = 5_000
            val body = conn.inputStream.bufferedReader().use { it.readText() }
            conn.disconnect()
            val json = JSONObject(body)
            val server = json.optString("server", "")
            val port = json.optInt("port", 0)
            val token = json.optString("token", "")
            val host = json.optString("host", "")
            if (server.isNotBlank() && port in 1..65535) {
                settings.setPublicTunnelServer(server, port)
                if (host.isNotBlank()) settings.setPublicTunnelHost(host)
                if (token.isNotBlank()) security.setFrpToken(PROFILE_PUBLIC, token)
                true
            } else {
                false
            }
        } catch (t: Throwable) {
            false
        }
    }

    /** 外网访问地址展示（REQ 4.3.1 生成外网访问地址）。 */
    fun formatAddress(host: String, remotePort: Int): String = "tcp://$host:$remotePort"

    /** 用于二维码的完整内容。 */
    fun qrContent(host: String, remotePort: Int): String = formatAddress(host, remotePort)

    companion object {
        // 无内置公共服务器：可靠的一键公网必须由运营方提供真实 frps 端点，
        // 不存在免费可靠的公共服务。未配置时 UI 引导：自有 frps / IPv6 直连 / 自建档案。
        const val DEFAULT_SERVER = ""
        const val DEFAULT_PORT = 7000
        const val DEFAULT_HOST = ""
        const val DEFAULT_TOKEN = ""
    }
}
