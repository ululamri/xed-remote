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
 * A [FileObject] whose backing store is a remote SSH server, accessed via Xed-Editor's own
 * Ubuntu proot environment (OpenSSH binaries, user's ~/.ssh config and keys).
 *
 * Reads: download the remote file to a temp file in Ubuntu home, return its bytes, delete.
 * Writes: write bytes to a temp file in Ubuntu home, sftp-put to remote, delete.
 * Directory ops: sftp mkdir/rm/rename, ssh stat.
 *
 * [attrs] is cached so the file-tree can call isDirectory()/getName()/etc from the UI thread
 * without hitting the network. It's @Transient so Java serialization (Xed-Editor's tab session
 * persist mechanism) only ever serializes the plain [path] string - safe, but the restored
 * object will need to re-stat on first use after an app restart.
 */
class RemoteFileObject internal constructor(
    val path: String,
    @Transient @Volatile private var attrs: RemoteFileStat? = null
) : FileObject {

    private val cachedName: String = if (path == "/") "/" else path.substringAfterLast('/')
    private val cachedExtension: String = cachedName.substringAfterLast('.', "")

    private fun childPath(name: String): String = if (path == "/") "/$name" else "$path/$name"

    private suspend fun ensureAttrs(): RemoteFileStat? {
        if (attrs == null) attrs = SshConnectionManager.statOrNull(path)
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
    override fun canExecute(): Boolean = attrs?.permissions?.let {
        it.length >= 3 && (it[2] == 'x' || it.contains("x"))
    } ?: false
    override fun lastModified(): Long = (attrs?.mtime ?: 0L) * 1000L
    override fun isSymlink(): Boolean = attrs?.isLink ?: false

    // ---- suspend ----

    override suspend fun listFiles(): List<FileObject> {
        val entries = SshConnectionManager.listDir(path) ?: return emptyList()
        return entries.map { (name, stat) -> RemoteFileObject(childPath(name), stat) }
    }

    override suspend fun getParentFile(): FileObject {
        if (path == "/") return this
        val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
        return RemoteFileObject(parentPath, SshConnectionManager.statOrNull(parentPath))
    }

    override suspend fun exists(): Boolean = ensureAttrs() != null

    override suspend fun createNewFile(): Boolean {
        val result = SshConnectionManager.sftpBatch("put /dev/null ${shellEscape(path)}")
        return result.exitCode == 0
    }

    override suspend fun getCanonicalPath(): String = path

    override suspend fun mkdir(): Boolean = SshConnectionManager.mkdir(path)

    override suspend fun mkdirs(): Boolean {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            val stat = SshConnectionManager.statOrNull(current)
            if (stat == null) {
                if (!SshConnectionManager.mkdir(current)) return false
            }
        }
        return true
    }

    override suspend fun readText(): String = readText(Charsets.UTF_8)

    override suspend fun readText(charset: Charset): String {
        val tmp = SshConnectionManager.downloadToTemp(path) ?: return ""
        return try { tmp.readText(charset) } finally { tmp.delete() }
    }

    override suspend fun writeText(text: String) { writeText(text, Charsets.UTF_8) }

    override suspend fun writeText(text: String, charset: Charset): Boolean {
        val tmp = createLocalTemp()
        return try {
            tmp.writeText(text, charset)
            SshConnectionManager.uploadFromLocal(tmp, path)
        } finally { tmp.delete() }
    }

    override suspend fun getInputStream(): InputStream {
        val tmp = SshConnectionManager.downloadToTemp(path)
        return if (tmp != null) {
            val bytes = tmp.readBytes()
            tmp.delete()
            ByteArrayInputStream(bytes)
        } else {
            ByteArrayInputStream(ByteArray(0))
        }
    }

    override suspend fun <R> useInputStream(block: suspend (InputStream) -> R): R {
        val stream = getInputStream()
        return try { block(stream) } finally { runCatching { stream.close() } }
    }

    override suspend fun getOutPutStream(append: Boolean): OutputStream {
        // For append, fetch existing content first.
        val existing: ByteArray = if (append) {
            val tmp = SshConnectionManager.downloadToTemp(path)
            if (tmp != null) try { tmp.readBytes() } finally { tmp.delete() } else ByteArray(0)
        } else {
            ByteArray(0)
        }

        return object : OutputStream() {
            private val buffer = ByteArrayOutputStream().apply { write(existing) }
            private var closed = false

            override fun write(b: Int) = buffer.write(b)
            override fun write(b: ByteArray, off: Int, len: Int) = buffer.write(b, off, len)

            override fun close() {
                if (closed) return
                closed = true
                val tmp = createLocalTemp()
                try {
                    tmp.writeBytes(buffer.toByteArray())
                    kotlinx.coroutines.runBlocking {
                        SshConnectionManager.uploadFromLocal(tmp, path)
                    }
                } finally { tmp.delete() }
            }
        }
    }

    override suspend fun length(): Long = ensureAttrs()?.size ?: 0L

    override suspend fun delete(): Boolean {
        val isDir = ensureAttrs()?.isDir ?: false
        return SshConnectionManager.rm(path, isDir)
    }

    override suspend fun toUri(): Uri =
        Uri.parse("ssh://${SshConnectionManager.host}:${SshConnectionManager.port}$path")

    override suspend fun getMimeType(context: Context): String =
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(cachedExtension.lowercase()) ?: "text/plain"

    override suspend fun renameTo(newName: String): Boolean {
        val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
        val newPath = if (parentPath == "/") "/$newName" else "$parentPath/$newName"
        return SshConnectionManager.rename(path, newPath)
    }

    override suspend fun hasChild(name: String): Boolean =
        SshConnectionManager.statOrNull(childPath(name)) != null

    override suspend fun createChild(createFile: Boolean, name: String): FileObject {
        val target = childPath(name)
        if (createFile) {
            SshConnectionManager.sftpBatch("put /dev/null ${shellEscape(target)}")
        } else {
            SshConnectionManager.mkdir(target)
        }
        return RemoteFileObject(target, SshConnectionManager.statOrNull(target))
    }

    override suspend fun getChildForName(name: String): FileObject {
        val target = childPath(name)
        return RemoteFileObject(target, SshConnectionManager.statOrNull(target))
    }

    override fun hashCode(): Int = path.hashCode()
    override fun equals(other: Any?): Boolean = other is RemoteFileObject && other.path == path
    override fun toString(): String = "ssh://${SshConnectionManager.host}$path"

    private fun shellEscape(s: String) = "'${s.replace("'", "'\\''")}'"

    private fun createLocalTemp(): File {
        val name = ".xed_upload_${System.currentTimeMillis()}_${cachedName.take(32)}"
        return File(sandboxHomeDir(), name)
    }

    companion object {
        private const val serialVersionUID: Long = 2L
    }
}
