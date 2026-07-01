package com.ulul.remoteworkspace

import com.rk.exec.ShellUtils
import com.rk.exec.awaitExit
import com.rk.exec.terminate
import com.rk.exec.ubuntuProcess
import com.rk.file.sandboxHomeDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages a persistent SSH ControlMaster connection.
 *
 * How ControlMaster works:
 * One "master" SSH process opens the TCP connection and creates a Unix domain socket at
 * [controlSocket]. Every subsequent `ssh`/`sftp` command uses `-o ControlPath=<socket>` to
 * multiplex through that ONE existing TCP connection instead of re-negotiating key exchange and
 * auth each time. This is the source of all the "heavy / reconnecting" behavior before - each
 * sftp operation was a full new TCP connection.
 *
 * ControlPersist=120 means the master process stays alive 120 seconds after the last client
 * disconnects, so brief gaps between operations don't tear down the connection.
 */
object ConnectionManager {

    @Volatile var host: String = ""
    @Volatile var port: Int = 22
    @Volatile var username: String = ""
    @Volatile var remoteBasePath: String = "/"
    @Volatile var extraSshArgs: String = ""

    var onLog: ((String) -> Unit)? = null
    var onStateChanged: ((Boolean) -> Unit)? = null

    @Volatile var openedRoot: RemoteFileObject? = null
    @Volatile var lastError: String? = null

    private val mutex = Mutex()

    // The Unix socket file used by ControlMaster. Placed inside sandboxHomeDir so it's inside
    // the Ubuntu proot where SSH can actually create it.
    private val controlSocket: File get() =
        File(sandboxHomeDir(), ".ssh/.xed_remote_ctl_${host}_${port}_${username}")

    val isConnected: Boolean get() = controlSocket.exists()

    // --------------- connection lifecycle ---------------

    /**
     * Starts the ControlMaster process (if not already running) and verifies with a test
     * `ssh -O check`.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (checkAlive()) return@withLock true

            // Make sure .ssh directory exists in Ubuntu home.
            ShellUtils.runUbuntu("-c", "mkdir -p /home/.ssh && chmod 700 /home/.ssh")

            // Start the ControlMaster in background. -N = no command, -f = go to background
            // after auth. -o ControlPersist=120 = keep master alive 120s after last client exits.
            val masterArgs = buildBaseArgs(includePath = false) + listOf(
                "-M",
                "-o", "ControlPersist=120",
                "-N",
                "-f",
                targetArg()
            )
            val masterResult = ubuntu(*masterArgs.toTypedArray())

            if (masterResult.exitCode != 0) {
                lastError = masterResult.error.ifBlank { "exit code ${masterResult.exitCode}" }
                log("Gagal memulai koneksi: $lastError")
                return@withLock false
            }

            // Verify it's actually listening.
            if (!checkAlive()) {
                lastError = "ControlMaster started but socket not created at ${controlSocket.absolutePath}"
                log(lastError!!)
                return@withLock false
            }

            lastError = null
            log("Terhubung ke ${targetArg()}:$port (ControlMaster aktif)")
            onStateChanged?.invoke(true)
            true
        }
    }

    suspend fun disconnect() = withContext(Dispatchers.IO) {
        if (isConnected) {
            // Send -O exit to cleanly shut down the ControlMaster process.
            ubuntu(*controlArgs("exit").toTypedArray())
        }
        openedRoot?.let { root ->
            runCatching { com.rk.filetree.removeProject(root, false) }
        }
        openedRoot = null
        onStateChanged?.invoke(false)
        log("Terputus dari server")
    }

    /** Checks if the ControlMaster socket is alive without making a new TCP connection. */
    suspend fun checkAlive(): Boolean = withContext(Dispatchers.IO) {
        if (!controlSocket.exists()) return@withContext false
        val result = ubuntu(*controlArgs("check").toTypedArray())
        result.exitCode == 0
    }

    // --------------- command execution ---------------

    /**
     * Runs [command] on the remote host through the persistent control socket.
     * Each call is a new ssh *channel* over the SAME TCP connection - fast, no re-auth.
     */
    suspend fun runCommand(
        command: String,
        timeoutMs: Long = 10 * 60 * 1000L,
        onOutputChunk: ((String) -> Unit)? = null
    ): ShellUtils.Result = withContext(Dispatchers.IO) {
        ensureConnected()
        val args = (buildBaseArgs() + listOf(targetArg(), command)).toTypedArray()
        val process = ubuntuProcess(command = args.toList())

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val outThread = Thread {
            runCatching {
                process.inputStream.bufferedReader().forEachLine { line ->
                    val chunk = "$line\n"
                    stdout.append(chunk)
                    onOutputChunk?.invoke(chunk)
                }
            }
        }
        val errThread = Thread {
            runCatching { process.errorStream.bufferedReader().forEachLine { stderr.appendLine(it) } }
        }

        outThread.start()
        errThread.start()

        val deadline = System.currentTimeMillis() + timeoutMs
        var timedOut = false
        try {
            while (outThread.isAlive || errThread.isAlive) {
                Thread.sleep(50)
                if (System.currentTimeMillis() > deadline) {
                    timedOut = true
                    onOutputChunk?.invoke("\n[Timeout setelah ${timeoutMs / 1000}s]\n")
                    break
                }
            }
        } finally {
            process.terminate()
        }
        outThread.join(2000)
        errThread.join(2000)

        ShellUtils.Result(
            exitCode = runCatching { process.exitValue() }.getOrDefault(-1),
            output = stdout.toString().trim(),
            error = stderr.toString().trim(),
            timedOut = timedOut
        )
    }

    /** Runs a single sftp batch over the control socket. Fast — no re-auth. */
    suspend fun sftpBatch(vararg lines: String): ShellUtils.Result = withContext(Dispatchers.IO) {
        ensureConnected()
        val args = mutableListOf(
            "sftp",
            "-b", "-",
            "-o", "StrictHostKeyChecking=no",
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=15",
            "-P", port.toString(),
            "-o", "ControlPath=${controlSocketInsideUbuntu()}"
        )
        extraSshArgs.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.forEach { args.add(it) }
        args.add(targetArg())

        val process = ubuntuProcess(command = args)
        try {
            process.outputStream.bufferedWriter().use { w ->
                lines.forEach { w.write(it + "\n") }
                w.write("bye\n")
            }
            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exit = process.awaitExit()
            ShellUtils.Result(exit, stdout.trim(), stderr.trim(), false)
        } finally {
            process.terminate()
        }
    }

    /** Stat a remote path. Returns null if not found. */
    suspend fun statOrNull(path: String): RemoteFileStat? = withContext(Dispatchers.IO) {
        val result = runCommand("stat -c '%F|%s|%Y|%a' -- ${shellEscape(path)} 2>/dev/null")
        if (result.exitCode != 0 || result.output.isBlank()) return@withContext null
        val parts = result.output.trim().split("|")
        if (parts.size < 4) return@withContext null
        RemoteFileStat(
            isDir = parts[0].contains("directory"),
            isLink = parts[0].contains("link"),
            size = parts[1].toLongOrNull() ?: 0L,
            mtime = parts[2].toLongOrNull() ?: 0L,
            permissions = parts[3].trim()
        )
    }

    /** Lists a remote directory. Returns null on error. */
    suspend fun listDir(path: String): List<Pair<String, RemoteFileStat>>? =
        withContext(Dispatchers.IO) {
            val result = runCommand(
                "stat -c '%n|%F|%s|%Y|%a' -- ${shellEscape(path)}/* ${shellEscape(path)}/.[!.]* 2>/dev/null"
            )
            if (result.exitCode != 0 && result.output.isBlank()) return@withContext null
            result.output.lines()
                .filter { it.contains("|") }
                .mapNotNull { line ->
                    val p = line.split("|")
                    if (p.size < 5) return@mapNotNull null
                    val name = File(p[0].trim()).name
                    if (name == "." || name == "..") return@mapNotNull null
                    name to RemoteFileStat(
                        isDir = p[1].contains("directory"),
                        isLink = p[1].contains("link"),
                        size = p[2].toLongOrNull() ?: 0L,
                        mtime = p[3].toLongOrNull() ?: 0L,
                        permissions = p[4].trim()
                    )
                }
        }

    /**
     * Downloads a remote file to a local temp file.
     * Caller is responsible for deleting it after use.
     */
    suspend fun downloadToTemp(remotePath: String): File? = withContext(Dispatchers.IO) {
        val tempName = ".xed_dl_${System.currentTimeMillis()}_${File(remotePath).name}"
        val localUbuntuPath = "/home/$tempName"
        val localAndroidPath = File(sandboxHomeDir(), tempName)

        val result = sftpBatch("get ${shellEscape(remotePath)} $localUbuntuPath")
        if (result.exitCode != 0 || !localAndroidPath.exists()) {
            log("Download gagal (${remotePath}): ${result.error}")
            return@withContext null
        }
        localAndroidPath
    }

    /** Uploads a local file (Android path) to a remote path. */
    suspend fun uploadFromLocal(localFile: File, remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            val localUbuntu = when {
                localFile.absolutePath.startsWith(sandboxHomeDir().absolutePath) ->
                    "/home" + localFile.absolutePath.removePrefix(sandboxHomeDir().absolutePath)
                else -> localFile.absolutePath
            }
            val result = sftpBatch("put $localUbuntu ${shellEscape(remotePath)}")
            if (result.exitCode != 0) {
                log("Upload gagal (${remotePath}): ${result.error}")
                return@withContext false
            }
            true
        }

    suspend fun mkdir(path: String): Boolean = sftpBatch("mkdir ${shellEscape(path)}").exitCode == 0

    suspend fun rm(path: String, isDir: Boolean): Boolean {
        val cmd = if (isDir) "rmdir ${shellEscape(path)}" else "rm ${shellEscape(path)}"
        return sftpBatch(cmd).exitCode == 0
    }

    suspend fun rename(from: String, to: String): Boolean =
        sftpBatch("rename ${shellEscape(from)} ${shellEscape(to)}").exitCode == 0

    // --------------- helpers ---------------

    /** Ensures connected, attempting reconnect once if not. */
    private suspend fun ensureConnected() {
        if (!isConnected || !checkAlive()) {
            log("Koneksi terputus, mencoba reconnect...")
            connect()
        }
    }

    private fun buildBaseArgs(includePath: Boolean = true): List<String> {
        val args = mutableListOf(
            "ssh",
            "-o", "StrictHostKeyChecking=no",
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=15",
            "-p", port.toString()
        )
        if (includePath) {
            args += listOf("-o", "ControlPath=${controlSocketInsideUbuntu()}")
        } else {
            args += listOf("-o", "ControlPath=${controlSocketInsideUbuntu()}")
        }
        extraSshArgs.trim().split("\\s+".toRegex()).filter { it.isNotBlank() }.forEach { args.add(it) }
        return args
    }

    private fun controlArgs(operation: String): List<String> = listOf(
        "ssh",
        "-o", "ControlPath=${controlSocketInsideUbuntu()}",
        "-O", operation,
        targetArg()
    )

    /**
     * The control socket path as seen INSIDE Ubuntu proot.
     * sandboxHomeDir() is mounted at /home inside proot.
     */
    private fun controlSocketInsideUbuntu(): String {
        val relative = controlSocket.absolutePath.removePrefix(sandboxHomeDir().absolutePath)
        return "/home$relative"
    }

    private fun targetArg(): String = if (username.isNotBlank()) "$username@$host" else host

    private suspend fun ubuntu(vararg cmd: String): ShellUtils.Result =
        ShellUtils.runUbuntu(command = cmd)

    fun shellEscape(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun log(msg: String) { onLog?.invoke(msg) }
}
