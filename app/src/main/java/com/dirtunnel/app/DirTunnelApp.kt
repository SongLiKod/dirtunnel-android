package com.dirtunnel.app

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import dagger.hilt.android.HiltAndroidApp

/**
 * DirTunnel Application。
 * - Hilt 依赖图根；
 * - 前台服务通知渠道（TECH 三 · 前台服务是安卓保活唯一可靠路径）。
 */
@HiltAndroidApp
class DirTunnelApp : Application() {

    /** 最早拿到 Context 的时机，安装崩溃自报告（早于 Hilt 注入与全部 onCreate）。 */
    override fun attachBaseContext(base: Context) {
        CrashReporter.install(base)
        super.attachBaseContext(base)
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java) ?: return
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_SERVICE,
                    "服务运行通知",
                    NotificationManager.IMPORTANCE_LOW,
                ).apply {
                    description = "前台服务常驻通知，展示运行中的服务与隧道状态（不含账号密码）"
                    setShowBadge(false)
                },
            )
            nm.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ALERT,
                    "告警通知",
                    NotificationManager.IMPORTANCE_DEFAULT,
                ).apply {
                    description = "服务崩溃、端口占用、隧道断连等告警"
                },
            )
        }
    }

    companion object {
        const val CHANNEL_SERVICE = "dirtunnel_service"
        const val CHANNEL_ALERT = "dirtunnel_alert"
    }
}
