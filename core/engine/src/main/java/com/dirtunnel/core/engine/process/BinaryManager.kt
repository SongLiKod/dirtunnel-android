package com.dirtunnel.core.engine.process

import android.content.Context
import android.os.Build
import android.util.Log
import com.dirtunnel.core.engine.model.BinaryStatus
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest
import javax.inject.Inject
import javax.inject.Singleton
import org.json.JSONArray

/**
 * 跨平台静态编译二进制托管（TECH 二）：
 * - ABI 选择：arm64-v8a（主力）/ armeabi-v7a（兼容）；
 * - 从 assets/bin/<abi>/<file> 释放到 filesDir/bin/<abi>/，chmod 755；
 * - 释放后做 md5 校验，与打包清单比对，防篡改（TECH 六.6）。
 */
@Singleton
class BinaryManager @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    data class ManifestEntry(
        val name: String,
        val file: String,
        val version: String,
        val md5: String,
        val abis: List<String>,
        /** true = 随 APK 的 jniLibs 内置（lib<file>.so，进程内引擎），无需从 assets 释放 */
        val native: Boolean = false,
    )

    val manifest: List<ManifestEntry> by lazy { loadManifest() }

    /** 优先 arm64-v8a。 */
    fun preferredAbi(): String {
        val supported = Build.SUPPORTED_ABIS.toList()
        return when {
            supported.contains("arm64-v8a") && Build.SUPPORTED_64_BIT_ABIS.isNotEmpty() -> "arm64-v8a"
            supported.contains("armeabi-v7a") -> "armeabi-v7a"
            else -> supported.firstOrNull() ?: "arm64-v8a"
        }
    }

    private fun binRoot(): File = File(context.filesDir, "bin").apply { mkdirs() }

    fun executableFile(binaryName: String): File? {
        val entry = manifest.firstOrNull { it.name == binaryName } ?: return null
        if (entry.native) {
            val f = nativeLibFile(entry)
            return if (f != null && f.exists()) f else null
        }
        val abi = preferredAbi()
        val f = File(File(binRoot(), abi), entry.file)
        return if (f.exists()) f else null
    }

    /** jniLibs 安装路径（manifest extractNativeLibs=true 时由 PMS 释放到 nativeLibraryDir）。 */
    private fun nativeLibFile(entry: ManifestEntry): File? = try {
        File(context.applicationInfo.nativeLibraryDir, "lib${entry.file}.so")
    } catch (_: Throwable) {
        null
    }

    /** 释放二进制到本地（返回 null=成功，否则为带解决方案的错误文案）。 */
    fun ensureExtracted(binaryName: String): String? {
        val entry = manifest.firstOrNull { it.name == binaryName }
            ?: return "协议组件 $binaryName 不在打包清单内。方案：升级到官方发行版安装包。"
        if (entry.native) return ensureNativePresent(entry)
        if (entry.md5.isBlank()) {
            return "协议组件 $binaryName 未随当前安装包内置（V1.1/V1.2 正式发行版提供）。方案：安装对应版本完整包，或先使用已内置协议。"
        }
        val abi = preferredAbi()
        if (!entry.abis.contains(abi)) {
            return "协议组件 $binaryName 不支持当前设备架构 $abi。方案：在设置中下载/切换对应架构的分包。"
        }
        val target = File(File(binRoot(), abi), entry.file)
        if (target.exists() && target.length() > 0) {
            val status = verify(entry, target)
            if (status.md5Match) {
                target.setExecutable(true, false)
                return null
            }
            // 校验失败：删除被篡改的文件并重新释放
            target.delete()
        }
        val assetPath = "bin/$abi/${entry.file}"
        return try {
            val bytes = context.assets.open(assetPath).use { it.readBytes() }
            target.parentFile?.mkdirs()
            target.writeBytes(bytes)
            target.setReadable(true, false)
            target.setExecutable(true, false)
            val status = verify(entry, target)
            if (!status.md5Match) {
                target.delete()
                "协议组件 $binaryName md5 校验失败，文件疑似被篡改。方案：重新安装官方安装包后重试。"
            } else null
        } catch (t: Throwable) {
            target.delete()
            "协议组件 $binaryName 缺失（assets/$assetPath）。方案：在设置 → 协议组件中选择“恢复默认组件”，或安装完整版安装包。"
        }
    }

    /** 进程内引擎（jniLibs）：随安装包由 PMS 释放，无需 assets 拷贝；做存在性 + md5 校验（TECH 六.6）。 */
    private fun ensureNativePresent(entry: ManifestEntry): String? {
        val f = nativeLibFile(entry)
        if (f == null || !f.exists() || f.length() <= 0) {
            return "协议组件 ${entry.name}（lib${entry.file}.so）未随安装包内置。方案：确认设备为 arm64-v8a 后重新安装官方安装包；安装后即开即用，无需配置。"
        }
        if (entry.md5.isNotBlank() && !entry.md5.equals(f.md5(), ignoreCase = true)) {
            return "协议组件 ${entry.name} 与清单 md5 不匹配（期望 ${entry.md5}）。方案：重新安装官方安装包（系统对应用文件的完整性异常）。"
        }
        return null
    }

    /** md5 校验（TECH 六.6 二进制文件校验 md5，防止被篡改）。 */
    fun verify(binaryName: String): BinaryStatus {
        val entry = manifest.firstOrNull { it.name == binaryName }
            ?: return BinaryStatus(
                name = binaryName, fileName = "-", present = false, md5Match = false,
                expectedMd5 = "", actualMd5 = "", version = "-", path = null,
                solution = "协议组件 $binaryName 不在清单中。方案：升级应用到最新版本。",
            )
        val file = executableFile(binaryName)
        return verify(entry, file)
    }

    private fun verify(entry: ManifestEntry, file: File?): BinaryStatus {
        if (file == null || !file.exists()) {
            return BinaryStatus(
                name = entry.name, fileName = entry.file, present = false, md5Match = false,
                expectedMd5 = entry.md5, actualMd5 = "", version = entry.version, path = null,
                solution = when {
                    entry.md5.isBlank() ->
                        "该组件未随当前安装包内置（V1.1/V1.2 正式发行版提供）。方案：忽略此提示，或安装对应版本的完整安装包。"
                    entry.native ->
                        "内置引擎 lib${entry.file}.so 未找到（可能设备非 arm64-v8a）。方案：重新安装对应架构的官方安装包；无需任何配置。"
                    else ->
                        "协议组件 ${entry.name} 未释放。方案：点击下方「重新校验」自动释放，或重新安装官方安装包。"
                },
            )
        }
        val actual = file.md5()
        val match = if (entry.md5.isBlank()) false else entry.md5.equals(actual, ignoreCase = true)
        return BinaryStatus(
            name = entry.name,
            fileName = entry.file,
            present = true,
            md5Match = match,
            expectedMd5 = entry.md5,
            actualMd5 = actual,
            version = entry.version,
            path = file.absolutePath,
            solution = when {
                match -> ""
                entry.md5.isBlank() -> "清单缺少 ${entry.name} 的 md5 基准值（开发构建）。发布前请补齐 binaries.json。"
                else -> "协议组件 ${entry.name} md5 不匹配（期望 ${entry.md5}，实际 $actual），可能被篡改。方案：重新安装官方安装包。"
            },
        )
    }

    /** 全量检查（设置页展示）：未释放的组件先从 assets 自动释放（幂等），再比对 md5。 */
    fun verifyAll(): List<BinaryStatus> = manifest.map {
        // 幂等释放：已存在且 md5 匹配则直接返回；否则从 assets 拷出后校验
        ensureExtracted(it.name)
        verify(it.name)
    }

    /** 按协议给出启动前检查结果。 */
    fun ensureForProtocol(protocolBinary: String): String? = ensureExtracted(protocolBinary)

    private fun loadManifest(): List<ManifestEntry> = try {
        val text = context.assets.open("binaries.json").bufferedReader().use { it.readText() }
        val arr = JSONArray(text)
        buildList {
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val abis = o.optJSONArray("abis")
                add(
                    ManifestEntry(
                        name = o.getString("name"),
                        file = o.getString("file"),
                        version = o.optString("version", "1.0.0"),
                        md5 = o.optString("md5", ""),
                        native = o.optBoolean("native", false),
                        abis = buildList {
                            if (abis != null) for (j in 0 until abis.length()) add(abis.getString(j))
                            else addAll(listOf("arm64-v8a", "armeabi-v7a"))
                        },
                    ),
                )
            }
        }
    } catch (t: Throwable) {
        Log.w(TAG, "读取 binaries.json 失败", t)
        emptyList()
    }

    private fun File.md5(): String = try {
        val digest = MessageDigest.getInstance("MD5")
        FileInputStream(this).use { input ->
            val buf = ByteArray(8192)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        digest.digest().joinToString("") { "%02x".format(it) }
    } catch (t: Throwable) {
        ""
    }

    companion object {
        private const val TAG = "BinaryManager"
    }
}
