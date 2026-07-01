package com.ulul.remoteworkspace

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.rk.file.FileObject
import com.rk.file.sandboxHomeDir
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * FileObject backed by SSH/SFTP via [ConnectionManager] (ControlMaster persistent connection)
 * with content reads going through [FileCache] (LRU disk cache, max 100MB).
 *
 * Read path:
 *   1. Check FileCache with current mtime (fast, no network if hit).
 *   2. On miss: sftp-download to temp, read bytes, store in cache, return.
 *
 * Write path (write-through):
 *   1. Write bytes to a temp file in Ubuntu home.
 *   2. sftp-put to remote.
 *   3. Invalidate the cache entry for this path (next read will fetch fresh).
 */
class RemoteFileObject internal constructor(
    val path: String,
    @Transient @Volatile private var attrs: RemoteFileStat? = null
) : FileObject {

    private val cachedName: String = if (path == "/") "/" else path.substringAfterLast('/')
    private val cachedExtension: String = cachedName.substringAfterLast('.', "")

    private fun childPath(name: String): String = if (path == "/") "/$name" else "$path/$name"

    private suspend fun ensureAttrs(): RemoteFileStat? {
        if (attrs == null) attrs = ConnectionManager.statOrNull(path)
        return attrs
    }

    // ---- synchronous (cached) ----
    override fun isDirectory(): Boolean = attrs?.isDir ?: false
    override fun isFile(): Boolean = !(attrs?.isDir ?: false)
    override fun getName(): String = cachedName
    override fun getExtension(): String = cachedExtension
    override fun getAbsolutePath(): String = path
    override fun canRead(): Boolean = true
    override fun canWrite(): Boolean = true
    override fun canExecute(): Boolean = attrs?.permissions?.contains("x") ?: false
    override fun lastModified(): Long = (attrs?.mtime ?: 0L) * 1000L
    override fun isSymlink(): Boolean = attrs?.isLink ?: false

    // ---- suspend ----

    override suspend fun listFiles(): List<FileObject> {
        val entries = ConnectionManager.listDir(path) ?: return emptyList()
        return entries.map { (name, stat) -> RemoteFileObject(childPath(name), stat) }
    }

    override suspend fun getParentFile(): FileObject {
        if (path == "/") return this
        val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
        return RemoteFileObject(parentPath, ConnectionManager.statOrNull(parentPath))
    }

    override suspend fun exists(): Boolean = ensureAttrs() != null

    override suspend fun createNewFile(): Boolean {
        val result = ConnectionManager.sftpBatch("put /dev/null ${shellEscape(path)}")
        if (result.exitCode == 0) attrs = ConnectionManager.statOrNull(path)
        return result.exitCode == 0
    }

    override suspend fun getCanonicalPath(): String = path

    override suspend fun mkdir(): Boolean {
        val ok = ConnectionManager.mkdir(path)
        if (ok) attrs = ConnectionManager.statOrNull(path)
        return ok
    }

    override suspend fun mkdirs(): Boolean {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            if (ConnectionManager.statOrNull(current) == null) {
                if (!ConnectionManager.mkdir(current)) return false
            }
        }
        attrs = ConnectionManager.statOrNull(path)
        return true
    }

    override suspend fun readText(): String = readText(Charsets.UTF_8)

    override suspend fun readText(charset: Charset): String {
        val bytes = readBytes() ?: return ""
        return bytes.toString(charset)
    }

    override suspend fun writeText(text: String) { writeText(text, Charsets.UTF_8) }

    override suspend fun writeText(text: String, charset: Charset): Boolean {
        val ok = writeBytes(text.toByteArray(charset))
        if (ok) attrs = null   // Force re-stat next time so mtime updates.
        return ok
    }

    override suspend fun getInputStream(): InputStream {
        val bytes = readBytes() ?: return ByteArrayInputStream(ByteArray(0))
        return ByteArrayInputStream(bytes)
    }

    override suspend fun <R> useInputStream(block: suspend (InputStream) -> R): R {
        val s = getInputStream()
        return try { block(s) } finally { runCatching { s.close() } }
    }

    override suspend fun getOutPutStream(append: Boolean): OutputStream {
        val existing: ByteArray = if (append) readBytes() ?: ByteArray(0) else ByteArray(0)
        return object : OutputStream() {
            private val buf = ByteArrayOutputStream().apply { write(existing) }
            private var closed = false
            override fun write(b: Int) = buf.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buf.write(b, off, len)
            override fun close() {
                if (closed) return
                closed = true
                kotlinx.coroutines.runBlocking { writeBytes(buf.toByteArray()) }
            }
        }
    }

    override suspend fun length(): Long = ensureAttrs()?.size ?: 0L

    override suspend fun delete(): Boolean {
        val isDir = ensureAttrs()?.isDir ?: false
        val ok = ConnectionManager.rm(path, isDir)
        if (ok) FileCache.invalidate(path)
        return ok
    }

    override suspend fun toUri(): Uri =
        Uri.parse("ssh://${ConnectionManager.host}:${ConnectionManager.port}$path")

    override suspend fun getMimeType(context: Context): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(cachedExtension.lowercase()) ?: "text/plain"

    override suspend fun renameTo(newName: String): Boolean {
        val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
        val newPath = if (parentPath == "/") "/$newName" else "$parentPath/$newName"
        val ok = ConnectionManager.rename(path, newPath)
        if (ok) FileCache.invalidate(path)
        return ok
    }

    override suspend fun hasChild(name: String): Boolean =
        ConnectionManager.statOrNull(childPath(name)) != null

    override suspend fun createChild(createFile: Boolean, name: String): FileObject {
        val target = childPath(name)
        if (createFile) {
            ConnectionManager.sftpBatch("put /dev/null ${shellEscape(target)}")
        } else {
            ConnectionManager.mkdir(target)
        }
        return RemoteFileObject(target, ConnectionManager.statOrNull(target))
    }

    override suspend fun getChildForName(name: String): FileObject {
        val target = childPath(name)
        return RemoteFileObject(target, ConnectionManager.statOrNull(target))
    }

    override fun hashCode(): Int = path.hashCode()
    override fun equals(other: Any?): Boolean = other is RemoteFileObject && other.path == path
    override fun toString(): String = "ssh://${ConnectionManager.host}$path"

    // ---- internal helpers ----

    /** Read bytes: cache-first, then sftp-download on miss. */
    private suspend fun readBytes(): ByteArray? {
        val currentAttrs = ensureAttrs() ?: return null
        return FileCache.getOrFetch(path, currentAttrs.mtime) {
            val tmp = ConnectionManager.downloadToTemp(path) ?: return@getOrFetch null
            try { tmp.readBytes() } finally { tmp.delete() }
        }
    }

    /** Write bytes: upload to remote, invalidate cache entry. */
    private suspend fun writeBytes(bytes: ByteArray): Boolean {
        val tmp = createLocalTemp()
        return try {
            tmp.writeBytes(bytes)
            val ok = ConnectionManager.uploadFromLocal(tmp, path)
            if (ok) {
                FileCache.invalidate(path)
                attrs = null   // Force mtime re-fetch on next read.
            }
            ok
        } finally {
            tmp.delete()
        }
    }

    private fun createLocalTemp(): File {
        val name = ".xed_upload_${System.currentTimeMillis()}_${cachedName.take(32)}"
        return File(sandboxHomeDir(), name)
    }

    private fun shellEscape(s: String): String = "'${s.replace("'", "'\\''")}'"

    companion object {
        private const val serialVersionUID: Long = 3L
    }
}
