package com.dirtunnel.core.storage.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * 目录实体（REQ 4.1 目录管理）。
 * treeUri 来自 SAF 文档树授权，持久化授权后重启不失效（TECH 七.4）。
 */
@Entity(tableName = "directories")
data class DirectoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    /** 展示名称（默认取末段路径） */
    val name: String,
    /** SAF 文档树 Uri，如 content://.../tree/primary%3ADownload */
    val treeUri: String,
    /** 解析出的真实文件系统路径（可空：非标准分区时无法解析，服务启动时提示） */
    val fsPath: String? = null,
    /** 目录收藏，一键复用（REQ 4.1.2） */
    val favorite: Boolean = false,
    val createdAt: Long = System.currentTimeMillis(),
    val lastUsedAt: Long = System.currentTimeMillis(),
)
