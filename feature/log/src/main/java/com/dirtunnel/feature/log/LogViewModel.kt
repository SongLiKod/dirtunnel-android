package com.dirtunnel.feature.log

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.dirtunnel.core.storage.dao.LogDao
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.core.storage.entity.ConnectionLogEntity
import com.dirtunnel.core.storage.entity.TransferLogEntity
import com.dirtunnel.core.ui.components.AppleAlert
import dagger.hilt.android.lifecycle.HiltViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** 日志类型筛选（REQ 4.4 连接日志 / 上传下载日志）。 */
enum class LogTab(val label: String) {
    CONNECTIONS("连接日志"),
    TRANSFERS("流量日志"),
}

/** 单条日志的统一展示模型。 */
data class LogRow(
    val id: Long,
    val timestamp: Long,
    val serviceName: String,
    val protocol: String,
    val clientIp: String,
    val account: String,
    /** 连接事件名 或 传输方向 */
    val event: String,
    val detail: String,
    val isError: Boolean,
)

/** 时间范围筛选。 */
enum class LogRange(val label: String, val millis: Long) {
    ALL("全部", 0L),
    HOUR("近1小时", 60 * 60 * 1000L),
    DAY("近24小时", 24 * 60 * 60 * 1000L),
    WEEK("近7天", 7 * 24 * 60 * 60 * 1000L),
}

/**
 * 日志列表（REQ 4.4：连接日志、上传下载日志，支持导出；
 * REQ 五 · 安全：一键清空日志；TECH 六.5：日志可不记录密码）。
 */
@HiltViewModel
class LogViewModel @Inject constructor(
    private val logDao: LogDao,
    private val settingsStore: SettingsStore,
) : ViewModel() {

    private val _tab = MutableStateFlow(LogTab.CONNECTIONS)
    val tab: StateFlow<LogTab> = _tab.asStateFlow()

    private val _range = MutableStateFlow(LogRange.ALL)
    val range: StateFlow<LogRange> = _range.asStateFlow()

    private val _keyword = MutableStateFlow("")
    val keyword: StateFlow<String> = _keyword.asStateFlow()

    val connections: StateFlow<List<LogRow>> = logDao.observeConnections()
        .map { list -> list.map { it.toRow() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val transfers: StateFlow<List<LogRow>> = logDao.observeTransfers()
        .map { list -> list.map { it.toRow() } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    /** 当前 tab 过滤后的日志（时间范围 + 关键词）。 */
    val rows: StateFlow<List<LogRow>> = combine(
        _tab, _range, _keyword, connections, transfers,
    ) { tab, range, kw, conns, trans ->
        val base = if (tab == LogTab.CONNECTIONS) conns else trans
        val since = if (range.millis > 0) System.currentTimeMillis() - range.millis else 0L
        base.filter { row ->
            row.timestamp >= since &&
                (kw.isBlank() ||
                    row.serviceName.contains(kw, true) ||
                    row.clientIp.contains(kw, true) ||
                    row.protocol.contains(kw, true) ||
                    row.detail.contains(kw, true))
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, emptyList())

    val connectionCount: StateFlow<Int> = logDao.observeConnectionCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    val transferCount: StateFlow<Int> = logDao.observeTransferCount()
        .stateIn(viewModelScope, SharingStarted.Eagerly, 0)

    /** 日志脱敏开关状态（TECH 六.5：日志可以选择不记录密码）。 */
    val recordSecrets: StateFlow<Boolean> = settingsStore.settings
        .map { it.recordSecretsInLog }
        .stateIn(viewModelScope, SharingStarted.Eagerly, false)

    private val _alert = MutableStateFlow<AppleAlert?>(null)
    val alert: StateFlow<AppleAlert?> = _alert.asStateFlow()

    fun selectTab(tab: LogTab) {
        _tab.value = tab
    }

    fun selectRange(range: LogRange) {
        _range.value = range
    }

    fun setKeyword(kw: String) {
        _keyword.value = kw
    }

    /** 一键清空日志（REQ 五 · 安全）。 */
    fun clearAll() {
        viewModelScope.launch {
            logDao.clearAllConnections()
            logDao.clearAllTransfers()
        }
    }

    fun requestClear() {
        _alert.value = AppleAlert(
            title = "清空全部日志？",
            message = "连接日志与流量日志将被永久删除，无法恢复。",
            confirmText = "清空",
            destructive = true,
            onConfirm = { clearAll() },
        )
    }

    fun dismissAlert() {
        val confirm = _alert.value?.onConfirm
        _alert.value = null
        confirm?.invoke()
    }

    /**
     * 生成 CSV 内容（REQ V1.1 日志导出）。
     * 密码脱敏由写入端控制，导出沿用库内已脱敏数据。
     */
    fun csvContent(): String {
        val fmt = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        val rows = rows.value
        return buildString {
            if (_tab.value == LogTab.CONNECTIONS) {
                appendLine("时间,服务,协议,客户端IP,账号,事件,详情")
                rows.forEach { r ->
                    appendLine(
                        listOf(
                            fmt.format(Date(r.timestamp)),
                            r.serviceName, r.protocol, r.clientIp,
                            r.account, r.event, r.detail,
                        ).joinToString(",") { csvEscape(it) },
                    )
                }
            } else {
                appendLine("时间,服务,协议,客户端IP,账号,方向,字节数")
                rows.forEach { r ->
                    appendLine(
                        listOf(
                            fmt.format(Date(r.timestamp)),
                            r.serviceName, r.protocol, r.clientIp,
                            r.account, r.event, r.detail,
                        ).joinToString(",") { csvEscape(it) },
                    )
                }
            }
        }
    }

    /** 导出文件名。 */
    fun exportFileName(): String {
        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val kind = if (_tab.value == LogTab.CONNECTIONS) "connections" else "transfers"
        return "dirtunnel_${kind}_$stamp.csv"
    }

    fun showExportHint() {
        viewModelScope.launch {
            val masked = !settingsStore.settings.first().recordSecretsInLog
            _alert.value = AppleAlert(
                title = "导出日志",
                message = if (masked) {
                    "将导出当前筛选结果为 CSV 文件。账号列已脱敏（未开启记录密码）。\n方案：选择保存位置后即可分享或备份。"
                } else {
                    "将导出当前筛选结果为 CSV 文件。注意：已开启「记录密码」，账号列可能包含敏感信息。\n方案：导出后请妥善保管文件。"
                },
                confirmText = "选择位置",
                onConfirm = { _exportRequested.value = true },
            )
        }
    }

    /** 导出意图（Alert 确认后置位，UI 据此拉起 SAF 创建文件选择器）。 */
    private val _exportRequested = MutableStateFlow(false)
    val exportRequested: StateFlow<Boolean> = _exportRequested.asStateFlow()

    fun consumeExportRequest() {
        _exportRequested.value = false
    }

    private fun csvEscape(s: String): String {
        val v = s.replace("\"", "\"\"")
        return if (v.contains(',') || v.contains('"') || v.contains('\n')) "\"$v\"" else v
    }

    private fun ConnectionLogEntity.toRow() = LogRow(
        id = id,
        timestamp = timestamp,
        serviceName = serviceName,
        protocol = protocol,
        clientIp = clientIp,
        account = account,
        event = event,
        detail = message,
        isError = event == "ERROR" || event == "DENY",
    )

    private fun TransferLogEntity.toRow() = LogRow(
        id = id,
        timestamp = timestamp,
        serviceName = serviceName,
        protocol = protocol,
        clientIp = clientIp,
        account = account,
        event = if (direction == "UPLOAD") "上传" else "下载",
        detail = bytes.toString(),
        isError = false,
    )
}
