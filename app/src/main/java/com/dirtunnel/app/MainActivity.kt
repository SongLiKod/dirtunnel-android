package com.dirtunnel.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.lifecycle.lifecycleScope
import com.dirtunnel.app.navigation.DirTunnelNavGraph
import com.dirtunnel.app.service.DirTunnelForegroundService
import com.dirtunnel.core.storage.data.SettingsStore
import com.dirtunnel.core.ui.theme.AppleTheme
import com.dirtunnel.core.ui.theme.ThemeMode
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

/**
 * 主入口：Compose 全屏 + AppleTheme 主题接线（TECH 四 · 全局封装 AppleTheme）。
 * 主题模式读取 DataStore，SYSTEM/LIGHT/DARK 三态（REQ 4.5）。
 */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsStore: SettingsStore

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // 崩溃自报告：上次运行闪退时，先用系统原生弹窗展示堆栈（不进入 Compose 主流程），
        // 用户确认后删除报告并正常进入。取证用，见 CrashReporter。
        val crashReport = CrashReporter.readReport(this)
        if (crashReport != null && showCrashReportDialog(crashReport)) {
            return
        }

        enableEdgeToEdge()
        WindowCompat.setDecorFitsSystemWindows(window, false)

        requestNotificationPermissionIfNeeded()

        val themeFlow = settingsStore.themeMode.map { ThemeMode.fromName(it) }

        setContent {
            val mode by themeFlow.collectAsState(initial = ThemeMode.SYSTEM)
            AppleTheme(mode = mode) {
                DirTunnelNavGraph()
            }
        }

        // 前台服务保活：APP 启动即拉起（REQ 五 · 后台稳定运行）
        lifecycleScope.launch {
            DirTunnelForegroundService.start(this@MainActivity)
        }
    }

    /**
     * 展示上次崩溃堆栈。返回 true = 已展示（本次启动不进入主流程）。
     * 全程 runCatching：任何异常都降级为继续正常启动，不引入新的启动崩溃。
     */
    private fun showCrashReportDialog(report: String): Boolean = try {
        android.app.AlertDialog.Builder(this)
            .setTitle("检测到上次运行崩溃（详情如下）")
            .setMessage(report)
            .setCancelable(false)
            .setPositiveButton("删除报告并进入应用") { _, _ ->
                CrashReporter.clear(this)
                recreate()
            }
            .setNegativeButton("退出") { _, _ -> finish() }
            .show()
        true
    } catch (t: Throwable) {
        Log.e("DirTunnelCrash", "展示崩溃报告失败，降级为正常启动", t)
        false
    }

    /** Android 13+ 前台服务通知需要运行时授权（TECH 三 · POST_NOTIFICATIONS）。 */
    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= 33) {
            val granted = ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                registerForActivityResult(
                    ActivityResultContracts.RequestPermission(),
                ) { _ -> }.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }
}
