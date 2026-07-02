package com.ulul.remoteworkspace

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.Keep
import com.rk.activities.main.MainActivity
import com.rk.commands.ActionContext
import com.rk.commands.CommandContext
import com.rk.commands.CommandProvider
import com.rk.commands.GlobalCommand
import com.rk.exec.TerminalCommand
import com.rk.exec.launchTerminal
import com.rk.extension.Extension
import com.rk.extension.ExtensionAPI
import com.rk.filetree.addProject
import com.rk.filetree.removeProject
import com.rk.icons.Icon
import com.rk.utils.application
import com.rk.utils.toast
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@Keep
@Suppress("unused")
class Main : ExtensionAPI() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandContext = CommandContext { MainActivity.instance!!.viewModel }

    private fun prefs() =
        application!!.getSharedPreferences("com.ulul.remoteworkspace_prefs", Context.MODE_PRIVATE)

    // ---- Commands ----

    private val connectCommand = object : GlobalCommand(commandContext) {
        override val id = "com.ulul.remoteworkspace.toggle"
        override fun getLabel() = if (ConnectionManager.isConnected) "Putuskan Remote Workspace" else "Hubungkan Remote Workspace"
        override fun getIcon(): Icon = Icon.TextIcon("SSH")
        override fun action(actionContext: ActionContext) {
            scope.launch {
                if (ConnectionManager.isConnected) {
                    ConnectionManager.disconnect()
                    toast("Terputus dari server")
                } else {
                    val ok = ConnectionManager.connect()
                    if (ok) toast("Terhubung ke ${ConnectionManager.host}")
                    else showErrorDialog(actionContext.currentActivity)
                }
            }
        }
    }

    private val configureCommand = object : GlobalCommand(commandContext) {
        override val id = "com.ulul.remoteworkspace.configure"
        override fun getLabel() = "Konfigurasi Remote Workspace"
        override fun getIcon(): Icon = Icon.TextIcon("CFG")
        override fun action(actionContext: ActionContext) = showConfigDialog(actionContext.currentActivity)
    }

    private val workspaceCommand = object : GlobalCommand(commandContext) {
        override val id = "com.ulul.remoteworkspace.toggleworkspace"
        override fun getLabel() = if (ConnectionManager.openedRoot != null) "Tutup Workspace Remote" else "Buka Workspace Remote"
        override fun getIcon(): Icon = Icon.TextIcon("DIR")
        override fun action(actionContext: ActionContext) {
            val existing = ConnectionManager.openedRoot
            if (existing != null) {
                removeProject(existing, false)
                ConnectionManager.openedRoot = null
                toast("Workspace ditutup")
                return
            }
            scope.launch {
                if (!ConnectionManager.isConnected) {
                    val ok = ConnectionManager.connect()
                    if (!ok) { showErrorDialog(actionContext.currentActivity); return@launch }
                }
                val stat = ConnectionManager.statOrNull(ConnectionManager.remoteBasePath)
                if (stat == null) { toast("Folder tidak ditemukan: ${ConnectionManager.remoteBasePath}"); return@launch }
                val root = RemoteFileObject(ConnectionManager.remoteBasePath, stat)
                val activity = MainActivity.instance ?: run { toast("Tidak bisa mengakses jendela utama"); return@launch }
                addProject(root, false)
                ConnectionManager.openedRoot = root
                toast("Workspace dibuka di sidebar")
            }
        }
    }

    /**
     * Opens a REAL remote terminal in Xed-Editor's own terminal emulator.
     *
     * How it works:
     * We use Xed-Editor's own [pendingCommand]/[launchTerminal] mechanism (the same one used
     * internally to run code files in the terminal). We set [pendingCommand] to a
     * [TerminalCommand] that runs `ssh -t user@host` with ControlPath pointing to the active
     * ControlMaster socket - so:
     * 1. The terminal opens inside Xed-Editor's terminal emulator (full PTY, color, interactive).
     * 2. The SSH connection multiplexes through the existing ControlMaster socket (no re-auth,
     *    instant connect).
     * 3. The user gets a real shell ON the remote server - cd, run builds, git, etc, all remote.
     * 4. sandbox=true: MkSession wraps the command as [sandbox.sh, "ssh", ...args], so OpenSSH
     *    from the Ubuntu proot is used — where it exists and has access to ~/.ssh/config & keys.
     *
     * This is the closest equivalent to VS Code's "integrated remote terminal".
     */
    private val remoteTerminalCommand = object : GlobalCommand(commandContext) {
        override val id = "com.ulul.remoteworkspace.terminal"
        override fun getLabel() = "Terminal Remote (${ConnectionManager.host.ifBlank { "belum dikonfigurasi" }})"
        override fun getIcon(): Icon = Icon.TextIcon(">_")
        override fun action(actionContext: ActionContext) {
            val activity = actionContext.currentActivity
            if (ConnectionManager.host.isBlank()) {
                toast("Konfigurasi host dulu lewat \"Konfigurasi Remote Workspace\"")
                return
            }
            scope.launch {
                // Ensure ControlMaster is running - terminal launch will be instant through it.
                if (!ConnectionManager.isConnected) {
                    val ok = ConnectionManager.connect()
                    if (!ok) { showErrorDialog(activity); return@launch }
                }

                // Build the SSH command to run inside the terminal.
                // -t  = force pseudo-TTY (required for interactive shell).
                // ControlPath = multiplex through the existing ControlMaster (instant open).
                val controlSocketArg = "-o ControlPath=${getControlSocketUbuntuPath()}"
                val target = buildTarget()
                val workDir = ConnectionManager.remoteBasePath

                // Extra args from config (e.g. -i /home/.ssh/custom_key).
                val extraArgs = ConnectionManager.extraSshArgs.trim()
                    .split("\\s+".toRegex()).filter { it.isNotBlank() }

                val sshArgs = buildList {
                    add("-t")
                    add("-o"); add("StrictHostKeyChecking=no")
                    add("-o"); add("ConnectTimeout=15")
                    add("-p"); add(ConnectionManager.port.toString())
                    add(controlSocketArg)
                    addAll(extraArgs)
                    add(target)
                    // Launch a shell that starts in the remote workspace directory.
                    add("cd ${shellEsc(workDir)} && exec \$SHELL -l")
                }

                activity.runOnUiThread {
                    launchTerminal(
                        activity,   // Activity (not Context - verified from sdk.jar)
                        TerminalCommand(
                            // sandbox=true: MkSession builds [sandbox.sh, "ssh", ...args]
                            // so OpenSSH inside the Ubuntu proot is used - where `ssh` exists,
                            // keys/config are available, and the ControlPath socket is reachable.
                            // sandbox=false would exec "ssh" directly on Android where it doesn't exist
                            // → the "exec(ssh): No such file or directory" error we had before.
                            sandbox = true,
                            exe = "ssh",
                            args = sshArgs.toTypedArray(),
                            id = "remote-workspace-terminal-${System.currentTimeMillis()}",
                            terminatePreviousSession = false,
                            workingDir = null
                        )
                    )
                }
            }
        }
    }

    private val clearCacheCommand = object : GlobalCommand(commandContext) {
        override val id = "com.ulul.remoteworkspace.clearcache"
        override fun getLabel() = "Hapus Cache Remote Workspace (${formatBytes(FileCache.currentBytes)})"
        override fun getIcon(): Icon = Icon.TextIcon("CLR")
        override fun action(actionContext: ActionContext) {
            scope.launch {
                FileCache.clear()
                toast("Cache dikosongkan")
            }
        }
    }

    // ---- Dialogs ----

    private fun showErrorDialog(activity: Activity) {
        val msg = ConnectionManager.lastError ?: "Tidak ada detail error.\n\nPastikan SSH bisa connect dari terminal Xed-Editor:\nssh ${ConnectionManager.username}@${ConnectionManager.host}"
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Gagal terhubung ke ${ConnectionManager.host}")
                .setMessage(msg)
                .setPositiveButton("Salin") { _, _ ->
                    val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("error", msg))
                    toast("Disalin")
                }
                .setNegativeButton("Tutup", null)
                .show()
        }
    }

    private fun showConfigDialog(activity: Activity) {
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(0))
        }
        fun lbl(t: String) = layout.addView(TextView(activity).apply { text = t; setPadding(0, dp(12), 0, dp(2)) })
        fun fld(hint: String, init: String): EditText {
            val e = EditText(activity).apply { this.hint = hint; setText(init) }
            layout.addView(e); return e
        }

        lbl("Host")
        val hostField = fld("192.168.1.10 atau server.contoh.com", ConnectionManager.host)
        lbl("Port")
        val portField = fld("22", ConnectionManager.port.toString())
        lbl("Username")
        val userField = fld("nama_user", ConnectionManager.username)
        lbl("Folder remote (workspace root)")
        val remoteField = fld("/home/user/project", ConnectionManager.remoteBasePath)
        lbl("SSH args tambahan (opsional)")
        val argsField = fld("-i /home/.ssh/custom_key", ConnectionManager.extraSshArgs)

        layout.addView(TextView(activity).apply {
            text = "SSH key & konfigurasi dibaca dari ~/.ssh/ di Ubuntu terminal Xed-Editor secara otomatis."
            setPadding(0, dp(12), 0, dp(4))
            alpha = 0.7f
        })

        AlertDialog.Builder(activity)
            .setTitle("Konfigurasi Remote Workspace")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Simpan & Tes Koneksi") { _, _ ->
                // Disconnect first if config is changing.
                if (ConnectionManager.isConnected) {
                    scope.launch { ConnectionManager.disconnect() }
                }
                ConnectionManager.host = hostField.text.toString().trim()
                ConnectionManager.port = portField.text.toString().toIntOrNull() ?: 22
                ConnectionManager.username = userField.text.toString().trim()
                ConnectionManager.remoteBasePath = remoteField.text.toString().trim().ifEmpty { "/" }
                ConnectionManager.extraSshArgs = argsField.text.toString().trim()
                prefs().edit()
                    .putString(KEY_HOST, ConnectionManager.host)
                    .putInt(KEY_PORT, ConnectionManager.port)
                    .putString(KEY_USER, ConnectionManager.username)
                    .putString(KEY_REMOTE_PATH, ConnectionManager.remoteBasePath)
                    .putString(KEY_EXTRA_ARGS, ConnectionManager.extraSshArgs)
                    .apply()
                scope.launch {
                    val ok = ConnectionManager.connect()
                    if (ok) toast("Terhubung ke ${ConnectionManager.host}")
                    else showErrorDialog(activity)
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    // ---- Lifecycle ----

    override fun onExtensionLoaded(extension: Extension) {
        val p = prefs()
        ConnectionManager.host = p.getString(KEY_HOST, "") ?: ""
        ConnectionManager.port = p.getInt(KEY_PORT, 22)
        ConnectionManager.username = p.getString(KEY_USER, "") ?: ""
        ConnectionManager.remoteBasePath = p.getString(KEY_REMOTE_PATH, "/") ?: "/"
        ConnectionManager.extraSshArgs = p.getString(KEY_EXTRA_ARGS, "") ?: ""

        // FileCache is lazy - no disk scan on startup. It initialises on first cache operation
        // (first time a remote file is read after connecting). This keeps extension load time
        // near-zero and avoids any lag on Xed-Editor startup.

        CommandProvider.registerCommand(connectCommand)
        CommandProvider.registerCommand(configureCommand)
        CommandProvider.registerCommand(workspaceCommand)
        CommandProvider.registerCommand(remoteTerminalCommand)
        CommandProvider.registerCommand(clearCacheCommand)
    }

    override fun onUninstalled(extension: Extension) {
        scope.launch {
            FileCache.clear()
            ConnectionManager.disconnect()
        }
        CommandProvider.unregisterCommand(connectCommand)
        CommandProvider.unregisterCommand(configureCommand)
        CommandProvider.unregisterCommand(workspaceCommand)
        CommandProvider.unregisterCommand(remoteTerminalCommand)
        CommandProvider.unregisterCommand(clearCacheCommand)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityDestroyed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityStarted(activity: Activity) {}

    override fun onActivityStopped(activity: Activity) {
        // Kill the ControlMaster background process when the app goes to background.
        // Without this, `ssh -M -f -N` keeps running in the proot environment between sessions.
        // On next app open: the socket still exists, isConnected returns true, the proot needs
        // to manage the zombie master process - all of this adds lag at startup.
        // With this: clean slate every time the app is opened. Connect is explicit (Command
        // Palette), fast (ControlMaster starts fresh), and doesn't surprise the user with a
        // "still connected" state from a previous session that may have been hours ago.
        if (ConnectionManager.isConnected) {
            scope.launch { ConnectionManager.disconnect() }
        }
    }

    // ---- Helpers ----

    private fun getControlSocketUbuntuPath(): String {
        // Mirrors ConnectionManager.controlSocketInsideUbuntu() without exposing internal state.
        val host = ConnectionManager.host
        val port = ConnectionManager.port
        val user = ConnectionManager.username
        return "/home/.ssh/.xed_remote_ctl_${host}_${port}_${user}"
    }

    private fun buildTarget(): String {
        val u = ConnectionManager.username
        val h = ConnectionManager.host
        return if (u.isNotBlank()) "$u@$h" else h
    }

    private fun shellEsc(s: String) = "'${s.replace("'", "'\\''")}'"
    private fun formatBytes(b: Long): String = when {
        b < 1024 -> "${b}B"
        b < 1024 * 1024 -> "${b / 1024}KB"
        else -> "${"%.1f".format(b / (1024.0 * 1024.0))}MB"
    }

    companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USER = "username"
        const val KEY_REMOTE_PATH = "remote_path"
        const val KEY_EXTRA_ARGS = "extra_ssh_args"
    }
}
