package com.ulul.remoteworkspace

import com.rk.exec.ShellUtils
import com.rk.exec.awaitExit
import com.rk.exec.terminate
import com.rk.exec.ubuntuProcess
import com.rk.file.sandboxHomeDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/**
 * Manages the connection to the remote server by delegating ALL SSH operations to the
 * native `ssh`/`sftp`/`scp` binaries inside Xed-Editor's own Ubuntu environment (proot).
 *
 * This is the correct architecture for this extension:
 * - Keys, known_hosts, ~/.ssh/config are in the Ubuntu env - the user already configured them
 *   via the built-in terminal, and they just work without any extra setup here.
 * - We don't need to map Android filesystem paths for keys (the previous approach using JSch
 *   required an Android-side path which is inaccessible from within the Ubuntu sandbox).
 * - auth (key type, agent, multi-factor, jump hosts) is handled by OpenSSH itself, which is
 *   far more capable and compatible than JSch.
 *
 * The "connection" here is stateless at the manager level - each SFTP operation spawns a
 * short-lived `sftp -b -` batch subprocess piped through stdin, and the remote command runner
 * spawns a `ssh` subprocess. This is simpler and more reliable than trying to keep a long-lived
 * sftp session alive across the mutex and Android process lifecycle events.
 */
object SshConnectionManager {

    @Volatile var host: String = ""
    @Volatile var port: Int = 22
    @Volatile var username: String = ""
    @Volatile var remoteBasePath: String = "/"
    @Volatile var extraSshArgs: String = ""   // e.g. "-i /home/.ssh/my_key" or "-J jumphost"

    var onLog: ((String) -> Unit)? = null

    /** Set to true once we have verified connectivity (a test ssh command succeeded). */
    @Volatile var isConnected: Boolean = false

    @Volatile var openedRoot: RemoteFileObject? = null

    /** Full message of the last connection failure, for surfacing to the user. */
    @Volatile var lastError: String? = null

    // --------------- core helpers ---------------

    /** Base SSH args shared by every command (no tty, batch mode, port). */
    private fun baseArgs(): List<String> {
        val args = mutableListOf(
            "ssh",
            "-o", "StrictHostKeyChecking=no",
            "-o", "BatchMode=yes",
            "-o", "ConnectTimeout=15",
            "-p", port.toString()
        )
        extraSshArgs.trim().split("\\s+".toRegex())
            .filter { it.isNotBlank() }
            .forEach { args.add(it) }
        return args
    }

    private fun targetArg(): String = if (username.isNotBlank()) "$username@$host" else host

    /** Runs a command inside Xed-Editor's Ubuntu proot environment. */
    private suspend fun ubuntu(vararg cmd: String): ShellUtils.Result =
        ShellUtils.runUbuntu(command = cmd)

    // --------------- public API ---------------

    /**
     * Verifies the connection by running `ssh … true` in the Ubuntu environment.
     * Uses OpenSSH from the Ubuntu environment, so ~/.ssh/config and keys are respected
     * automatically — no path mapping needed.
     */
    suspend fun connect(): Boolean = withContext(Dispatchers.IO) {
        val cmd = (baseArgs() + listOf(targetArg(), "true")).toTypedArray()
        val result = ubuntu(*cmd)
        return@withContext if (result.exitCode == 0) {
            isConnected = true
            lastError = null
            log("Terhubung ke ${targetArg()}:$port")
            true
        } else {
            isConnected = false
            val detail = result.error.ifBlank { "exit code ${result.exitCode}" }
            lastError = detail
            log("Gagal terhubung: $detail")
            false
        }
    }

    fun disconnect() {
        isConnected = false
        openedRoot?.let { root -> runCatching { com.rk.filetree.removeProject(root, false) } }
        openedRoot = null
        onStateChanged?.invoke(false)
        log("Terputus dari server")
    }

    var onStateChanged: ((Boolean) -> Unit)? = null

    /**
     * Runs a single SFTP batch command and returns stdout.
     * Each call spawns a fresh `sftp -b -` subprocess — stateless, safe to call concurrently.
     */
    suspend fun sftpBatch(vararg batchLines: String): ShellUtils.Result =
        withContext(Dispatchers.IO) {
            val args = mutableListOf(
                "sftp",
                "-b", "-",
                "-o", "StrictHostKeyChecking=no",
                "-o", "BatchMode=yes",
                "-o", "ConnectTimeout=15",
                "-P", port.toString()
            )
            extraSshArgs.trim().split("\\s+".toRegex())
                .filter { it.isNotBlank() }
                .forEach { args.add(it) }
            args.add(targetArg())

            val process = ubuntuProcess(command = args)
            try {
                process.outputStream.bufferedWriter().use { writer ->
                    batchLines.forEach { writer.write(it + "\n") }
                    writer.write("bye\n")
                }
                val stdout = process.inputStream.bufferedReader().readText()
                val stderr = process.errorStream.bufferedReader().readText()
                val exit = process.awaitExit()
                ShellUtils.Result(exit, stdout.trim(), stderr.trim(), false)
            } finally {
                process.terminate()
            }
        }

    /**
     * Runs [command] on the remote host via `ssh … CMD` in the Ubuntu environment,
     * streaming output chunks to [onOutputChunk].
     */
    suspend fun runCommand(
        command: String,
        timeoutMs: Long = 10 * 60 * 1000L,
        onOutputChunk: ((String) -> Unit)? = null
    ): ShellUtils.Result = withContext(Dispatchers.IO) {
        val args = (baseArgs() + listOf(targetArg(), command)).toTypedArray()
        val process = ubuntuProcess(command = args.toList())

        val stdout = StringBuilder()
        val stderr = StringBuilder()

        val outThread = Thread {
            runCatching {
                BufferedReader(InputStreamReader(process.inputStream)).forEachLine { line ->
                    val chunk = line + "\n"
                    stdout.append(chunk)
                    onOutputChunk?.invoke(chunk)
                }
            }
        }
        val errThread = Thread {
            runCatching {
                process.errorStream.bufferedReader().forEachLine { line ->
                    stderr.appendLine(line)
                }
            }
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

    /** Stat a remote path via `ssh … ls -ld PATH`. Returns null if path doesn't exist. */
    suspend fun statOrNull(path: String): RemoteFileStat? = withContext(Dispatchers.IO) {
        val cmd = (baseArgs() + listOf(
            targetArg(),
            "stat -c '%F|%s|%Y|%a' -- ${shellEscape(path)} 2>/dev/null"
        )).toTypedArray()
        val result = ubuntu(*cmd)
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

    /** Lists a remote directory via `ssh … ls -la PATH`. Returns null on error. */
    suspend fun listDir(path: String): List<Pair<String, RemoteFileStat>>? =
        withContext(Dispatchers.IO) {
            val cmd = (baseArgs() + listOf(
                targetArg(),
                "stat -c '%n|%F|%s|%Y|%a' -- ${shellEscape(path)}/* ${shellEscape(path)}/.[!.]* 2>/dev/null"
            )).toTypedArray()
            val result = ubuntu(*cmd)
            if (result.exitCode != 0 && result.output.isBlank()) return@withContext null

            result.output.lines()
                .filter { it.contains("|") }
                .mapNotNull { line ->
                    val parts = line.split("|")
                    if (parts.size < 5) return@mapNotNull null
                    val name = File(parts[0].trim()).name
                    if (name == "." || name == "..") return@mapNotNull null
                    name to RemoteFileStat(
                        isDir = parts[1].contains("directory"),
                        isLink = parts[1].contains("link"),
                        size = parts[2].toLongOrNull() ?: 0L,
                        mtime = parts[3].toLongOrNull() ?: 0L,
                        permissions = parts[4].trim()
                    )
                }
        }

    /** Downloads a remote file to a local temp file (in Ubuntu home), returns local path. */
    suspend fun downloadToTemp(remotePath: String): File? = withContext(Dispatchers.IO) {
        val tempName = ".xed_remote_tmp_${System.currentTimeMillis()}_${File(remotePath).name}"
        val localPath = "/home/$tempName"
        val androidLocalPath = File(sandboxHomeDir(), tempName)

        val result = sftpBatch("get ${shellEscape(remotePath)} $localPath")
        if (result.exitCode != 0) {
            log("Download gagal: ${result.error}")
            return@withContext null
        }
        if (!androidLocalPath.exists()) {
            log("Download selesai tapi file tidak ditemukan: ${androidLocalPath.absolutePath}")
            return@withContext null
        }
        androidLocalPath
    }

    /** Uploads a local file (Android path) to a remote path. */
    suspend fun uploadFromLocal(localFile: File, remotePath: String): Boolean =
        withContext(Dispatchers.IO) {
            // Map Android path to the path visible inside Ubuntu proot.
            // sandboxHomeDir() is mounted at /home, so files there map cleanly.
            // Files in /storage/emulated/0 are bind-mounted at the same path.
            val localInsideUbuntu = when {
                localFile.absolutePath.startsWith(sandboxHomeDir().absolutePath) ->
                    "/home" + localFile.absolutePath.removePrefix(sandboxHomeDir().absolutePath)
                else -> localFile.absolutePath  // /storage/... paths are bind-mounted as-is
            }
            val result = sftpBatch("put $localInsideUbuntu ${shellEscape(remotePath)}")
            if (result.exitCode != 0) {
                log("Upload gagal: ${result.error}")
                return@withContext false
            }
            true
        }

    suspend fun mkdir(remotePath: String): Boolean {
        val result = sftpBatch("mkdir ${shellEscape(remotePath)}")
        return result.exitCode == 0
    }

    suspend fun rm(remotePath: String, isDir: Boolean): Boolean {
        val cmd = if (isDir) "rmdir ${shellEscape(remotePath)}" else "rm ${shellEscape(remotePath)}"
        val result = sftpBatch(cmd)
        return result.exitCode == 0
    }

    suspend fun rename(from: String, to: String): Boolean {
        val result = sftpBatch("rename ${shellEscape(from)} ${shellEscape(to)}")
        return result.exitCode == 0
    }

    // ---- helpers ----

    private fun shellEscape(s: String): String = "'${s.replace("'", "'\\''")}'"

    private fun log(msg: String) { onLog?.invoke(msg) }
}

data class RemoteFileStat(
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val mtime: Long,
    val permissions: String
)
