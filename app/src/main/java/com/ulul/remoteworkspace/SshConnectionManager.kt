package com.ulul.remoteworkspace

import com.jcraft.jsch.ChannelExec
import com.jcraft.jsch.ChannelSftp
import com.jcraft.jsch.JSch
import com.jcraft.jsch.Session
import com.jcraft.jsch.SftpATTRS
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream

enum class AuthMethod { PASSWORD, PRIVATE_KEY }

data class RemoteExecResult(val exitCode: Int, val stdout: String, val stderr: String)

/**
 * Holds the SSH/SFTP connection configuration and the single shared session used by
 * [RemoteFileObject]. All SFTP operations are serialized through [withSftp] since a single
 * ChannelSftp instance is not safe for concurrent overlapping commands.
 *
 * Host key checking is disabled for simplicity (this targets a personal/trusted dev server,
 * not a public-facing one) - see README for the tradeoff.
 */
object SshConnectionManager {

    @Volatile var host: String = ""
    @Volatile var port: Int = 22
    @Volatile var username: String = ""
    @Volatile var authMethod: AuthMethod = AuthMethod.PASSWORD
    @Volatile var password: String = ""
    @Volatile var privateKeyPath: String = ""
    @Volatile var privateKeyPassphrase: String = ""
    @Volatile var remoteBasePath: String = "/"

    var onLog: ((String) -> Unit)? = null
    var onStateChanged: ((Boolean) -> Unit)? = null

    /** The remote root currently shown as a sidebar file tree tab, if any. */
    @Volatile var openedRoot: RemoteFileObject? = null

    /** Full message of the last connection failure, for surfacing to the user. */
    @Volatile var lastError: String? = null

    private var session: Session? = null
    private var sftpChannel: ChannelSftp? = null
    private val mutex = Mutex()

    val isConnected: Boolean get() = session?.isConnected == true

    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (session?.isConnected == true) return@withLock true
            try {
                val jsch = JSch()
                if (authMethod == AuthMethod.PRIVATE_KEY) {
                    if (privateKeyPassphrase.isNotEmpty()) {
                        jsch.addIdentity(privateKeyPath, privateKeyPassphrase)
                    } else {
                        jsch.addIdentity(privateKeyPath)
                    }
                }

                val newSession = jsch.getSession(username, host, port)
                if (authMethod == AuthMethod.PASSWORD) {
                    newSession.setPassword(password)
                }
                newSession.setConfig("StrictHostKeyChecking", "no")
                // Only offer the auth method actually configured - offering both can make some
                // servers/JSch combinations fail in confusing ways (e.g. trying
                // keyboard-interactive with no UserInfo callback set).
                newSession.setConfig(
                    "PreferredAuthentications",
                    if (authMethod == AuthMethod.PRIVATE_KEY) "publickey" else "password,keyboard-interactive"
                )
                newSession.connect(15000)

                val channel = newSession.openChannel("sftp") as ChannelSftp
                channel.connect(15000)

                session = newSession
                sftpChannel = channel
                lastError = null
                log("Terhubung ke $username@$host:$port")
                onStateChanged?.invoke(true)
                true
            } catch (e: Exception) {
                val detail = "${e::class.simpleName}: ${e.message}"
                lastError = detail
                log("Gagal terhubung: $detail")
                runCatching { session?.disconnect() }
                session = null
                sftpChannel = null
                false
            }
        }
    }

    suspend fun disconnect() {
        withContext(Dispatchers.IO) {
            mutex.withLock {
                runCatching { sftpChannel?.disconnect() }
                runCatching { session?.disconnect() }
                sftpChannel = null
                session = null
            }
        }
        // If a workspace tab is still open in the sidebar when we disconnect, clean it up too -
        // otherwise it's left pointing at a dead connection.
        openedRoot?.let { root -> com.rk.filetree.removeProject(root, false) }
        openedRoot = null
        onStateChanged?.invoke(false)
        log("Terputus dari server")
    }

    /** Runs [block] against the shared SFTP channel. Serialized + always on Dispatchers.IO. */
    suspend fun <T> withSftp(block: (ChannelSftp) -> T): T = withContext(Dispatchers.IO) {
        mutex.withLock {
            val channel = sftpChannel
            if (channel == null || !channel.isConnected) {
                throw IllegalStateException("Belum terhubung ke server")
            }
            block(channel)
        }
    }

    suspend fun statOrNull(path: String): SftpATTRS? = try {
        withSftp { it.stat(path) }
    } catch (e: Exception) {
        null
    }

    /**
     * Runs [command] on the remote host via a dedicated exec channel (separate from the shared
     * SFTP channel - SSH multiplexes multiple channels over one session just fine).
     * [onOutputChunk] is invoked with each chunk of combined stdout as it streams in.
     */
    suspend fun runCommand(
        command: String,
        timeoutMs: Long = 10 * 60 * 1000L,
        onOutputChunk: ((String) -> Unit)? = null
    ): RemoteExecResult = withContext(Dispatchers.IO) {
        val activeSession = session ?: throw IllegalStateException("Belum terhubung ke server")
        val channel = activeSession.openChannel("exec") as ChannelExec
        channel.setCommand(command)

        val errBuffer = ByteArrayOutputStream()
        channel.setErrStream(errBuffer)

        val stdout = StringBuilder()
        try {
            val input = channel.inputStream
            channel.connect(15000)

            val buffer = ByteArray(8192)
            val deadline = System.currentTimeMillis() + timeoutMs
            while (true) {
                while (input.available() > 0) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    val chunk = String(buffer, 0, read, Charsets.UTF_8)
                    stdout.append(chunk)
                    onOutputChunk?.invoke(chunk)
                }
                if (channel.isClosed && input.available() <= 0) break
                if (System.currentTimeMillis() > deadline) {
                    onOutputChunk?.invoke("\n[Timeout setelah ${timeoutMs / 1000}s, memutus koneksi command]\n")
                    break
                }
                kotlinx.coroutines.delay(50)
            }

            val exitCode = if (channel.isClosed) channel.exitStatus else -1
            RemoteExecResult(exitCode, stdout.toString(), errBuffer.toString(Charsets.UTF_8.name()))
        } finally {
            runCatching { channel.disconnect() }
        }
    }

    private fun log(message: String) {
        onLog?.invoke(message)
    }
}
