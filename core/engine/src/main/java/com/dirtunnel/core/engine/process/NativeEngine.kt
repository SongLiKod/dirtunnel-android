package com.dirtunnel.core.engine.process

import android.util.Log

/**
 * 内置协议引擎（进程内托管）。
 *
 * 现代 Android（API 29+，SELinux W^X：untrusted_app 无 app_data_file 的
 * execute 权）禁止应用 exec 自身数据目录中的文件，ProcessBuilder 子进程形态
 * 对 V1.0 协议必然失败（"Cannot run program ... error=13"）。因此 dirtunneld
 * 以 Go c-shared 形式打进 jniLibs（libdirtunneld.so），由 JNI 加载在 App
 * 进程内运行（gomobile/Tailscale 同款成熟架构）。
 *
 * 架构不变点：引擎仍只监听 127.0.0.1 内部端口，对外统一经 TcpProxyServer
 * （IP 白名单 / 连接数 / 限速 / 流量记账）暴露；密码仍只出现在 0600 配置
 * 文件中，经路径传入（TECH 六.2）；md5 校验仍对 .so 生效（TECH 六.6）。
 */
object NativeEngine {

    @Volatile
    private var loaded = false

    @Volatile
    private var loadError: String? = null

    private fun ensureLoaded(): Boolean {
        if (loaded) return true
        synchronized(this) {
            if (loaded) return true
            try {
                System.loadLibrary("dirtunneld")
                loaded = true
            } catch (t: Throwable) {
                loadError = t.message ?: t.javaClass.simpleName
                Log.w(TAG, "加载 libdirtunneld.so 失败", t)
            }
            return loaded
        }
    }

    private external fun startServer(mode: String, configPath: String): Int
    private external fun stopServer(handle: Int)
    private external fun lastError(): String?

    /**
     * 启动一个服务实例。
     * @return 句柄 >=0 表示成功且已同步监听就绪；-1 表示失败（原因见 [lastErrorText]）
     */
    fun start(mode: String, configPath: String): Int {
        if (!ensureLoaded()) return -1
        return try {
            startServer(mode, configPath)
        } catch (t: Throwable) {
            loadError = t.message ?: t.javaClass.simpleName
            Log.w(TAG, "startServer($mode) 异常", t)
            -1
        }
    }

    /** 停止指定句柄（关闭监听与在途连接）；句柄无效时静默。 */
    fun stop(handle: Int) {
        if (handle < 0 || !loaded) return
        try {
            stopServer(handle)
        } catch (t: Throwable) {
            Log.w(TAG, "stopServer($handle) 异常", t)
        }
    }

    /** 最近一次引擎错误描述（永不为 null）。 */
    fun lastErrorText(): String {
        if (!loaded) return loadError ?: "内置协议引擎未能加载（libdirtunneld.so）"
        return try {
            lastError()?.ifBlank { null } ?: "未知错误"
        } catch (t: Throwable) {
            loadError ?: (t.message ?: "未知错误")
        }
    }

    private const val TAG = "NativeEngine"
}
