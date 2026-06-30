package com.ulul.remoteworkspace

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.os.Bundle
import android.text.InputType
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
 * Targets the extension API actually shipped in Xed-Editor v3.2.9 / versionCode 87 (commit
 * 73835433) - considerably more minimal than the newer "ExtensionContext"-based API: ExtensionAPI
 * takes no constructor argument, is instantiated via a no-arg constructor, and only has
 * onExtensionLoaded(extension) / onUninstalled(extension) - no SettingsContent, no built-in
 * command/scope/settings helpers. Everything those would have given us is recreated by hand
 * below. The good news: FileObject, EditorManager and the sidebar file tree (here exposed as
 * plain top-level functions in com.rk.filetree, simpler than the newer DrawerViewModel API) are
 * all present and unchanged, so native file-tree/editor-tab integration still works.
 */
@Keep
@Suppress("unused")
class Main : ExtensionAPI() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val commandContext = CommandContext { MainActivity.instance!!.viewModel }

    private fun prefs() =
        application!!.getSharedPreferences("com.ulul.remoteworkspace_prefs", Context.MODE_PRIVATE)

    private val toggleCommand = object : GlobalCommand(commandContext) {
        override val id: String = "com.ulul.remoteworkspace.toggle"

        override fun getLabel(): String =
            if (SshConnectionManager.isConnected) "Putuskan Remote Workspace" else "Hubungkan Remote Workspace"

        override fun getIcon(): Icon = Icon.TextIcon("SSH")

        override fun action(actionContext: ActionContext) {
            scope.launch {
                if (SshConnectionManager.isConnected) {
                    SshConnectionManager.disconnect()
                    toast("Terputus dari server")
                } else {
                    val ok = SshConnectionManager.connect()
                    toast(if (ok) "Terhubung ke server" else "Gagal terhubung, cek konfigurasi")
                }
            }
        }
    }

    // No SettingsContent hook exists in this API version, so configuration happens through a
    // plain AlertDialog instead, triggered from the Command Palette.
    private val configureCommand = object : GlobalCommand(commandContext) {
        override val id: String = "com.ulul.remoteworkspace.configure"
        override fun getLabel(): String = "Konfigurasi Remote Workspace"
        override fun getIcon(): Icon = Icon.TextIcon("CFG")
        override fun action(actionContext: ActionContext) {
            showConfigDialog(actionContext.currentActivity)
        }
    }

    // Mirrors what used to be the "Buka sebagai Workspace" / "Tutup Workspace" Settings buttons -
    // also unreachable now without SettingsContent, so it's a command instead.
    private val workspaceToggleCommand = object : GlobalCommand(commandContext) {
        override val id: String = "com.ulul.remoteworkspace.toggleworkspace"

        override fun getLabel(): String =
            if (SshConnectionManager.openedRoot != null) "Tutup Workspace Remote" else "Buka Workspace Remote"

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
                        toast("Gagal terhubung, cek konfigurasi")
                        return@launch
                    }
                }
                val rootAttrs = SshConnectionManager.statOrNull(SshConnectionManager.remoteBasePath)
                if (rootAttrs == null) {
                    toast("Folder tidak ditemukan: ${SshConnectionManager.remoteBasePath}")
                    return@launch
                }
                val root = RemoteFileObject(SshConnectionManager.remoteBasePath, rootAttrs)
                // save=false: a remote FileObject must never be embedded in a persisted drawer
                // tab list (see RemoteFileObject's class doc) - it would throw or, worse, corrupt
                // serialization for every other open tab too.
                addProject(root, false)
                SshConnectionManager.openedRoot = root
                toast("Workspace dibuka di sidebar")
            }
        }
    }

    private fun showConfigDialog(activity: Activity) {
        val density = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val layout = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(16), dp(24), dp(0))
        }

        fun addField(label: String, initial: String, isPassword: Boolean = false): EditText {
            layout.addView(TextView(activity).apply {
                text = label
                setPadding(0, dp(12), 0, dp(2))
            })
            val field = EditText(activity).apply {
                setText(initial)
                if (isPassword) {
                    inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                }
            }
            layout.addView(field)
            return field
        }

        val hostField = addField("Host", SshConnectionManager.host)
        val portField = addField("Port", SshConnectionManager.port.toString())
        val userField = addField("Username", SshConnectionManager.username)
        val passField = addField("Password (kosongkan jika pakai key)", SshConnectionManager.password, isPassword = true)
        val keyPathField = addField("Path private key (kosongkan jika pakai password)", SshConnectionManager.privateKeyPath)
        val keyPassField = addField("Passphrase key (jika ada)", SshConnectionManager.privateKeyPassphrase, isPassword = true)
        val remoteField = addField("Folder remote (workspace root)", SshConnectionManager.remoteBasePath)

        layout.addView(TextView(activity).apply {
            text = "Catatan: kalau path key diisi, key dipakai (bukan password). " +
                "Pastikan key ada di storage yang bisa diakses Xed-Editor, bukan di home Termux."
            setPadding(0, dp(12), 0, dp(4))
            alpha = 0.7f
        })

        val scrollView = ScrollView(activity).apply { addView(layout) }

        AlertDialog.Builder(activity)
            .setTitle("Konfigurasi Remote Workspace")
            .setView(scrollView)
            .setPositiveButton("Simpan") { _, _ ->
                val useKey = keyPathField.text.toString().isNotBlank()
                SshConnectionManager.host = hostField.text.toString().trim()
                SshConnectionManager.port = portField.text.toString().toIntOrNull() ?: 22
                SshConnectionManager.username = userField.text.toString().trim()
                SshConnectionManager.authMethod = if (useKey) AuthMethod.PRIVATE_KEY else AuthMethod.PASSWORD
                SshConnectionManager.password = passField.text.toString()
                SshConnectionManager.privateKeyPath = keyPathField.text.toString().trim()
                SshConnectionManager.privateKeyPassphrase = keyPassField.text.toString()
                SshConnectionManager.remoteBasePath = remoteField.text.toString().trim().ifEmpty { "/" }

                prefs().edit()
                    .putString(KEY_HOST, SshConnectionManager.host)
                    .putInt(KEY_PORT, SshConnectionManager.port)
                    .putString(KEY_USER, SshConnectionManager.username)
                    .putString(KEY_AUTH, if (useKey) "key" else "password")
                    .putString(KEY_PASSWORD, SshConnectionManager.password)
                    .putString(KEY_KEY_PATH, SshConnectionManager.privateKeyPath)
                    .putString(KEY_KEY_PASSPHRASE, SshConnectionManager.privateKeyPassphrase)
                    .putString(KEY_REMOTE_PATH, SshConnectionManager.remoteBasePath)
                    .apply()

                toast("Konfigurasi disimpan. Gunakan \"Hubungkan Remote Workspace\" untuk connect.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onExtensionLoaded(extension: Extension) {
        val p = prefs()
        SshConnectionManager.host = p.getString(KEY_HOST, SshConnectionManager.host) ?: SshConnectionManager.host
        SshConnectionManager.port = p.getInt(KEY_PORT, SshConnectionManager.port)
        SshConnectionManager.username = p.getString(KEY_USER, SshConnectionManager.username) ?: SshConnectionManager.username
        SshConnectionManager.authMethod = if (p.getString(KEY_AUTH, "password") == "key") {
            AuthMethod.PRIVATE_KEY
        } else {
            AuthMethod.PASSWORD
        }
        SshConnectionManager.password = p.getString(KEY_PASSWORD, SshConnectionManager.password) ?: SshConnectionManager.password
        SshConnectionManager.privateKeyPath =
            p.getString(KEY_KEY_PATH, SshConnectionManager.privateKeyPath) ?: SshConnectionManager.privateKeyPath
        SshConnectionManager.privateKeyPassphrase =
            p.getString(KEY_KEY_PASSPHRASE, SshConnectionManager.privateKeyPassphrase) ?: SshConnectionManager.privateKeyPassphrase
        SshConnectionManager.remoteBasePath =
            p.getString(KEY_REMOTE_PATH, SshConnectionManager.remoteBasePath) ?: SshConnectionManager.remoteBasePath

        CommandProvider.registerCommand(toggleCommand)
        CommandProvider.registerCommand(configureCommand)
        CommandProvider.registerCommand(workspaceToggleCommand)
    }

    override fun onUninstalled(extension: Extension) {
        scope.launch { SshConnectionManager.disconnect() }
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
        const val KEY_AUTH = "auth_method"
        const val KEY_PASSWORD = "password"
        const val KEY_KEY_PATH = "key_path"
        const val KEY_KEY_PASSPHRASE = "key_passphrase"
        const val KEY_REMOTE_PATH = "remote_path"
    }
}
