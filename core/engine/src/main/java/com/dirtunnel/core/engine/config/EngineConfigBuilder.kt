package com.dirtunnel.core.engine.config

import com.dirtunnel.core.engine.model.ConfigFile
import com.dirtunnel.core.engine.model.ProcessSpec
import com.dirtunnel.core.engine.model.ServiceSpec
import com.dirtunnel.core.engine.network.LanAddresses
import com.dirtunnel.core.storage.model.AccessPermission
import com.dirtunnel.core.storage.model.ProtocolType
import java.io.File
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import android.util.Base64

/**
 * 协议引擎参数组装（TECH 二）：按协议生成子进程配置与命令行。
 *
 * 安全约定（TECH 六）：
 * - 账号密码写入 0600 auth/secrets 文件，不进命令行明文；
 * - 所有对外流量只经 127.0.0.1 内部端口，由 TcpProxyServer 统一做
 *   IP 白名单 / 连接数 / 限速 / 流量记账后再暴露到用户配置的端口；
 * - TLS 协议强制 TLS1.2/1.3 现代加密套件（REQ 4.4）。
 */
object EngineConfigBuilder {

    /**
     * @param binaries 协议相关二进制，key 见 assets/binaries.json 的 name
     * @param configDir 实例专属配置目录（内容按 0600 写入）
     */
    fun build(
        spec: ServiceSpec,
        binaries: Map<String, File>,
        configDir: File,
    ): Result<ProcessSpec> = runCatching {
        configDir.mkdirs()
        val primary = binaries[spec.protocol.binary]
            ?: error("缺少协议组件 ${spec.protocol.binary}")
        when (spec.protocol) {
            ProtocolType.FTP -> ftpConfig(spec, primary, configDir, tls = false)
            ProtocolType.FTPS -> ftpConfig(spec, primary, configDir, tls = true)
            ProtocolType.SFTP -> sftpConfig(spec, binaries, configDir)
            ProtocolType.HTTP -> httpConfig(spec, primary, configDir, webdav = false)
            ProtocolType.WEBDAV -> httpConfig(spec, primary, configDir, webdav = true)
            ProtocolType.SMB -> smbConfig(spec, primary, configDir)
            ProtocolType.RSYNC -> rsyncConfig(spec, primary, configDir)
            ProtocolType.NFS -> nfsConfig(spec, primary, configDir)
        }
    }

    // ===== 权限映射 =====

    private fun readonly(p: AccessPermission): Boolean = p == AccessPermission.READ_ONLY
    private fun allowDelete(p: AccessPermission): Boolean = p == AccessPermission.READ_WRITE

    /** TLS 证书（V1.1 自定义证书：cert.pem / key.pem）。 */
    private fun tlsFiles(spec: ServiceSpec): Pair<String?, String?> {
        if (!spec.useCustomCert) return null to null
        val dir = spec.certDir ?: return null to null
        val cert = File(dir, "cert.pem")
        val key = File(dir, "key.pem")
        return if (cert.exists() && key.exists()) cert.path to key.path else null to null
    }

    private fun specKey(spec: ServiceSpec) = "service_${spec.serviceId}"

    // ===== FTP / FTPS（portable ftpd 静态编译） =====

    private fun ftpConfig(
        spec: ServiceSpec,
        binary: File,
        configDir: File,
        tls: Boolean,
    ): ProcessSpec {
        val cfg = File(configDir, "ftpd.conf")
        val accounts = File(configDir, "accounts")
        val (cert, key) = tlsFiles(spec)

        val body = buildString {
            appendLine("# DirTunnel portable-ftpd 配置（自动生成）")
            appendLine("bind_address = 127.0.0.1")
            appendLine("port = ${spec.internalPort}")
            appendLine("root = ${spec.rootPath}")
            appendLine("anonymous = ${if (spec.anonymous) "yes" else "no"}")
            if (!spec.anonymous) appendLine("auth_file = ${accounts.path}")
            appendLine("readonly = ${if (readonly(spec.permission)) "yes" else "no"}")
            appendLine("delete = ${if (allowDelete(spec.permission)) "yes" else "no"}")
            appendLine("max_clients = 16")
            appendLine("log_file = ${File(configDir, "server.log").path}")
            // PASV 广告地址与连接弹窗保持一致（否则 FTP 被动连接会用引擎自选 IP，可能误选 VPN 口）
            LanAddresses.all().firstOrNull()?.let { appendLine("pasv_address = $it") }
            if (tls) {
                appendLine("ssl_enable = yes")
                appendLine("ssl_only = yes")
                appendLine("ssl_min_version = TLSv1.2")
                if (cert != null && key != null) {
                    appendLine("tls_cert = $cert")
                    appendLine("tls_key = $key")
                }
            }
        }

        return ProcessSpec(
            key = specKey(spec),
            displayLabel = "${spec.name} (${if (tls) "FTPS" else "FTP"} :${spec.internalPort})",
            binaryPath = binary.path,
            args = listOf("ftp", "-c", cfg.path),
            workDir = configDir.path,
            configFiles = buildList {
                add(ConfigFile(cfg.name, body))
                if (!spec.anonymous && !spec.username.isNullOrBlankCompat()) {
                    add(ConfigFile(accounts.name, "${spec.username}:${spec.password.orEmpty()}\n"))
                }
            },
            readyPort = spec.internalPort,
            execNative = true,
            nativeMode = "ftp",
            nativeConfigName = cfg.name,
            nativeLogPath = File(configDir, "server.log").path,
        )
    }

    // ===== SFTP（openssh sshd + sftp-server 子系统） =====

    private fun sftpConfig(
        spec: ServiceSpec,
        binaries: Map<String, File>,
        configDir: File,
    ): ProcessSpec {
        val sshd = binaries["sshd"] ?: error("缺少协议组件 sshd")
        val sftpServer = binaries["sftp-server"] ?: error("缺少协议组件 sftp-server")
        val cfg = File(configDir, "sshd_config")
        val accounts = File(configDir, "accounts")
        val hostKey = File(configDir, "host_ed25519")

        if (!hostKey.exists()) {
            hostKey.writeText(generatePrivateKeyPem())
            hostKey.setReadable(false, false)
            hostKey.setWritable(false, false)
            hostKey.setReadable(true, true)
            hostKey.setWritable(true, true)
        }

        val sftpArgs = buildList {
            add("-d"); add(spec.rootPath)
            if (readonly(spec.permission)) add("-R")
        }

        val body = buildString {
            appendLine("# DirTunnel OpenSSH sshd 配置（自动生成，承载 sftp-server 子系统）")
            appendLine("Port ${spec.internalPort}")
            appendLine("ListenAddress 127.0.0.1")
            appendLine("HostKey ${hostKey.path}")
            appendLine("PidFile ${File(configDir, "sshd.pid").path}")
            appendLine("PasswordAuthentication yes")
            appendLine("KbdInteractiveAuthentication no")
            appendLine("PubkeyAuthentication yes")
            appendLine("PermitRootLogin no")
            appendLine("AllowTcpForwarding no")
            appendLine("X11Forwarding no")
            appendLine("AllowAgentForwarding no")
            appendLine("PrintMotd no")
            appendLine("LogLevel INFO")
            appendLine("UsePAM no")
            if (!spec.anonymous) {
                appendLine("AllowUsers ${spec.username}")
                appendLine("PasswordFile ${accounts.path}")
            }
            appendLine("Subsystem sftp ${sftpServer.path} ${sftpArgs.joinToString(" ")}")
            if (spec.useCustomCert) {
                val (cert, key) = tlsFiles(spec)
                if (cert != null && key != null) {
                    appendLine("HostCertificate $cert")
                    appendLine("HostKey $key")
                }
            }
        }

        return ProcessSpec(
            key = specKey(spec),
            displayLabel = "${spec.name} (SFTP :${spec.internalPort})",
            binaryPath = sshd.path,
            args = listOf("sftp", "-f", cfg.path, "-D"),
            workDir = configDir.path,
            configFiles = buildList {
                add(ConfigFile(cfg.name, body))
                if (!spec.anonymous) {
                    add(ConfigFile(accounts.name, "${spec.username}:${spec.password.orEmpty()}\n"))
                }
            },
            readyPort = spec.internalPort,
            execNative = true,
            nativeMode = "sftp",
            nativeConfigName = cfg.name,
        )
    }

    // ===== HTTP / WebDAV（minihttpd + webdav 模块） =====

    private fun httpConfig(
        spec: ServiceSpec,
        binary: File,
        configDir: File,
        webdav: Boolean,
    ): ProcessSpec {
        val cfg = File(configDir, "minihttpd.conf")
        val accounts = File(configDir, "accounts")
        val (cert, key) = tlsFiles(spec)
        val tls = spec.useCustomCert && cert != null && key != null

        val body = buildString {
            appendLine("# DirTunnel minihttpd 配置（自动生成）")
            appendLine("bind = 127.0.0.1")
            appendLine("port = ${spec.internalPort}")
            appendLine("dir = ${spec.rootPath}")
            appendLine("index_files = index.html,index.htm")
            appendLine("directory_listing = yes")
            appendLine("follow_symlinks = no")
            appendLine("anonymous = ${if (spec.anonymous) "yes" else "no"}")
            if (!spec.anonymous) {
                appendLine("auth_required = yes")
                appendLine("auth_file = ${accounts.path}")
            } else {
                appendLine("auth_required = no")
            }
            appendLine("readonly = ${if (readonly(spec.permission)) "yes" else "no"}")
            appendLine("delete = ${if (allowDelete(spec.permission)) "yes" else "no"}")
            appendLine("max_clients = 16")
            appendLine("log_file = ${File(configDir, "access.log").path}")
            if (webdav) {
                appendLine("dav = yes")
                appendLine("dav_path = ${spec.rootPath}")
                appendLine("dav_create = yes")
                appendLine("dav_lock = ${File(configDir, "dav.lock").path}")
                appendLine("dav_methods = GET,PUT,DELETE,MKCOL,PROPFIND,PROPPATCH,MOVE,COPY,LOCK,UNLOCK")
            }
            if (tls) {
                // WebDAV over HTTPS / HTTPS 静态站（V1.1 自定义证书）
                appendLine("ssl_enable = yes")
                appendLine("ssl_min_version = TLSv1.2")
                appendLine("tls_cert = $cert")
                appendLine("tls_key = $key")
            }
        }

        return ProcessSpec(
            key = specKey(spec),
            displayLabel = when {
                webdav && tls -> "${spec.name} (WebDAV over HTTPS :${spec.internalPort})"
                webdav -> "${spec.name} (WebDAV :${spec.internalPort})"
                else -> "${spec.name} (HTTP :${spec.internalPort})"
            },
            binaryPath = binary.path,
            args = listOf("http", "-c", cfg.path),
            workDir = configDir.path,
            configFiles = buildList {
                add(ConfigFile(cfg.name, body))
                if (!spec.anonymous) {
                    add(ConfigFile(accounts.name, "${spec.username}:${spec.password.orEmpty()}\n"))
                }
            },
            readyPort = spec.internalPort,
            execNative = true,
            nativeMode = "http",
            nativeConfigName = cfg.name,
            nativeLogPath = File(configDir, "access.log").path,
        )
    }

    // ===== SMB2/3（samba，V1.1 默认禁用 SMB1） =====

    private fun smbConfig(
        spec: ServiceSpec,
        binary: File,
        configDir: File,
    ): ProcessSpec {
        val cfg = File(configDir, "smb.conf")
        val accounts = File(configDir, "accounts")
        val module = "share"

        val body = buildString {
            appendLine("# DirTunnel Samba 配置（自动生成）")
            appendLine("[global]")
            appendLine("   workgroup = WORKGROUP")
            appendLine("   server string = DirTunnel ${spec.name}")
            appendLine("   security = user")
            appendLine("   map to guest = Never")           // 默认禁止匿名（TECH 六.3）
            appendLine("   smb ports = ${spec.internalPort}")
            appendLine("   bind interfaces only = yes")
            appendLine("   interfaces = 127.0.0.1")
            appendLine("   netbios name = DIRTUNNEL")
            appendLine("   disable netbios = yes")
            appendLine("   dns proxy = no")
            appendLine("   load printers = no")
            appendLine("   printing = bsd")
            appendLine("   printcap name = /dev/null")
            appendLine("   disable spoolss = yes")
            // 默认禁用 SMB1（REQ 三 / V1.1）
            appendLine("   server min protocol = SMB2")
            appendLine("   server max protocol = SMB3")
            appendLine("   encrypt smb3 = desired")
            appendLine("   pid directory = ${File(configDir, "pid").path}")
            appendLine("   state directory = ${File(configDir, "state").path}")
            appendLine("   lock directory = ${File(configDir, "lock").path}")
            appendLine("   private dir = ${configDir.path}")
            appendLine("   log file = ${File(configDir, "smbd.log").path}")
            appendLine("   max log size = 512")
            appendLine("   usershare allow shares = no")
            if (!spec.anonymous) {
                appendLine("   passdb backend = tdbsam:${accounts.path}")
            }
            appendLine("")
            appendLine("[$module]")
            appendLine("   path = ${spec.rootPath}")
            appendLine("   browseable = yes")
            appendLine("   read only = ${if (readonly(spec.permission)) "yes" else "no"}")
            appendLine("   writable = ${if (readonly(spec.permission)) "no" else "yes"}")
            appendLine("   guest ok = ${if (spec.anonymous) "yes" else "no"}")
            if (!spec.anonymous) {
                appendLine("   valid users = ${spec.username}")
            }
            appendLine("   create mask = 0644")
            appendLine("   directory mask = 0755")
            appendLine("   veto files = /")
            if (!allowDelete(spec.permission)) {
                appendLine("   delete readonly = no")
            }
        }

        return ProcessSpec(
            key = specKey(spec),
            displayLabel = "${spec.name} (SMB2/3 :${spec.internalPort})",
            binaryPath = binary.path,
            args = listOf("-F", "-s", cfg.path),
            workDir = configDir.path,
            configFiles = buildList {
                add(ConfigFile(cfg.name, body))
                if (!spec.anonymous) {
                    // 静态 smbd 扩展账号文件（0600）
                    add(ConfigFile(accounts.name, "${spec.username}:${spec.password.orEmpty()}\n"))
                }
            },
            readyPort = spec.internalPort,
        )
    }

    // ===== Rsync（V1.2） =====

    private fun rsyncConfig(
        spec: ServiceSpec,
        binary: File,
        configDir: File,
    ): ProcessSpec {
        val cfg = File(configDir, "rsyncd.conf")
        val secrets = File(configDir, "secrets")
        val body = buildString {
            appendLine("# DirTunnel rsync 守护进程配置（自动生成）")
            appendLine("use chroot = no")
            appendLine("port = ${spec.internalPort}")
            appendLine("pid file = ${File(configDir, "rsyncd.pid").path}")
            appendLine("log file = ${File(configDir, "rsyncd.log").path}")
            appendLine("lock file = ${File(configDir, "rsyncd.lock").path}")
            appendLine("read only = ${if (readonly(spec.permission)) "yes" else "no"}")
            if (!spec.anonymous) {
                appendLine("auth users = ${spec.username}")
                appendLine("secrets file = ${secrets.path}")
            }
            appendLine("")
            appendLine("[share]")
            appendLine("   path = ${spec.rootPath}")
            appendLine("   comment = DirTunnel ${spec.name}")
            appendLine("   read only = ${if (readonly(spec.permission)) "yes" else "no"}")
            appendLine("   list = yes")
            if (!allowDelete(spec.permission)) {
                appendLine("   write only = no")
            }
        }
        return ProcessSpec(
            key = specKey(spec),
            displayLabel = "${spec.name} (Rsync :${spec.internalPort})",
            binaryPath = binary.path,
            args = listOf("--daemon", "--no-detach", "--config=${cfg.path}"),
            workDir = configDir.path,
            configFiles = buildList {
                add(ConfigFile(cfg.name, body))
                if (!spec.anonymous) {
                    add(ConfigFile(secrets.name, "${spec.username}:${spec.password.orEmpty()}\n"))
                }
            },
            readyPort = spec.internalPort,
        )
    }

    // ===== NFS（V1.2 高阶） =====

    private fun nfsConfig(
        spec: ServiceSpec,
        binary: File,
        configDir: File,
    ): ProcessSpec {
        val exports = File(configDir, "exports")
        val body = buildString {
            appendLine("# DirTunnel NFS 导出（自动生成，V1.2 高阶功能）")
            appendLine(
                "${spec.rootPath} *(rw=${if (readonly(spec.permission)) "no" else "yes"}," +
                    "no_root_squash,insecure,no_subtree_check)",
            )
        }
        return ProcessSpec(
            key = specKey(spec),
            displayLabel = "${spec.name} (NFS :${spec.internalPort})",
            binaryPath = binary.path,
            args = listOf("--config", exports.path, "--port", spec.internalPort.toString()),
            workDir = configDir.path,
            configFiles = listOf(ConfigFile(exports.name, body)),
            readyPort = spec.internalPort,
        )
    }

    // ===== 工具 =====

    private fun String?.isNullOrBlankCompat(): Boolean = this == null || this.isBlank()

    /**
     * 生成 RSA 主机私钥（PKCS#8 PEM），写入 0600 文件。
     * （TECH 六.2：敏感材料落盘 0600，不进命令行）
     */
    fun generatePrivateKeyPem(bits: Int = 2048): String = try {
        val kpg = KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }
        val kp = kpg.generateKeyPair()
        val encoded = Base64.encodeToString(
            kp.private.encoded,
            Base64.NO_WRAP or Base64.NO_CLOSE,
        )
        "-----BEGIN PRIVATE KEY-----\n" +
            encoded.chunked(64).joinToString("\n") +
            "\n-----END PRIVATE KEY-----\n"
    } catch (t: Throwable) {
        // 兜底：占位（真实密钥由启动失败提示引导用户重装组件）
        ""
    }

    @Suppress("unused")
    private fun privateKeyFromBytes(bytes: ByteArray): PrivateKey? = try {
        java.security.KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(bytes))
    } catch (t: Throwable) {
        null
    }
}
