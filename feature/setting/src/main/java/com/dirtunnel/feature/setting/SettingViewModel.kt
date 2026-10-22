package com.dirtunnel.feature.setting

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.engine.health.SystemHealthMonitor
import com.dirtunnel.core.engine.process.BinaryManager
import com.dirtunnel.core.network.DdnsUpdater
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.core.ui.components.AppleAlert
import com.dirtunnel.core.ui.theme.ThemeMode
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** DDNS 服务商选项（V1.2 DDNS）。 */
data class DdnsProviderOption(val id: String, val label: String, val hint: String)

/**
 * 全局设置（TECH 模块拆分 6 · feature:setting：主题切换、全局设置、证书导入、高级开关）。
 */
@HiltViewModel
class SettingViewModel @Inject constructor(
    private val settingsStore: SettingsStore,
    private val healthMonitor: SystemHealthMonitor,
    private val binaryManager: BinaryManager,
    private val ddnsUpdater: DdnsUpdater,
) : ViewModel() {

    val settings = settingsStore.settings
        .stateIn(viewModelScope, SharingStarted.Eagerly, com.dirtunnel.core.storage.data.AppSettings())

    val themeMode: StateFlow<ThemeMode> = settingsStore.themeMode
        .map { ThemeMode.fromName(it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ThemeMode.SYSTEM)

    val batteryRestricted: StateFlow<Boolean?> = healthMonitor.batteryRestricted

    /** 底部导航栏不透明度（设置 → 外观）。 */
    fun setNavBarOpacity(value: Float) {
        viewModelScope.launch { settingsStore.setNavBarOpacity(value) }
    }

    /** 协议组件 md5 校验结果（TECH 六.6）。 */
    private val _binaries = MutableStateFlow<List<com.dirtunnel.core.engine.model.BinaryStatus>>(emptyList())
    val binaries: StateFlow<List<com.dirtunnel.core.engine.model.BinaryStatus>> = _binaries.asStateFlow()

    private val _alert = MutableStateFlow<AppleAlert?>(null)
    val alert: StateFlow<AppleAlert?> = _alert.asStateFlow()

    private val _ddnsResult = MutableStateFlow<String?>(null)
    val ddnsResult: StateFlow<String?> = _ddnsResult.asStateFlow()

    private val _ddnsBusy = MutableStateFlow(false)
    val ddnsBusy: StateFlow<Boolean> = _ddnsBusy.asStateFlow()

    /** 最近一次 DDNS 更新时间戳（毫秒）。 */
    val lastDdnsUpdate: StateFlow<Long> = MutableStateFlow(0L).also { out ->
        viewModelScope.launch {
            out.value = settingsStore.getLastDdnsUpdate()
            settingsStore.settings.collect {
                out.value = settingsStore.getLastDdnsUpdate()
            }
        }
    }

    val ddnsProviders = listOf(
        DdnsProviderOption("CLOUDFLARE", "Cloudflare", "记录填 zoneId/recordId，token 用 API Token"),
        DdnsProviderOption("DNSPOD", "DNSPod", "域名填主域名，记录填子域，token 填 id,token"),
        DdnsProviderOption("GENERIC_HTTP", "通用 HTTP", "更新地址含 {ip} 占位，token 可选"),
    )

    init {
        healthMonitor.refreshBattery()
        refreshBinaries()
    }

    fun refreshBattery() {
        healthMonitor.refreshBattery()
    }

    fun refreshBinaries() {
        viewModelScope.launch {
            val list = withContext(Dispatchers.IO) { binaryManager.verifyAll() }
            _binaries.value = list
        }
    }

    // ===== 主题 =====

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch { settingsStore.setThemeMode(mode.name) }
    }

    // ===== 保活 / 后台 =====

    fun setWakeLockEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.setWakeLockEnabled(value) }
    }

    fun setBootAutoStart(value: Boolean) {
        viewModelScope.launch { settingsStore.setBootAutoStart(value) }
    }

    fun setAutoRestart(value: Boolean) {
        viewModelScope.launch { settingsStore.setAutoRestartProcess(value) }
    }

    // ===== 安全 =====

    fun setRecordSecrets(value: Boolean) {
        viewModelScope.launch { settingsStore.setRecordSecretsInLog(value) }
    }

    // ===== 高阶功能 =====

    fun setAdvancedVisible(value: Boolean) {
        viewModelScope.launch { settingsStore.setAdvancedFeaturesVisible(value) }
    }

    // ===== 公共隧道端点 =====

    fun setPublicTunnelServer(server: String, port: Int) {
        viewModelScope.launch { settingsStore.setPublicTunnelServer(server, port) }
    }

    fun setPublicTunnelHost(host: String) {
        viewModelScope.launch { settingsStore.setPublicTunnelHost(host) }
    }

    fun setPublicTunnelConfigUrl(url: String) {
        viewModelScope.launch { settingsStore.setPublicTunnelConfigUrl(url) }
    }

    // ===== DDNS =====

    fun setDdnsEnabled(value: Boolean) {
        viewModelScope.launch { settingsStore.setDdnsEnabled(value) }
    }

    fun setDdnsProvider(provider: String) {
        viewModelScope.launch { settingsStore.setDdnsProvider(provider) }
    }

    fun setDdnsDomain(domain: String) {
        viewModelScope.launch { settingsStore.setDdnsDomain(domain) }
    }

    fun setDdnsRecord(record: String) {
        viewModelScope.launch { settingsStore.setDdnsRecord(record) }
    }

    fun testDdns() {
        viewModelScope.launch {
            _ddnsBusy.value = true
            val r = withContext(Dispatchers.IO) { ddnsUpdater.update() }
            _ddnsResult.value = r.detail
            if (r.success) {
                settingsStore.setLastDdnsUpdate(System.currentTimeMillis())
            }
            _ddnsBusy.value = false
        }
    }

    fun clearDdnsResult() {
        _ddnsResult.value = null
    }

    // ===== 证书 =====

    fun setCustomCertPath(path: String) {
        viewModelScope.launch { settingsStore.setCustomCertPath(path) }
    }

    fun clearCustomCert() {
        viewModelScope.launch { settingsStore.setCustomCertPath("") }
    }

    // ===== 告警引导（REQ 五 · 易用性：异常提示附带解决方案） =====

    fun batteryRequestIntent(context: Context): android.content.Intent =
        healthMonitor.batteryRequestIntent()

    fun batteryListIntent(context: Context): android.content.Intent =
        healthMonitor.batteryListIntent()

    fun notificationSettingsIntent(context: Context): android.content.Intent =
        healthMonitor.notificationSettingsIntent()

    fun dismissAlert() {
        val confirm = _alert.value?.onConfirm
        _alert.value = null
        confirm?.invoke()
    }

    fun notify(title: String, message: String) {
        _alert.value = AppleAlert(title, message)
    }
}
