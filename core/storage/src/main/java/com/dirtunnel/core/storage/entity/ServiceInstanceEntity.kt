package com.dirtunnel.core.storage.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.dirtunnel.core.storage.model.PROFILE_PUBLIC
import com.dirtunnel.core.storage.model.AccessPermission
import com.dirtunnel.core.storage.model.ProtocolType

/**
 * 服务实例（REQ 4.2 服务实例管理）。
 * 属性：绑定目录、协议类型、监听端口、账号密码（匿名开关独立）、权限配置、
 * 是否启用穿透、是否自动启动。
 *
 * 注意：密码不入库，存于 EncryptedSharedPreferences（TECH 六.1），
 * key = "service_password_<id>"。
 */
@Entity(
    tableName = "service_instances",
    foreignKeys = [
        ForeignKey(
            entity = DirectoryEntity::class,
            parentColumns = ["id"],
            childColumns = ["directoryId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("directoryId"), Index("port", unique = true)],
)
data class ServiceInstanceEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val directoryId: Long,
    val name: String,
    val protocol: String = ProtocolType.HTTP.name,
    /** 对外监听端口（用户自定义，启动前做端口占用扫描） */
    val port: Int = ProtocolType.HTTP.defaultPort,
    /** 账号（匿名开启时忽略） */
    val username: String = "",
    /** 匿名开关独立，默认关闭（TECH 六.3：所有服务默认不开启匿名访问） */
    val anonymous: Boolean = false,
    /** 权限：只读 / 读写 / 禁止删除 */
    val permission: String = AccessPermission.READ_ONLY.name,
    /** 是否启用穿透 */
    val enableTunnel: Boolean = false,
    /** 穿透归属：0=公共隧道，>0=自建 FRP 档案 id */
    val tunnelProfileId: Long = PROFILE_PUBLIC,
    /** 自动启动（开机/APP启动） */
    val autoStart: Boolean = false,
    /** IP 白名单，逗号分隔，支持 192.168.1.* 前缀；空=不限制（REQ 4.4） */
    val whitelist: String = "",
    /** 流量限速 KB/s，0=不限速（V1.2） */
    val rateLimitKbps: Int = 0,
    /** 最大连接数，0=不限制（V1.2） */
    val maxConnections: Int = 0,
    /** 使用自定义证书（V1.1 HTTPS 自定义证书） */
    val useCustomCert: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
) {
    val protocolType: ProtocolType get() = ProtocolType.fromName(protocol)
    val accessPermission: AccessPermission get() = AccessPermission.fromName(permission)

    fun whitelistSet(): List<String> =
        whitelist.split(',').map { it.trim() }.filter { it.isNotEmpty() }
}
