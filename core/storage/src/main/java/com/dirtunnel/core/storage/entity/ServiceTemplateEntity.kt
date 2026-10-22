package com.dirtunnel.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.dirtunnel.core.storage.model.AccessPermission
import com.dirtunnel.core.storage.model.ProtocolType

/**
 * 配置模板（REQ 六 · V1.2 配置模板）。
 * 仅保存非敏感配置；账号密码不进模板。
 */
@Entity(tableName = "service_templates")
data class ServiceTemplateEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val protocol: String = ProtocolType.HTTP.name,
    val port: Int = ProtocolType.HTTP.defaultPort,
    val username: String = "",
    val anonymous: Boolean = false,
    val permission: String = AccessPermission.READ_ONLY.name,
    val enableTunnel: Boolean = false,
    val whitelist: String = "",
    val rateLimitKbps: Int = 0,
    val maxConnections: Int = 0,
    val createdAt: Long = System.currentTimeMillis(),
)
