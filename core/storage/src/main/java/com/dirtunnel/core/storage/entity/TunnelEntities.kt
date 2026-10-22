package com.dirtunnel.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dirtunnel.core.storage.model.PROFILE_PUBLIC

/**
 * 自建 FRP 服务端档案（REQ 4.3.2：填写服务器地址、端口、token，图形化配置，无需手写 ini）。
 * token 不入库，存于 EncryptedSharedPreferences，key = "frp_token_<id>"（TECH 六.1）。
 */
@Entity(tableName = "frp_profiles")
data class FrpProfileEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    /** frps 服务器地址 */
    val serverAddr: String,
    /** frps 服务端口（默认 7000） */
    val serverPort: Int = 7000,
    /** 启用 frp TLS 传输加密 */
    val useTls: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
) {
    companion object
}

/**
 * 自定义本地 TCP 端口转发（REQ 三 · 穿透层独立能力）：
 * 用户可启动 APP 以外的本地服务，通过 DirTunnel 的 FRP 隧道转发出去。
 * 隧道兼容 TCP，适配所有内置协议 + 用户自定义端口（REQ 4.3）。
 */
@Entity(tableName = "tcp_forwards")
data class TcpForwardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 展示名称，如 "本地 MySQL" */
    val name: String,
    /** 本地服务监听端口（APP 以外的本地服务） */
    val localPort: Int,
    /** 公网远端端口，0=由服务器分配 */
    val remotePort: Int = 0,
    /** 穿透归属：0=公共隧道，>0=自建 FRP 档案 id */
    val profileId: Long = PROFILE_PUBLIC,
    val enabled: Boolean = true,
    val autoStart: Boolean = true,
    val createdAt: Long = System.currentTimeMillis(),
)
