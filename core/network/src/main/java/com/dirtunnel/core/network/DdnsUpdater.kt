package com.dirtunnel.core.network

import com.dirtunnel.core.storage.data.SecurityStore
import com.dirtunnel.core.storage.data.SettingsStore
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * DDNS 动态域名更新（REQ 六 · V1.2 DDNS）。
 * 支持：
 * - GENERIC_HTTP：更新地址模板（含 {ip} 占位），通用适配 dynvus/no-ip 类服务；
 * - CLOUDFLARE：ddnsRecord = "zoneId/recordId"，token = Cloudflare API Token；
 * - DNSPOD：ddnsRecord = "子域.域名"，token = "id,token"。
 * token 加密存储（TECH 六.1）。
 */
@Singleton
class DdnsUpdater @Inject constructor(
    private val settings: SettingsStore,
    private val security: SecurityStore,
    @ApplicationContext private val context: Context,
) {
    data class DdnsResult(val success: Boolean, val detail: String)

    /** 获取公网出口 IP。 */
    suspend fun fetchPublicIp(): String? = withContext(Dispatchers.IO) {
        listOf("https://api.ipify.org", "https://ifconfig.me/ip", "https://api.my-ip.io/ip")
            .asSequence()
            .mapNotNull { u ->
                try {
                    val conn = URL(u).openConnection() as HttpURLConnection
                    conn.connectTimeout = 4_000
                    conn.readTimeout = 4_000
                    val ip = conn.inputStream.bufferedReader().use { it.readText() }.trim()
                    conn.disconnect()
                    ip.takeIf { it.matches(Regex("""^\d{1,3}(\.\d{1,3}){3}$""")) }
                } catch (t: Throwable) {
                    null
                }
            }
            .firstOrNull()
    }

    /** 执行一次 DDNS 更新。 */
    suspend fun update(forceIp: String? = null): DdnsResult = withContext(Dispatchers.IO) {
        val s = settings.settings.first()
        if (!s.ddnsEnabled) return@withContext DdnsResult(false, "DDNS 未开启")
        if (s.ddnsDomain.isBlank() && s.ddnsRecord.isBlank()) {
            return@withContext DdnsResult(false, "未配置 DDNS 域名/更新地址")
        }
        val ip = forceIp ?: fetchPublicIp() ?: return@withContext DdnsResult(false, "无法获取公网 IP")
        val token = security.ddnsToken().orEmpty()
        try {
            when (s.ddnsProvider.uppercase()) {
                "CLOUDFLARE" -> cloudflare(s.ddnsRecord, ip, token)
                "DNSPOD" -> dnspod(s.ddnsDomain, s.ddnsRecord, ip, token)
                else -> genericHttp(s.ddnsRecord, ip, token)
            }
        } catch (t: Throwable) {
            DdnsResult(false, "更新失败：${t.message ?: t.javaClass.simpleName}")
        }
    }

    private fun genericHttp(template: String, ip: String, token: String): DdnsResult {
        val urlStr = template.replace("{ip}", ip).replace("{IP}", ip)
        val conn = URL(urlStr).openConnection() as HttpURLConnection
        conn.connectTimeout = 6_000
        conn.readTimeout = 6_000
        if (token.isNotBlank()) {
            conn.setRequestProperty("Authorization", "Bearer $token")
            conn.setRequestProperty("X-Api-Token", token)
        }
        val code = conn.responseCode
        val body = try {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (t: Throwable) {
            ""
        }
        conn.disconnect()
        return DdnsResult(code in 200..299, "HTTP $code ${body.take(120)}；IP=$ip")
    }

    private fun cloudflare(recordSpec: String, ip: String, token: String): DdnsResult {
        val parts = recordSpec.split("/", limit = 2)
        require(parts.size == 2) { "Cloudflare 需在“记录”中填写 zoneId/recordId" }
        val (zone, record) = parts
        val conn = URL("https://api.cloudflare.com/client/v4/zones/$zone/dns_records/$record")
            .openConnection() as HttpURLConnection
        conn.requestMethod = "PUT"
        conn.connectTimeout = 6_000
        conn.readTimeout = 6_000
        conn.doOutput = true
        conn.setRequestProperty("Authorization", "Bearer $token")
        conn.setRequestProperty("Content-Type", "application/json")
        val payload = JSONObject().apply {
            put("type", "A")
            put("content", ip)
            put("ttl", 120)
        }.toString()
        conn.outputStream.use { it.write(payload.toByteArray()) }
        val code = conn.responseCode
        val body = try {
            (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { it.readText() } ?: ""
        } catch (t: Throwable) {
            ""
        }
        conn.disconnect()
        val ok = try {
            JSONObject(body).optBoolean("success", false)
        } catch (t: Throwable) {
            code in 200..299
        }
        return DdnsResult(ok, if (ok) "Cloudflare 更新成功，IP=$ip" else "Cloudflare 返回：${body.take(160)}")
    }

    private fun dnspod(domain: String, subRecord: String, ip: String, token: String): DdnsResult {
        val sub = subRecord.substringBefore('.')
        val conn = URL("https://dnsapi.com/Record.Ddns").openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = 6_000
        conn.readTimeout = 6_000
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        val form = buildString {
            append("login=").append(urlEncode(token))
            append("&domain=").append(urlEncode(domain))
            append("&sub_domain=").append(urlEncode(sub.ifBlank { "@" }))
            append("&record_line=").append(urlEncode("默认"))
            append("&value=").append(urlEncode(ip))
        }
        conn.outputStream.use { it.write(form.toByteArray()) }
        val code = conn.responseCode
        val body = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } catch (t: Throwable) {
            try {
                conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
            } catch (t2: Throwable) {
                ""
            }
        }
        conn.disconnect()
        val ok = try {
            JSONObject(body).getJSONObject("status").optString("code") == "1"
        } catch (t: Throwable) {
            code in 200..299
        }
        return DdnsResult(ok, if (ok) "DNSPod 更新成功，IP=$ip" else "DNSPod 返回：${body.take(160)}")
    }

    private fun urlEncode(s: String): String =
        java.net.URLEncoder.encode(s, "UTF-8")
}
