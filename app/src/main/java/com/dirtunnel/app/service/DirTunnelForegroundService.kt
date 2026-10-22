package com.dirtunnel.app.service

import android.app.Notification
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import com.dirtunnel.app.DirTunnelApp
import com.dirtunnel.app.MainActivity
import com.dirtunnel.core.engine.model.ProcessState
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.feature.service.runtime.ServiceRuntimeController
import com.dirtunnel.feature.tunnel.runtime.TunnelRuntimeController
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * 前台服务保活（TECH 三 · 前台服务是安卓保活唯一可靠路径；REQ 4.4 后台稳定运行 ≥72h）。
 *
 * 职责：
 * 1. 常驻前台通知，展示运行中的服务/隧道数量（不含账号密码，TECH 六.4）；
 * 2. 可选 WakeLock（用户开关，防止息屏断流）；
 * 3. 启动时自动拉起「自动启动」的服务与隧道（REQ 4.2 / 4.3）。
 */
@AndroidEntryPoint
class DirTunnelForegroundService : Service() {

    @Inject lateinit var serviceRuntime: ServiceRuntimeController
    @Inject lateinit var tunnelRuntime: TunnelRuntimeController
    @Inject lateinit var settingsStore: SettingsStore

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startAsForeground()
        observeAndNotify()
        acquireWakeLockIfNeeded()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START_AUTO -> scope.launch { startAutoServices() }
            ACTION_STOP_ALL -> scope.launch { stopEverything() }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        releaseWakeLock()
        super.onDestroy()
    }

    // ==================== 前台通知 ====================

    private fun startAsForeground() {
        val notification = buildNotification("DirTunnel 正在运行", "准备中…")
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(
                    NOTIFICATION_ID,
                    notification,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
                )
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
        } catch (t: Throwable) {
            // 个别 ROM 拒绝 dataSync 类型时降级为普通前台服务
            try {
                startForeground(NOTIFICATION_ID, notification)
            } catch (t2: Throwable) {
                stopSelf()
            }
        }
    }

    private fun buildNotification(title: String, text: String): Notification {
        val contentIntent = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, DirTunnelApp.CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentTitle(title)
            .setContentText(text)
            .setOngoing(true)
            .setContentIntent(contentIntent)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setShowWhen(false)
            .build()
    }

    /** 订阅运行状态，通知栏实时更新（TECH 六.4 前台通知不泄露账号密码）。 */
    private fun observeAndNotify() {
        scope.launch {
            while (isActive) {
                val svcRunning = serviceRuntime.statuses.value.values.count {
                    it.state is ProcessState.Running
                }
                val tunnelConnected = tunnelRuntime.runtimes.value.values.count { it.connected }
                val text = buildString {
                    append("运行中服务 $svcRunning")
                    if (tunnelConnected > 0) append(" · 隧道 $tunnelConnected")
                    if (svcRunning == 0 && tunnelConnected == 0) append("（无活动任务）")
                }
                val nm = getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
                nm.notify(NOTIFICATION_ID, buildNotification("DirTunnel 正在运行", text))
                delay(5_000)
            }
        }
    }

    // ==================== 自动启动 ====================

    private suspend fun startAutoServices() {
        serviceRuntime.startAutoStartServices()
        // 隧道：服务已开穿透的自动拉起
        tunnelRuntime.startAllEnabled()
    }

    private suspend fun stopEverything() {
        serviceRuntime.stopAll()
        tunnelRuntime.stopAll()
    }

    // ==================== WakeLock ====================

    private fun acquireWakeLockIfNeeded() {
        scope.launch {
            val enabled = settingsStore.settings.first().wakeLockEnabled
            if (!enabled) return@launch
            try {
                val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "DirTunnel:foreground").apply {
                    setReferenceCounted(false)
                    acquire(24 * 60 * 60 * 1000L)
                }
            } catch (t: Throwable) {
                // 获取失败不阻塞服务
            }
        }
    }

    private fun releaseWakeLock() {
        try {
            if (wakeLock?.isHeld == true) wakeLock?.release()
        } catch (t: Throwable) {
        }
        wakeLock = null
    }

    companion object {
        private const val NOTIFICATION_ID = 1001
        const val ACTION_START_AUTO = "com.dirtunnel.action.START_AUTO"
        const val ACTION_STOP_ALL = "com.dirtunnel.action.STOP_ALL"

        /** 启动前台服务（幂等）。 */
        fun start(context: Context) {
            val intent = Intent(context, DirTunnelForegroundService::class.java)
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                // Android 12+ 后台启动限制：由 BootReceiver 或前台 Activity 触发
                try {
                    context.startService(intent)
                } catch (t2: Throwable) {
                }
            }
        }

        /** 携带 action 启动（自动拉起服务 / 一键停止）。 */
        fun startWithAction(context: Context, action: String) {
            val intent = Intent(context, DirTunnelForegroundService::class.java).setAction(action)
            try {
                context.startForegroundService(intent)
            } catch (t: Throwable) {
                try {
                    context.startService(intent)
                } catch (t2: Throwable) {
                }
            }
        }
    }
}
