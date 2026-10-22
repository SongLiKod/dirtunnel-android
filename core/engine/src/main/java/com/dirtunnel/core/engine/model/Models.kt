package com.dirtunnel.core.engine.model

import com.dirtunnel.core.storage.model.AccessPermission
import com.dirtunnel.core.storage.model.ProtocolType

/**
 * 服务规格（Domain 层输入，与 Room 实体解耦）。
 * 由 feature:service 从 ServiceInstanceEntity 映射而来。
 */
data class ServiceSpec(
    val serviceId: Long,
    val name: String,
    val protocol: ProtocolType,
    /** 目录真实文件系统路径 */
    val rootPath: String,
    /** 子进程内部监听端口（仅 127.0.0.1，对外经 TcpProxyServer 暴露） */
    val internalPort: Int,
    val username: String,
    /** 明文密码仅在进程启动瞬间用于写入 0600 配置文件，不进入日志/命令行 */
    val password: String?,
    /** 匿名访问，默认关闭（TECH 六.3） */
    val anonymous: Boolean,
    val permission: AccessPermission,
    /** V1.1 自定义证书（PEM cert+key 所在目录） */
    val useCustomCert: Boolean,
    val certDir: String?,
)

/** 描述一个待托管子进程（含 0600 敏感配置文件，TECH 六.2）。 */
data class ConfigFile(
    val name: String,
    val content: String,
)

data class ProcessSpec(
    /** 实例唯一 key，如 service_12 / tunnel_profile_1 */
    val key: String,
    val displayLabel: String,
    val binaryPath: String,
    val args: List<String>,
    val workDir: String,
    val env: Map<String, String> = emptyMap(),
    /** 启动前写入 workDir，权限 0600；含敏感信息时绝不进命令行 */
    val configFiles: List<ConfigFile> = emptyList(),
    /** 启动后等待该端口就绪的内部端口（0 = 不等待） */
    val readyPort: Int = 0,
    val readyTimeoutMs: Long = 8_000,
    /**
     * 进程内托管（JNI 加载 libdirtunneld.so）。
     * Android 10+（SELinux W^X）禁止 untrusted 应用 exec 自身数据目录文件，
     * V1.0 协议引擎必须在 App 进程内运行；binaryPath/args 此时不使用。
     */
    val execNative: Boolean = false,
    /** native 引擎模式：ftp | http | sftp */
    val nativeMode: String = "",
    /** native 引擎读取的配置文件名（相对 workDir，由 configFiles 按 0600 写入） */
    val nativeConfigName: String = "",
    /** native 引擎日志文件（宿主 tail 进运行日志，如 frpc 的 log.to） */
    val nativeLogPath: String? = null,
)

/** 子进程生命周期状态。 */
sealed interface ProcessState {
    data object Stopped : ProcessState
    data object Starting : ProcessState
    data class Running(val pid: Long, val startedAt: Long) : ProcessState
    data object Stopping : ProcessState
    data class Exited(val exitCode: Int, val at: Long) : ProcessState
    data class Failed(val message: String, val solution: String) : ProcessState
}

/** 二进制可用性/校验结果（TECH 六.6 md5 校验）。 */
data class BinaryStatus(
    val name: String,
    val fileName: String,
    val present: Boolean,
    val md5Match: Boolean,
    val expectedMd5: String,
    val actualMd5: String,
    val version: String,
    val path: String?,
    val solution: String,
)

/** 端口冲突检测结果（TECH 七.3）。 */
data class PortConflict(
    val port: Int,
    val inUse: Boolean,
    val solution: String,
)

/** 服务运行时状态（给 UI 状态卡片 / 仪表盘）。 */
data class ServiceRuntimeStatus(
    val serviceId: Long,
    val state: ProcessState,
    val internalPort: Int = 0,
    val proxyPort: Int = 0,
    val activeConnections: Int = 0,
    val bytesUp: Long = 0,
    val bytesDown: Long = 0,
    val lastError: String? = null,
    val lastErrorSolution: String? = null,
) {
    val isRunning: Boolean get() = state is ProcessState.Running
}
