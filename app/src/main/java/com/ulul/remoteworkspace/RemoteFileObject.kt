package com.ulul.remoteworkspace

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import com.jcraft.jsch.SftpATTRS
import com.rk.file.FileObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.nio.charset.Charset

/**
 * A [FileObject] backed by an SFTP path on the [SshConnectionManager] singleton.
 *
 * Deliberately holds NO reference to [SshConnectionManager] (it's referenced directly, as a
 * global singleton, inside each method body instead). The host app's session-restore code
 * (`EditorTabState`) embeds the live `FileObject` of every open tab and persists it via plain
 * Java serialization on every app pause - if this class held a field pointing at the connection
 * manager (or at the JSch session/channel), that whole save would throw NotSerializableException
 * every time a remote tab was open, silently breaking session-restore for ALL tabs, not just
 * remote ones. Keeping this class to just a [path] string (plus a `@Transient` attrs cache)
 * keeps it harmlessly serializable: it survives a save, and if the app is ever restarted with a
 * remote tab in the saved session, the restored reference just won't be connected yet (it will
 * throw the usual "Belum terhubung ke server" on first use, rather than corrupting anything).
 *
 * Synchronous members (isDirectory/isFile/canRead/.../lastModified) only ever read cached
 * attributes - they must never block on the network, since the host app may call them from
 * the UI thread while rendering the file tree. Attributes are populated either by whoever
 * constructed this instance (e.g. a parent's listFiles() already has them from `ls`) or lazily
 * the first time a suspend member needs them.
 *
 * Whole-file buffering is used for reads/writes (simplest correct way to avoid corrupting the
 * single shared SFTP channel with overlapping operations) - fine for source files, not meant
 * for huge binaries.
 */
class RemoteFileObject internal constructor(
    val path: String,
    @Transient @Volatile private var attrs: SftpATTRS? = null
) : FileObject {

    private val cachedName: String =
        if (path == "/") "/" else path.substringAfterLast('/')
    private val cachedExtension: String =
        cachedName.substringAfterLast('.', "")

    private fun childPath(name: String): String = if (path == "/") "/$name" else "$path/$name"

    private suspend fun ensureAttrs(): SftpATTRS? {
        if (attrs == null) attrs = SshConnectionManager.statOrNull(path)
        return attrs
    }

    // ---- synchronous members: cached-only, never touch the network ----

    override fun isDirectory(): Boolean = attrs?.isDir ?: false
    override fun isFile(): Boolean = attrs?.let { !it.isDir } ?: true
    override fun getName(): String = cachedName
    override fun getExtension(): String = cachedExtension
    override fun getAbsolutePath(): String = path
    override fun canWrite(): Boolean = true
    override fun canRead(): Boolean = true
    override fun canExecute(): Boolean = attrs?.let { (it.permissions and 0b001_001_001) != 0 } ?: false
    override fun lastModified(): Long = attrs?.let { it.mTime.toLong() * 1000L } ?: 0L
    override fun isSymlink(): Boolean = attrs?.isLink ?: false

    // ---- suspend members: may hit the network, always serialized via SshConnectionManager ----

    override suspend fun listFiles(): List<FileObject> = SshConnectionManager.withSftp { sftp ->
        sftp.ls(path)
            .filterNot { it.filename == "." || it.filename == ".." }
            .map { entry -> RemoteFileObject(childPath(entry.filename), entry.attrs) }
    }

    override suspend fun getParentFile(): FileObject {
        if (path == "/") return this
        val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
        return RemoteFileObject(parentPath, SshConnectionManager.statOrNull(parentPath))
    }

    override suspend fun exists(): Boolean = ensureAttrs() != null

    override suspend fun createNewFile(): Boolean = try {
        SshConnectionManager.withSftp { sftp -> sftp.put(path).use { } }
        attrs = SshConnectionManager.statOrNull(path)
        true
    } catch (e: Exception) {
        false
    }

    override suspend fun getCanonicalPath(): String = path

    override suspend fun mkdir(): Boolean = try {
        SshConnectionManager.withSftp { sftp -> sftp.mkdir(path) }
        attrs = SshConnectionManager.statOrNull(path)
        true
    } catch (e: Exception) {
        false
    }

    override suspend fun mkdirs(): Boolean {
        val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
        var current = ""
        for (part in parts) {
            current += "/$part"
            if (SshConnectionManager.statOrNull(current) == null) {
                try {
                    SshConnectionManager.withSftp { sftp -> sftp.mkdir(current) }
                } catch (e: Exception) {
                    return false
                }
            }
        }
        attrs = SshConnectionManager.statOrNull(path)
        return true
    }

    override suspend fun writeText(text: String) {
        writeText(text, Charsets.UTF_8)
    }

    override suspend fun writeText(text: String, charset: Charset): Boolean = try {
        SshConnectionManager.withSftp { sftp -> sftp.put(path).use { out -> out.write(text.toByteArray(charset)) } }
        attrs = SshConnectionManager.statOrNull(path)
        true
    } catch (e: Exception) {
        false
    }

    override suspend fun getInputStream(): InputStream = SshConnectionManager.withSftp { sftp ->
        ByteArrayInputStream(sftp.get(path).use { it.readBytes() })
    }

    override suspend fun <R> useInputStream(block: suspend (InputStream) -> R): R {
        val stream = getInputStream()
        return try {
            block(stream)
        } finally {
            runCatching { stream.close() }
        }
    }

    override suspend fun getOutPutStream(append: Boolean): OutputStream {
        val existing = if (append) {
            try {
                SshConnectionManager.withSftp { sftp -> sftp.get(path).use { it.readBytes() } }
            } catch (e: Exception) {
                ByteArray(0)
            }
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
                kotlinx.coroutines.runBlocking {
                    SshConnectionManager.withSftp { sftp -> sftp.put(path).use { out -> out.write(buffer.toByteArray()) } }
                    attrs = SshConnectionManager.statOrNull(path)
                }
            }
        }
    }

    override suspend fun length(): Long = ensureAttrs()?.size ?: 0L

    override suspend fun delete(): Boolean = try {
        val a = ensureAttrs()
        SshConnectionManager.withSftp { sftp -> if (a?.isDir == true) sftp.rmdir(path) else sftp.rm(path) }
        true
    } catch (e: Exception) {
        false
    }

    override suspend fun toUri(): Uri =
        Uri.parse("ssh://${SshConnectionManager.username}@${SshConnectionManager.host}:${SshConnectionManager.port}$path")

    override suspend fun getMimeType(context: Context): String {
        val type = MimeTypeMap.getSingleton().getMimeTypeFromExtension(cachedExtension.lowercase())
        return type ?: "text/plain"
    }

    override suspend fun renameTo(newName: String): Boolean = try {
        val parentPath = path.substringBeforeLast('/').ifEmpty { "/" }
        val newPath = if (parentPath == "/") "/$newName" else "$parentPath/$newName"
        SshConnectionManager.withSftp { sftp -> sftp.rename(path, newPath) }
        true
    } catch (e: Exception) {
        false
    }

    override suspend fun hasChild(name: String): Boolean = SshConnectionManager.statOrNull(childPath(name)) != null

    override suspend fun createChild(createFile: Boolean, name: String): FileObject {
        val target = childPath(name)
        if (createFile) {
            SshConnectionManager.withSftp { sftp -> sftp.put(target).use { } }
        } else {
            SshConnectionManager.withSftp { sftp -> sftp.mkdir(target) }
        }
        return RemoteFileObject(target, SshConnectionManager.statOrNull(target))
    }

    override suspend fun getChildForName(name: String): FileObject {
        val target = childPath(name)
        return RemoteFileObject(target, SshConnectionManager.statOrNull(target))
    }

    override suspend fun readText(): String = readText(Charsets.UTF_8)

    override suspend fun readText(charset: Charset): String = SshConnectionManager.withSftp { sftp ->
        sftp.get(path).use { it.readBytes() }.toString(charset)
    }

    override fun hashCode(): Int = path.hashCode()
    override fun equals(other: Any?): Boolean = other is RemoteFileObject && other.path == path
    override fun toString(): String = "ssh://${SshConnectionManager.host}$path"

    companion object {
        private const val serialVersionUID: Long = 1L
    }
}
