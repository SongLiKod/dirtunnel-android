package com.dirtunnel.core.engine.health

import android.Manifest
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import android.os.Build
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** 告警可执行的引导动作（REQ 五 · 易用性：异常提示附带解决方案）。 */
enum class WarningAction {
    OPEN_BATTERY_REQUEST,
    OPEN_BATTERY_LIST,
    OPEN_NOTIFICATION_SETTINGS,
    OPEN_APP_DETAILS,
    OPEN_APP_PERMISSIONS,
}

/** 首页仪表盘告警项（REQ 4.6 监控：端口占用告警、权限不足告警 + 风险规避）。 */
data class HealthWarning(
    val id: String,
    val title: String,
    val message: String,
    /** 解决方案（必带，REQ 五 · 易用性） */
    val solution: String,
    val severity: Severity = Severity.WARNING,
    val action: WarningAction? = null,
) {
    enum class Severity { INFO, WARNING, ERROR }
}

/**
 * 系统健康巡检（TECH 七 · 已知风险与规避方案）：
 * 1. 电池优化/应用休眠 → 检测 + 强制前台服务 + 引导关闭电池限制；
 * 2. SELinux 拦截子进程 → 检测并提示；
 * 3. 通知权限（Android13+）→ 前台服务保活前置条件。
 */
@Singleton
class SystemHealthMonitor @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager

    /** true = 系统正在限制本应用（息屏断连风险） */
    private val _batteryRestricted = kotlinx.coroutines.flow.MutableStateFlow<Boolean?>(null)
    val batteryRestricted: kotlinx.coroutines.flow.StateFlow<Boolean?> = _batteryRestricted.asStateFlow()

    fun refreshBattery() {
        _batteryRestricted.value = !isIgnoringBatteryOptimizations()
    }

    fun isIgnoringBatteryOptimizations(): Boolean = try {
        powerManager.isIgnoringBatteryOptimizations(context.packageName)
    } catch (t: Throwable) {
        true // 检测失败不打扰用户
    }

    /** 通知是否可用（前台服务通知展示的前提，Android13+ 需运行时授权）。 */
    fun notificationsEnabled(): Boolean = try {
        val permOk = if (Build.VERSION.SDK_INT >= 33) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        } else {
            true
        }
        permOk && NotificationManagerCompat.from(context).areNotificationsEnabled()
    } catch (t: Throwable) {
        true
    }

    /** SELinux 是否处于 enforce（TECH 七.2 不同厂商拦截子进程）。 */
    fun selinuxEnforcing(): Boolean = try {
        val f = listOf(File("/sys/fs/selinux/enforce"), File("/selinux/enforce"))
            .firstOrNull { it.exists() }
        f?.readText()?.trim() == "1"
    } catch (t: Throwable) {
        false
    }

    // ===== 组装告警 =====

    fun batteryWarning(): HealthWarning? {
        if (_batteryRestricted.value != true) return null
        return HealthWarning(
            id = "battery",
            title = "电池优化正在限制 DirTunnel",
            message = "息屏后系统可能冻结前台服务，导致隧道断连、后台 72h 稳定运行目标无法达成。",
            solution = "点击下方按钮，将 DirTunnel 加入“不限制电池用量”白名单；同时在厂商省电设置中允许后台运行。",
            action = WarningAction.OPEN_BATTERY_REQUEST,
        )
    }

    fun notificationWarning(): HealthWarning? {
        if (notificationsEnabled()) return null
        return HealthWarning(
            id = "notification",
            title = "通知权限未开启",
            message = "前台服务通知无法展示，安卓可能随时回收服务进程。",
            solution = "前往系统设置 → 应用 → DirTunnel → 通知，开启通知权限。",
            action = WarningAction.OPEN_NOTIFICATION_SETTINGS,
        )
    }

    fun selinuxWarning(): HealthWarning? {
        if (!selinuxEnforcing()) return null
        return HealthWarning(
            id = "selinux",
            title = "SELinux 处于强制模式",
            message = "部分厂商 ROM 会拦截子进程的网络/文件访问，协议服务可能启动失败。",
            solution = "若服务启动失败，请查看运行日志中的 avc 拒绝记录；可尝试更换端口或改用系统自带存储目录重试。",
            severity = HealthWarning.Severity.INFO,
        )
    }

    fun collectWarnings(extra: List<HealthWarning> = emptyList()): List<HealthWarning> = buildList {
        extra.forEach { add(it) }
        batteryWarning()?.let { add(it) }
        notificationWarning()?.let { add(it) }
        selinuxWarning()?.let { add(it) }
    }

    // ===== Intent 引导 =====

    fun batteryRequestIntent(): Intent = try {
        Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${context.packageName}"),
        )
    } catch (t: Throwable) {
        batteryListIntent()
    }

    fun batteryListIntent(): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)

    fun notificationSettingsIntent(): Intent {
        return if (Build.VERSION.SDK_INT >= 26) {
            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
        } else {
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                .setData(Uri.parse("package:${context.packageName}"))
        }
    }

    fun appDetailsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))

    fun appPermissionsIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${context.packageName}"))
}
