package com.dirtunnel.app

import android.content.Context
import android.os.Build
import android.util.Log
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 崩溃自报告（调试辅助）：
 * - 进程最早的 attachBaseContext 阶段安装 [Thread.setDefaultUncaughtExceptionHandler]；
 * - 闪退瞬间把设备信息 + 完整异常堆栈写入应用私有文件 `last_crash.txt`，并同步输出到 logcat；
 * - 下次启动时 MainActivity 在进入 Compose/Hilt 主流程之前读取该文件，
 *   用系统原生 AlertDialog（不依赖 Compose/Hilt）展示堆栈，避免"崩溃→无法取证"死循环。
 */
object CrashReporter {

    private const val TAG = "DirTunnelCrash"
    private const val FILE_NAME = "last_crash.txt"
    private const val MAX_REPORT_CHARS = 60_000

    @Volatile
    private var context: Context? = null

    @Volatile
    private var installed = false

    /** 必须在 Application.attachBaseContext 最早期调用。 */
    fun install(base: Context) {
        if (installed) return
        installed = true
        context = base
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            writeReport(thread, throwable)
            previous?.uncaughtException(thread, throwable)
        }
    }

    private fun writeReport(thread: Thread, throwable: Throwable) {
        try {
            val ctx = context ?: return
            val text = buildReport(ctx, thread, throwable)
            File(ctx.filesDir, FILE_NAME).writeText(text)
            // 任何本机 logcat 查看器也能在崩溃现场直接看到
            Log.e(TAG, "崩溃已记录:\n$text")
        } catch (t: Throwable) {
            Log.e(TAG, "写崩溃报告失败", t)
        }
    }

    private fun buildReport(ctx: Context, thread: Thread, throwable: Throwable): String {
        val sw = StringWriter()
        throwable.printStackTrace(PrintWriter(sw))
        var stack = sw.toString()
        if (stack.length > MAX_REPORT_CHARS) {
            stack = stack.substring(0, MAX_REPORT_CHARS) + "\n...（堆栈过长已截断）"
        }
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
        val version = try {
            val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
            "${pi.versionName} (${pi.longVersionCode})"
        } catch (_: Throwable) {
            "unknown"
        }
        return buildString {
            appendLine("==== DirTunnel 崩溃报告 ====")
            appendLine("时间: $time")
            appendLine("设备: ${Build.MANUFACTURER} ${Build.MODEL} (${Build.DEVICE})")
            appendLine("系统: Android ${Build.DISPLAY} / SDK ${Build.VERSION.SDK_INT}")
            appendLine("应用: $version")
            appendLine("线程: ${thread.name}")
            appendLine("---- 异常堆栈 ----")
            append(stack)
        }
    }

    /** MainActivity 早期读取；无报告返回 null。 */
    fun readReport(ctx: Context): String? = try {
        val f = File(ctx.filesDir, FILE_NAME)
        if (f.exists() && f.length() > 0L) f.readText() else null
    } catch (_: Throwable) {
        null
    }

    /** 用户确认后删除，避免重复弹窗。 */
    fun clear(ctx: Context) {
        try {
            File(ctx.filesDir, FILE_NAME).delete()
        } catch (_: Throwable) {
        }
    }
}
