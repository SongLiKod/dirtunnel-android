package com.dirtunnel.app.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.dirtunnel.app.service.DirTunnelForegroundService
import dagger.hilt.android.AndroidEntryPoint

/**
 * 开机自启（REQ 五 · 后台稳定运行 / TECH 三 · 前台服务保活）。
 * BOOT_COMPLETED / MY_PACKAGE_REPLACED 后拉起前台服务，
 * 前台服务内部会自动启动标记为「自动启动」的服务与隧道。
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == Intent.ACTION_BOOT_COMPLETED ||
            action == Intent.ACTION_MY_PACKAGE_REPLACED
        ) {
            DirTunnelForegroundService.startWithAction(
                context,
                DirTunnelForegroundService.ACTION_START_AUTO,
            )
        }
    }
}
