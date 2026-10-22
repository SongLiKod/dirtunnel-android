package com.dirtunnel.core.network

import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import androidx.core.content.FileProvider
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * 软件内更新：检查 GitHub Releases → **应用内静默下载 APK** → 调起系统安装确认。
 *
 * - 全程不离开应用、不打开浏览器；唯一外部界面是 Android 系统安装确认对话框；
 * - 下载挂在进程级单例作用域（[Singleton]）：离开设置页甚至切换 Tab 都不中断；
 * - 调试签名安装优先下载 `-debug.apk` 资产、正式签名安装优先正式 APK——
 *   Android 升级要求签名一致，选错资产会在安装时被系统拒绝。
 */
@Singleton
class AppUpdater @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /** 更新状态机（设置页直接渲染）。 */
    sealed interface State {
        data object Idle : State
        data object Checking : State

        /** 已是最新（或无更新）。 */
        data class UpToDate(val version: String) : State

        /** 发现新版本，等待用户点击下载。 */
        data class Available(
            val version: String,
            val notes: String,
            val sizeBytes: Long,
            val downloadUrl: String,
        ) : State

        /** 应用内静默下载中（点击可取消）。 */
        data class Downloading(
            val version: String,
            val progress: Float,
            val downloadedBytes: Long,
            val totalBytes: Long,
        ) : State

        /** 下载完成，已调起/等待调起系统安装。 */
        data class Ready(val version: String, val file: File) : State

        data class Failed(val message: String, val solution: String) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var downloadJob: Job? = null

    /** 最近一次发现的新版本（下载失败后重试用）。 */
    @Volatile
    private var pending: State.Available? = null

    /** 当前已安装版本（来自 PackageManager，非硬编码）。 */
    val currentVersion: String
        get() = try {
            context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "0.0.0"
        } catch (_: Throwable) {
            "0.0.0"
        }

    /** 本安装是否调试签名（决定挑哪个发布资产，见类注释）。 */
    private val isDebugBuild: Boolean
        get() = try {
            (context.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE) != 0
        } catch (_: Throwable) {
            false
        }

    // ==================== 检查 ====================

    fun check() {
        if (_state.value is State.Checking) return
        scope.launch {
            _state.value = State.Checking
            val info = try {
                fetchLatest()
            } catch (t: Throwable) {
                _state.value = State.Failed(
                    "检查更新失败：${t.message ?: "网络错误"}",
                    "检查网络连接后重试；GitHub 接口未认证限额 60 次/小时。",
                )
                return@launch
            }
            if (isNewer(info.tag, currentVersion)) {
                pending = State.Available(info.tag, info.notes, info.assetSize, info.assetUrl)
                _state.value = pending!!
            } else {
                _state.value = State.UpToDate(currentVersion)
            }
        }
    }

    /** 失败后点击重试：有挂起的发现版本则续传下载，否则重新检查。 */
    fun retry() {
        if (pending != null) download() else check()
    }

    // ==================== 下载 ====================

    /** 发现新版本后调用：应用内静默下载（离开页面不中断）。 */
    fun download() {
        val avail = pending ?: (_state.value as? State.Available) ?: return
        if (_state.value is State.Downloading) return
        downloadJob = scope.launch { doDownload(avail) }
    }

    /** 下载中点击 = 取消并清理半成品。 */
    fun cancelDownload() {
        downloadJob?.cancel()
        _state.value = pending ?: State.Idle
    }

    private suspend fun doDownload(avail: State.Available) {
        val dir = File(context.filesDir, "update").apply { mkdirs() }
        val out = File(dir, "dirtunnel-${avail.version}.apk")
        try {
            val conn = URL(avail.downloadUrl).openConnection() as HttpURLConnection
            try {
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000
                conn.setRequestProperty("User-Agent", "DirTunnel-Updater")
                val code = conn.responseCode
                if (code !in 200..299) error("服务器返回 HTTP $code")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: avail.sizeBytes
                var downloaded = 0L
                var lastPercent = -1
                conn.inputStream.use { input ->
                    FileOutputStream(out).use { fileOut ->
                        val buf = ByteArray(128 * 1024)
                        while (currentCoroutineContext().isActive) {
                            val n = input.read(buf)
                            if (n < 0) break
                            fileOut.write(buf, 0, n)
                            downloaded += n
                            val percent =
                                if (total > 0) (downloaded * 100 / total).toInt() else -1
                            if (percent != lastPercent) {
                                lastPercent = percent
                                _state.value = State.Downloading(
                                    version = avail.version,
                                    progress = if (total > 0) downloaded.toFloat() / total else -1f,
                                    downloadedBytes = downloaded,
                                    totalBytes = total,
                                )
                            }
                        }
                    }
                }
                _state.value = State.Ready(avail.version, out)
                install(out)
            } finally {
                conn.disconnect()
            }
        } catch (_: kotlinx.coroutines.CancellationException) {
            out.delete()
            _state.value = pending ?: State.Idle
        } catch (t: Throwable) {
            out.delete()
            _state.value = State.Failed(
                "下载失败：${t.message ?: "网络错误"}",
                "检查存储空间与网络后点击重试。",
            )
        }
    }

    // ==================== 安装 ====================

    /** 调起系统安装器（唯一系统界面：安装确认对话框）。 */
    fun install(file: File? = null) {
        val apk = file ?: (_state.value as? State.Ready)?.file ?: return
        try {
            val uri = FileProvider.getUriForFile(
                context,
                "${context.packageName}.fileprovider",
                apk,
            )
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (t: Throwable) {
            _state.value = State.Failed(
                "无法启动安装：${t.message ?: "未知错误"}",
                "请在系统设置中允许本应用安装未知应用后重试。",
            )
        }
    }

    // ==================== 工具 ====================

    private data class ReleaseInfo(
        val tag: String,
        val notes: String,
        val assetUrl: String,
        val assetSize: Long,
    )

    /** GitHub Releases latest（无需认证；必须带 User-Agent）。 */
    private fun fetchLatest(): ReleaseInfo {
        val conn = URL(API_URL).openConnection() as HttpURLConnection
        try {
            conn.connectTimeout = 15_000
            conn.readTimeout = 15_000
            conn.setRequestProperty("Accept", "application/vnd.github+json")
            conn.setRequestProperty("User-Agent", "DirTunnel-Updater")
            val code = conn.responseCode
            if (code == 404) error("暂无发布版本")
            if (code !in 200..299) error("服务器返回 HTTP $code")
            val json = JSONObject(conn.inputStream.bufferedReader().readText())
            val tag = json.optString("tag_name").removePrefix("v").trim()
            if (tag.isBlank()) error("版本信息异常")
            val notes = json.optString("body", "").trim()
            val assets = json.optJSONArray("assets")
                ?: error("发布物缺失：该版本没有附件")
            // 挑资产：调试安装 → *-debug.apk；正式安装 → 正式签名 APK（跳过 -unsigned）
            var chosen: JSONObject? = null
            for (i in 0 until assets.length()) {
                val a = assets.optJSONObject(i) ?: continue
                val name = a.optString("name")
                if (!name.endsWith(".apk") || name.contains("-unsigned")) continue
                val isDebugAsset = name.endsWith("-debug.apk")
                if (isDebugBuild == isDebugAsset) {
                    chosen = a
                    break
                }
            }
            // 签名类别不匹配的资产装不上同名应用：仅当没有同类资产时退而取之
            if (chosen == null) {
                for (i in 0 until assets.length()) {
                    val a = assets.optJSONObject(i) ?: continue
                    val name = a.optString("name")
                    if (name.endsWith(".apk") && !name.contains("-unsigned")) {
                        chosen = a
                        break
                    }
                }
            }
            val asset = chosen ?: error("发布物中没有可安装的 APK")
            return ReleaseInfo(
                tag = tag,
                notes = notes,
                assetUrl = asset.optString("browser_download_url"),
                assetSize = asset.optLong("size", 0L),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** 语义化版本比较：1.0.1 > 1.0.0。 */
    private fun isNewer(latest: String, current: String): Boolean {
        val l = latest.split('.', '-').map { it.toIntOrNull() ?: 0 }
        val c = current.split('.', '-').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(l.size, c.size)) {
            val lv = l.getOrElse(i) { 0 }
            val cv = c.getOrElse(i) { 0 }
            if (lv != cv) return lv > cv
        }
        return false
    }

    companion object {
        private const val API_URL =
            "https://api.github.com/repos/SongLiKod/dirtunnel-android/releases/latest"
    }
}
