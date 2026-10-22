package com.dirtunnel.core.storage.model

/**
 * 协议类型（REQ.md 三、协议规划 按版本优先级）。
 * 默认端口全部 ≥1024，遵循 REQ 4.4「端口自定义，避免默认端口风险」。
 */
enum class ProtocolType(
    val label: String,
    val defaultPort: Int,
    /** 10 = V1.0 首发，11 = V1.1 迭代，12 = V1.2 高阶 */
    val version: Int,
    /** 对应 core:engine 托管的静态二进制名 */
    val binary: String,
    val supportsTls: Boolean = false,
    /** V1.2 高阶功能，界面默认隐藏 */
    val advanced: Boolean = false,
    val description: String = "",
) {
    FTP("FTP", 2121, 10, "ftpd", description = "便携 ftpd 静态编译，标准 FTP 文件传输"),
    FTPS("FTPS", 9990, 10, "ftpd", supportsTls = true, description = "FTP + TLS（TLS1.2/1.3 现代加密套件）"),
    SFTP("SFTP", 2222, 10, "sftp-server", supportsTls = true, description = "OpenSSH sftp-server 子系统（SSH2）"),
    HTTP("HTTP", 8080, 10, "minihttpd", description = "静态网页浏览与下载"),
    WEBDAV("WebDAV", 8081, 10, "minihttpd", supportsTls = true, description = "WebDAV / WebDAV over HTTPS"),
    SMB("SMB2/3", 1445, 11, "smbd", description = "Samba，V1.1 引入，默认禁用 SMB1"),
    RSYNC("Rsync", 8873, 12, "rsync", advanced = true, description = "增量同步服务端（V1.2 高阶，默认隐藏）"),
    NFS("NFS", 20490, 12, "nfsd", advanced = true, description = "Linux 挂载（V1.2 高阶，默认关闭）");

    val isTunnelProtocol: Boolean get() = true

    companion object {
        fun fromName(name: String?): ProtocolType =
            entries.firstOrNull { it.name == name } ?: HTTP
    }
}

/** 目录权限：只读 / 读写 / 禁止删除（REQ 4.1.4）。 */
enum class AccessPermission(val label: String, val hint: String) {
    READ_ONLY("只读", "仅可浏览、下载"),
    READ_WRITE("读写", "可上传、修改、删除"),
    NO_DELETE("禁止删除", "可上传与修改，不可删除");

    companion object {
        fun fromName(name: String?): AccessPermission =
            entries.firstOrNull { it.name == name } ?: READ_ONLY
    }
}

/** 隧道配置归属：公共隧道 or 自建 FRP 档案（REQ 4.3 两种模式）。 */
const val PROFILE_PUBLIC = 0L
