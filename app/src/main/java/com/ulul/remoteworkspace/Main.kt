package com.ulul.remoteworkspace

import android.app.Activity
import android.app.AlertDialog
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.annotation.Keep
import androidx.compose.runtime.Composable
import com.rk.commands.ActionContext
import com.rk.commands.CommandProvider
import com.rk.commands.GlobalCommand
import com.rk.extension.ExtensionAPI
import com.rk.extension.ExtensionContext
import com.rk.icons.Icon
import com.rk.utils.toast
import kotlinx.coroutines.launch

@Keep
@Suppress("unused")
class Main(context: ExtensionContext) : ExtensionAPI(context) {

    private val toggleCommand = object : GlobalCommand() {
        override val id: String = "com.ulul.remoteworkspace.toggle"

        override fun getLabel(): String =
            if (SshConnectionManager.isConnected) "Putuskan Remote Workspace" else "Hubungkan Remote Workspace"

        override fun getIcon(): Icon = Icon.TextIcon("SSH")

        override fun action(actionContext: ActionContext) {
            context.scope.launch {
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

    // Workaround for a bug in Xed-Editor's own navigation (ExtensionDetail.kt builds the
    // settings route with the literal text "{extensionId}" instead of substituting the real
    // extension id, so ExtensionSettings always receives a null extension - the settings gear
    // icon shows "extension not found" for every extension with hasSettings=true, not just this
    // one). Until that's fixed upstream, configuration happens through this dialog instead.
    private val configureCommand = object : GlobalCommand() {
        override val id: String = "com.ulul.remoteworkspace.configure"
        override fun getLabel(): String = "Konfigurasi Remote Workspace"
        override fun getIcon(): Icon = Icon.TextIcon("CFG")
        override fun action(actionContext: ActionContext) {
            showConfigDialog(actionContext.currentActivity)
        }
    }

    // Same reasoning as configureCommand: the "Buka sebagai Workspace" button normally lives in
    // SettingsContent, which is unreachable until the upstream navigation bug is fixed. This
    // command does the same thing (add/remove the sidebar file tree tab for the remote root).
    private val workspaceToggleCommand = object : GlobalCommand() {
        override val id: String = "com.ulul.remoteworkspace.toggleworkspace"

        override fun getLabel(): String =
            if (SshConnectionManager.openedRoot != null) "Tutup Workspace Remote" else "Buka Workspace Remote"

        override fun getIcon(): Icon = Icon.TextIcon("DIR")

        override fun action(actionContext: ActionContext) {
            val existing = SshConnectionManager.openedRoot
            if (existing != null) {
                com.rk.activities.main.MainActivity.instance?.drawerViewModel?.removeFileTreeTab(existing, false)
                SshConnectionManager.openedRoot = null
                toast("Workspace ditutup")
                return
            }

            context.scope.launch {
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
                val activity = com.rk.activities.main.MainActivity.instance
                if (activity == null) {
                    toast("Tidak bisa mengakses jendela utama")
                    return@launch
                }
                activity.drawerViewModel.addFileTreeTab(root, false)
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

        val scrollView = ScrollView(activity).apply {
            addView(layout)
            gravity = Gravity.TOP
        }

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

                context.settings.putString(KEY_HOST, SshConnectionManager.host)
                context.settings.putInt(KEY_PORT, SshConnectionManager.port)
                context.settings.putString(KEY_USER, SshConnectionManager.username)
                context.settings.putString(KEY_AUTH, if (useKey) "key" else "password")
                context.settings.putString(KEY_PASSWORD, SshConnectionManager.password)
                context.settings.putString(KEY_KEY_PATH, SshConnectionManager.privateKeyPath)
                context.settings.putString(KEY_KEY_PASSPHRASE, SshConnectionManager.privateKeyPassphrase)
                context.settings.putString(KEY_REMOTE_PATH, SshConnectionManager.remoteBasePath)

                toast("Konfigurasi disimpan. Gunakan \"Hubungkan Remote Workspace\" untuk connect.")
            }
            .setNegativeButton("Batal", null)
            .show()
    }

    override fun onExtensionLoaded() {
        SshConnectionManager.host = context.settings.getString(KEY_HOST, SshConnectionManager.host)
        SshConnectionManager.port = context.settings.getInt(KEY_PORT, SshConnectionManager.port)
        SshConnectionManager.username = context.settings.getString(KEY_USER, SshConnectionManager.username)
        SshConnectionManager.authMethod = if (context.settings.getString(KEY_AUTH, "password") == "key") {
            AuthMethod.PRIVATE_KEY
        } else {
            AuthMethod.PASSWORD
        }
        SshConnectionManager.password = context.settings.getString(KEY_PASSWORD, SshConnectionManager.password)
        SshConnectionManager.privateKeyPath = context.settings.getString(KEY_KEY_PATH, SshConnectionManager.privateKeyPath)
        SshConnectionManager.privateKeyPassphrase =
            context.settings.getString(KEY_KEY_PASSPHRASE, SshConnectionManager.privateKeyPassphrase)
        SshConnectionManager.remoteBasePath = context.settings.getString(KEY_REMOTE_PATH, SshConnectionManager.remoteBasePath)

        CommandProvider.registerCommand(toggleCommand)
        CommandProvider.registerCommand(configureCommand)
        CommandProvider.registerCommand(workspaceToggleCommand)
        context.logInfo("Remote Workspace extension loaded (not connected automatically).")
    }

    override fun onInstalled() {
        // Defaults are applied on first load.
    }

    override fun onUpdated() {
        // No migration needed between versions yet.
    }

    override fun onUninstalled() {
        context.scope.launch { SshConnectionManager.disconnect() }
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

    @Composable
    override fun SettingsContent() {
        RemoteWorkspaceSettingsScreen(context)
    }

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
