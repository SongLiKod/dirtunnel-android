package com.dirtunnel.core.storage.saf

import android.content.Context
import android.content.Intent
import android.content.UriPermission
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.webkit.MimeTypeMap
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton
import dagger.hilt.android.qualifiers.ApplicationContext

/** SAF 目录节点。 */
data class SaFNode(
    val documentId: String,
    val name: String,
    val uri: String,
    val mimeType: String,
    val size: Long,
    val lastModified: Long,
    val isDirectory: Boolean,
)

/**
 * SAF 存储访问框架封装（REQ 4.1.1 / TECH 一 · 文件访问）。
 * - 授权持久化，重启不失效（TECH 七.4 持久化 SAF Uri 授权）；
 * - tree Uri → 真实文件系统路径解析，供子进程二进制直接挂载目录；
 * - 列目录、校验授权有效性（SAF 权限失效告警，REQ 五 · 易用性）。
 */
@Singleton
class SaFDocumentTree @Inject constructor(
    @ApplicationContext context: Context,
) {
    private val appContext = context.applicationContext
    private val resolver get() = appContext.contentResolver

    /** 拿到持久化授权（读写）。 */
    fun takePersistable(treeUri: String, write: Boolean = true): Boolean = try {
        val uri = Uri.parse(treeUri)
        val readFlags = Intent.FLAG_GRANT_READ_URI_PERMISSION
        val writeFlags = Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            resolver.takePersistableUriPermission(uri, if (write) readFlags or writeFlags else readFlags)
        } catch (_: SecurityException) {
            resolver.takePersistableUriPermission(uri, readFlags)
        }
        true
    } catch (t: Throwable) {
        false
    }

    fun hasPersistable(treeUri: String): Boolean =
        resolver.persistedUriPermissions.any { it.uri.toString() == treeUri }

    fun releasePersistable(treeUri: String) {
        try {
            val uri = Uri.parse(treeUri)
            resolver.persistedUriPermissions
                .filter { it.uri.toString() == treeUri }
                .forEach { release(it, uri) }
        } catch (_: Throwable) {
        }
    }

    private fun release(p: UriPermission, uri: Uri) {
        try {
            var flags = 0
            if (p.isReadPermission) flags = flags or Intent.FLAG_GRANT_READ_URI_PERMISSION
            if (p.isWritePermission) flags = flags or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            if (flags != 0) {
                resolver.releasePersistableUriPermission(uri, flags)
            }
        } catch (_: Throwable) {
        }
    }

    /** 授权是否仍有效（SAF 权限重启失效检测）。 */
    fun isStillValid(treeUri: String): Boolean {
        if (!hasPersistable(treeUri)) return false
        return try {
            val uri = Uri.parse(treeUri)
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(
                uri,
                DocumentsContract.getTreeDocumentId(uri),
            )
            resolver.query(children, arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID), null, null, null)
                ?.use { it.moveToFirst() || it.count >= 0 } ?: false
        } catch (_: SecurityException) {
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** 列出目录子节点。 */
    fun listChildren(treeUri: String): List<SaFNode> = try {
        val uri = Uri.parse(treeUri)
        val treeDocId = DocumentsContract.getTreeDocumentId(uri)
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(uri, treeDocId)
        val projection = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_MIME_TYPE,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<SaFNode>()
        resolver.query(children, projection, null, null, null)?.use { c ->
            val iId = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
            val iName = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
            val iMime = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
            val iSize = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_SIZE)
            val iMod = c.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_LAST_MODIFIED)
            while (c.moveToNext()) {
                val docId = c.getString(iId)
                val mime = c.getString(iMime) ?: "application/octet-stream"
                out += SaFNode(
                    documentId = docId,
                    name = c.getString(iName) ?: docId,
                    uri = DocumentsContract.buildDocumentUriUsingTree(uri, docId).toString(),
                    mimeType = mime,
                    size = c.getLong(iSize),
                    lastModified = c.getLong(iMod),
                    isDirectory = DocumentsContract.Document.MIME_TYPE_DIR == mime,
                )
            }
        }
        out.sortedWith(compareByDescending<SaFNode> { it.isDirectory }.thenBy { it.name.lowercase() })
    } catch (t: Throwable) {
        emptyList()
    }

    /** 目录展示名。 */
    fun displayName(treeUri: String): String? = try {
        val treeId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        val raw = treeId.substringAfter(':', treeId)
        raw.substringAfterLast('/').takeIf { it.isNotEmpty() } ?: treeId
    } catch (t: Throwable) {
        null
    }

    /**
     * tree Uri → 真实路径（内部存储 / SD 卡）。
     * 解析失败返回 null，服务启动会提示“该分区无法直接映射到文件系统路径”。
     */
    fun resolveFsPath(treeUri: String): String? = try {
        val treeId = DocumentsContract.getTreeDocumentId(Uri.parse(treeUri))
        val path = when {
            treeId.startsWith("raw:") -> treeId.removePrefix("raw:")
            treeId.startsWith("primary:") -> {
                val rest = treeId.removePrefix("primary:")
                File(Environment.getExternalStorageDirectory(), rest).path
            }
            else -> {
                // 外置存储卡：XXXX-XXXX:dir/sub
                val parts = treeId.split(':')
                if (parts.size >= 2 && Regex("^[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}$").matches(parts[0])) {
                    File("/storage/${parts[0]}", parts.drop(1).joinToString(":")).path
                } else if (parts.size >= 2) {
                    File("/storage/${parts[0]}", parts.drop(1).joinToString(":")).path
                } else null
            }
        }
        path?.let { p ->
            val f = File(p)
            if (f.isDirectory && f.exists()) f.path else null
        }
    } catch (t: Throwable) {
        null
    }

    companion object {
        /** 打开系统目录选择器（SAF）。 */
        fun openTreeIntent(): Intent =
            Intent(Intent.ACTION_OPEN_DOCUMENT_TREE).apply {
                addFlags(
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                        Intent.FLAG_GRANT_WRITE_URI_PERMISSION or
                        Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION,
                )
            }
    }
}

/** 依据扩展名猜测 MIME。 */
fun mimeFor(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase()
    return MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "application/octet-stream"
}
