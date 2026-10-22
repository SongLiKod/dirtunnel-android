package com.dirtunnel.core.storage.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/** 全局单例 DataStore（同名文件只能有一个实例）。 */
val Context.dirtunnelSettingsDataStore by preferencesDataStore(name = "dirtunnel_settings")

/** 全局设置快照（UI 单流订阅）。 */
data class AppSettings(
    val themeMode: String = "SYSTEM",
    val wakeLockEnabled: Boolean = false,
    val autoRestartProcess: Boolean = true,
    val recordSecretsInLog: Boolean = false,
    val advancedFeaturesVisible: Boolean = false,
    val bootAutoStart: Boolean = true,
    val publicTunnelServer: String = "",
    val publicTunnelPort: Int = 7000,
    val publicTunnelHost: String = "",
    val publicTunnelConfigUrl: String = "",
    val globalWhitelist: Set<String> = emptySet(),
    val ddnsEnabled: Boolean = false,
    val ddnsProvider: String = "CLOUDFLARE",
    val ddnsDomain: String = "",
    val ddnsRecord: String = "",
    val customCertPath: String = "",
)

/**
 * DataStore：设置、主题配置、账号配置（TECH 一 · 本地持久化）。
 * 主题枚举持久化为 SYSTEM / LIGHT / DARK（TECH 四 · Compose 实现思路）。
 */
@Singleton
class SettingsStore @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    private object Keys {
        val THEME = stringPreferencesKey("theme_mode")
        val WAKE_LOCK = booleanPreferencesKey("wake_lock_enabled")
        val AUTO_RESTART = booleanPreferencesKey("auto_restart_process")
        val RECORD_SECRETS = booleanPreferencesKey("record_secrets_in_log")
        val ADVANCED = booleanPreferencesKey("advanced_features_visible")
        val BOOT_AUTO_START = booleanPreferencesKey("boot_auto_start")
        val PUBLIC_SERVER = stringPreferencesKey("public_tunnel_server")
        val PUBLIC_PORT = intPreferencesKey("public_tunnel_port")
        val PUBLIC_HOST = stringPreferencesKey("public_tunnel_host")
        val PUBLIC_CONFIG_URL = stringPreferencesKey("public_tunnel_config_url")
        val GLOBAL_WHITELIST = stringSetPreferencesKey("global_ip_whitelist")
        val DDNS_ENABLED = booleanPreferencesKey("ddns_enabled")
        val DDNS_PROVIDER = stringPreferencesKey("ddns_provider")
        val DDNS_DOMAIN = stringPreferencesKey("ddns_domain")
        val DDNS_RECORD = stringPreferencesKey("ddns_record")
        val CERT_PATH = stringPreferencesKey("custom_cert_path")
        val LAST_DDNS_UPDATE = longPreferencesKey("last_ddns_update")
    }

    val settings: Flow<AppSettings> = dataStore.data.map { p ->
        AppSettings(
            themeMode = p[Keys.THEME] ?: "SYSTEM",
            wakeLockEnabled = p[Keys.WAKE_LOCK] ?: false,
            autoRestartProcess = p[Keys.AUTO_RESTART] ?: true,
            recordSecretsInLog = p[Keys.RECORD_SECRETS] ?: false,
            advancedFeaturesVisible = p[Keys.ADVANCED] ?: false,
            bootAutoStart = p[Keys.BOOT_AUTO_START] ?: true,
            publicTunnelServer = p[Keys.PUBLIC_SERVER] ?: "",
            publicTunnelPort = p[Keys.PUBLIC_PORT] ?: 7000,
            publicTunnelHost = p[Keys.PUBLIC_HOST] ?: "",
            publicTunnelConfigUrl = p[Keys.PUBLIC_CONFIG_URL] ?: "",
            globalWhitelist = p[Keys.GLOBAL_WHITELIST] ?: emptySet(),
            ddnsEnabled = p[Keys.DDNS_ENABLED] ?: false,
            ddnsProvider = p[Keys.DDNS_PROVIDER] ?: "CLOUDFLARE",
            ddnsDomain = p[Keys.DDNS_DOMAIN] ?: "",
            ddnsRecord = p[Keys.DDNS_RECORD] ?: "",
            customCertPath = p[Keys.CERT_PATH] ?: "",
        )
    }

    // ===== 主题 =====

    val themeMode: Flow<String> = dataStore.data.map { it[Keys.THEME] ?: "SYSTEM" }

    suspend fun setThemeMode(name: String) = dataStore.edit { it[Keys.THEME] = name }

    // ===== 逐项读写 =====

    suspend fun setWakeLockEnabled(value: Boolean) = dataStore.edit { it[Keys.WAKE_LOCK] = value }
    suspend fun setAutoRestartProcess(value: Boolean) = dataStore.edit { it[Keys.AUTO_RESTART] = value }
    suspend fun setRecordSecretsInLog(value: Boolean) = dataStore.edit { it[Keys.RECORD_SECRETS] = value }
    suspend fun setAdvancedFeaturesVisible(value: Boolean) = dataStore.edit { it[Keys.ADVANCED] = value }
    suspend fun setBootAutoStart(value: Boolean) = dataStore.edit { it[Keys.BOOT_AUTO_START] = value }

    suspend fun setPublicTunnelServer(server: String, port: Int) = dataStore.edit {
        it[Keys.PUBLIC_SERVER] = server
        it[Keys.PUBLIC_PORT] = port
    }

    suspend fun setPublicTunnelHost(host: String) = dataStore.edit { it[Keys.PUBLIC_HOST] = host }
    suspend fun setPublicTunnelConfigUrl(url: String) = dataStore.edit { it[Keys.PUBLIC_CONFIG_URL] = url }

    suspend fun setGlobalWhitelist(whitelist: Set<String>) = dataStore.edit { it[Keys.GLOBAL_WHITELIST] = whitelist }

    suspend fun setDdnsEnabled(enabled: Boolean) = dataStore.edit { it[Keys.DDNS_ENABLED] = enabled }
    suspend fun setDdnsProvider(provider: String) = dataStore.edit { it[Keys.DDNS_PROVIDER] = provider }
    suspend fun setDdnsDomain(domain: String) = dataStore.edit { it[Keys.DDNS_DOMAIN] = domain }
    suspend fun setDdnsRecord(record: String) = dataStore.edit { it[Keys.DDNS_RECORD] = record }

    suspend fun setCustomCertPath(path: String) = dataStore.edit { it[Keys.CERT_PATH] = path }

    suspend fun setLastDdnsUpdate(time: Long) = dataStore.edit { it[Keys.LAST_DDNS_UPDATE] = time }

    suspend fun getLastDdnsUpdate(): Long =
        dataStore.data.first()[Keys.LAST_DDNS_UPDATE] ?: 0L
}
