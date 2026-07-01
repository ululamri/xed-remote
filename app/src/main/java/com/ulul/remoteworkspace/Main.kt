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

/**
 * SSH/SFTP is now delegated entirely to Xed-Editor's own Ubuntu proot environment - the user's
 * ~/.ssh/config, keys, and known_hosts are read automatically by OpenSSH, exactly as they are in
 * the built-in terminal. No JSch, no Android-side key path mapping.
 */
@Keep
@Suppress("unused")
class Main : ExtensionAPI() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandContext = CommandContext { MainActivity.instance!!.viewModel }

    private fun prefs() =
        application!!.getSharedPreferences("com.ulul.remoteworkspace_prefs", Context.MODE_PRIVATE)

    private val toggleCommand = object : GlobalCommand(commandContext) {
        override val id = "com.ulul.remoteworkspace.toggle"
        override fun getLabel() = if (SshConnectionManager.isConnected) "Putuskan Remote Workspace" else "Hubungkan Remote Workspace"
        override fun getIcon(): Icon = Icon.TextIcon("SSH")
        override fun action(actionContext: ActionContext) {
            scope.launch {
                if (SshConnectionManager.isConnected) {
                    SshConnectionManager.disconnect()
                    toast("Terputus dari server")
                } else {
                    val ok = SshConnectionManager.connect()
                    if (ok) {
                        toast("Terhubung ke ${SshConnectionManager.host}")
                    } else {
                        showConnectErrorDialog(actionContext.currentActivity)
                    }
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

    private val workspaceToggleCommand = object : GlobalCommand(commandContext) {
        override val id = "com.ulul.remoteworkspace.toggleworkspace"
        override fun getLabel() = if (SshConnectionManager.openedRoot != null) "Tutup Workspace Remote" else "Buka Workspace Remote"
        override fun getIcon(): Icon = Icon.TextIcon("DIR")

        override fun action(actionContext: ActionContext) {
            val existing = SshConnectionManager.openedRoot
            if (existing != null) {
                removeProject(existing, false)
                SshConnectionManager.openedRoot = null
                toast("Workspace ditutup")
                return
            }
            scope.launch {
                if (!SshConnectionManager.isConnected) {
                    val ok = SshConnectionManager.connect()
                    if (!ok) {
                        showConnectErrorDialog(actionContext.currentActivity)
                        return@launch
                    }
                }
                val stat = SshConnectionManager.statOrNull(SshConnectionManager.remoteBasePath)
                if (stat == null) {
                    toast("Folder tidak ditemukan: ${SshConnectionManager.remoteBasePath}")
                    return@launch
                }
                val root = RemoteFileObject(SshConnectionManager.remoteBasePath, stat)
                val activity = MainActivity.instance ?: run {
                    toast("Tidak bisa mengakses jendela utama")
                    return@launch
                }
                addProject(root, false)
                SshConnectionManager.openedRoot = root
                toast("Workspace dibuka di sidebar")
            }
        }
    }

    private fun showConnectErrorDialog(activity: Activity) {
        val message = SshConnectionManager.lastError
            ?: "Tidak diketahui.\n\nPastikan SSH sudah bisa connect dari terminal Xed-Editor:\nssh ${SshConnectionManager.username}@${SshConnectionManager.host}"
        activity.runOnUiThread {
            AlertDialog.Builder(activity)
                .setTitle("Gagal terhubung ke ${SshConnectionManager.host}")
                .setMessage(message)
                .setPositiveButton("Salin") { _, _ ->
                    val cb = activity.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
                    cb.setPrimaryClip(android.content.ClipData.newPlainText("error", message))
                    toast("Disalin ke clipboard")
                }
                .setNegativeButton("Tutup", null)
                .show()
        }
    }

    private fun showConfigDialog(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(0))
        }

        fun label(text: String) = layout.addView(TextView(activity).apply {
            this.text = text
            setPadding(0, dp(12), 0, dp(2))
        })

        fun field(hint: String, initial: String): EditText {
            val et = EditText(activity).apply {
                this.hint = hint
                setText(initial)
            }
            layout.addView(et)
            return et
        }

        label("Host")
        val hostField = field("contoh: 192.168.1.10", SshConnectionManager.host)
        label("Port")
        val portField = field("22", SshConnectionManager.port.toString())
        label("Username")
        val userField = field("contoh: ulul", SshConnectionManager.username)
        label("Folder remote (workspace root)")
        val remoteField = field("contoh: /home/ulul/project", SshConnectionManager.remoteBasePath)
        label("SSH args tambahan (opsional)")
        val argsField = field("contoh: -i /home/.ssh/my_key", SshConnectionManager.extraSshArgs)

        layout.addView(TextView(activity).apply {
            text = "SSH key & konfigurasi dibaca dari ~/.ssh/ Ubuntu Xed-Editor — sama persis seperti yang sudah kamu setup di terminal bawaan Xed-Editor. Tidak perlu input key/password di sini."
            setPadding(0, dp(12), 0, dp(4))
            alpha = 0.7f
        })

        AlertDialog.Builder(activity)
            .setTitle("Konfigurasi Remote Workspace")
            .setView(ScrollView(activity).apply { addView(layout) })
            .setPositiveButton("Simpan & Tes Koneksi") { _, _ ->
                SshConnectionManager.host = hostField.text.toString().trim()
                SshConnectionManager.port = portField.text.toString().toIntOrNull() ?: 22
                SshConnectionManager.username = userField.text.toString().trim()
                SshConnectionManager.remoteBasePath = remoteField.text.toString().trim().ifEmpty { "/" }
                SshConnectionManager.extraSshArgs = argsField.text.toString().trim()
                persistConfig()
                scope.launch {
                    val ok = SshConnectionManager.connect()
                    if (ok) {
                        toast("Terhubung ke ${SshConnectionManager.host}")
                    } else {
                        showConnectErrorDialog(activity)
                    }
                }
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    private fun persistConfig() {
        prefs().edit()
            .putString(KEY_HOST, SshConnectionManager.host)
            .putInt(KEY_PORT, SshConnectionManager.port)
            .putString(KEY_USER, SshConnectionManager.username)
            .putString(KEY_REMOTE_PATH, SshConnectionManager.remoteBasePath)
            .putString(KEY_EXTRA_ARGS, SshConnectionManager.extraSshArgs)
            .apply()
    }

    override fun onExtensionLoaded(extension: Extension) {
        val p = prefs()
        SshConnectionManager.host = p.getString(KEY_HOST, "") ?: ""
        SshConnectionManager.port = p.getInt(KEY_PORT, 22)
        SshConnectionManager.username = p.getString(KEY_USER, "") ?: ""
        SshConnectionManager.remoteBasePath = p.getString(KEY_REMOTE_PATH, "/") ?: "/"
        SshConnectionManager.extraSshArgs = p.getString(KEY_EXTRA_ARGS, "") ?: ""

        CommandProvider.registerCommand(toggleCommand)
        CommandProvider.registerCommand(configureCommand)
        CommandProvider.registerCommand(workspaceToggleCommand)
    }

    override fun onUninstalled(extension: Extension) {
        SshConnectionManager.disconnect()
        CommandProvider.unregisterCommand(toggleCommand)
        CommandProvider.unregisterCommand(configureCommand)
        CommandProvider.unregisterCommand(workspaceToggleCommand)
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {}
    override fun onActivityDestroyed(activity: Activity) {}
    override fun onActivityPaused(activity: Activity) {}
    override fun onActivityResumed(activity: Activity) {}
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) {}
    override fun onActivityStarted(activity: Activity) {}
    override fun onActivityStopped(activity: Activity) {}

    companion object {
        const val KEY_HOST = "host"
        const val KEY_PORT = "port"
        const val KEY_USER = "username"
        const val KEY_REMOTE_PATH = "remote_path"
        const val KEY_EXTRA_ARGS = "extra_ssh_args"
    }
}
