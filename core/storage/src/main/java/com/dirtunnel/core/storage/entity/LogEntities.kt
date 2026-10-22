package com.dirtunnel.core.storage.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/** 连接事件类型。 */
enum class ConnectionEvent { CONNECT, DENY, DISCONNECT, ERROR }

/** 传输方向：客户端→服务端为上传，服务端→客户端为下载。 */
enum class TransferDirection { UPLOAD, DOWNLOAD }

/**
 * 连接日志（REQ 4.4：连接日志、上传下载日志，支持导出）。
 */
@Entity(tableName = "connection_logs", indices = [Index("timestamp")])
data class ConnectionLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    /** 关联服务实例 id；隧道/转发类事件为 0 */
    val serviceId: Long = 0,
    val serviceName: String = "",
    val protocol: String = "",
    val clientIp: String = "",
    /** 触发账号（可按设置脱敏） */
    val account: String = "",
    val event: String = ConnectionEvent.CONNECT.name,
    val message: String = "",
)

/**
 * 上传/下载流量日志。
 */
@Entity(tableName = "transfer_logs", indices = [Index("timestamp")])
data class TransferLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val timestamp: Long = System.currentTimeMillis(),
    val serviceId: Long = 0,
    val serviceName: String = "",
    val protocol: String = "",
    val clientIp: String = "",
    val direction: String = TransferDirection.UPLOAD.name,
    val bytes: Long = 0,
    val account: String = "",
)
