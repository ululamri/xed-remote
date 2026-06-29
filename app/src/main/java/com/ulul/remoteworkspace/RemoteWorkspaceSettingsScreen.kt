package com.ulul.remoteworkspace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.rk.activities.main.MainActivity
import com.rk.extension.ExtensionContext
import com.rk.utils.toast
import kotlinx.coroutines.launch

@Composable
fun RemoteWorkspaceSettingsScreen(context: ExtensionContext) {
    val settings = context.settings
    val scope = rememberCoroutineScope()

    var host by remember { mutableStateOf(settings.getString(Main.KEY_HOST, SshConnectionManager.host)) }
    var port by remember { mutableStateOf(settings.getInt(Main.KEY_PORT, SshConnectionManager.port).toString()) }
    var username by remember { mutableStateOf(settings.getString(Main.KEY_USER, SshConnectionManager.username)) }
    var useKeyAuth by remember {
        mutableStateOf(settings.getString(Main.KEY_AUTH, "password") == "key")
    }
    var password by remember { mutableStateOf(settings.getString(Main.KEY_PASSWORD, SshConnectionManager.password)) }
    var keyPath by remember { mutableStateOf(settings.getString(Main.KEY_KEY_PATH, SshConnectionManager.privateKeyPath)) }
    var keyPassphrase by remember {
        mutableStateOf(settings.getString(Main.KEY_KEY_PASSPHRASE, SshConnectionManager.privateKeyPassphrase))
    }
    var remotePath by remember { mutableStateOf(settings.getString(Main.KEY_REMOTE_PATH, SshConnectionManager.remoteBasePath)) }

    var connected by remember { mutableStateOf(SshConnectionManager.isConnected) }
    var workspaceOpen by remember { mutableStateOf(SshConnectionManager.openedRoot != null) }
    var busy by remember { mutableStateOf(false) }
    var logText by remember { mutableStateOf("") }

    var command by remember { mutableStateOf("") }
    var commandOutput by remember { mutableStateOf("") }
    var commandRunning by remember { mutableStateOf(false) }

    DisposableEffect(Unit) {
        SshConnectionManager.onStateChanged = { isConnected ->
            connected = isConnected
            if (!isConnected) workspaceOpen = false
        }
        SshConnectionManager.onLog = { line ->
            logText = (logText + "\n" + line).takeLast(2000)
        }
        onDispose {
            SshConnectionManager.onStateChanged = null
            SshConnectionManager.onLog = null
        }
    }

    fun persist() {
        settings.putString(Main.KEY_HOST, host)
        settings.putInt(Main.KEY_PORT, port.toIntOrNull() ?: 22)
        settings.putString(Main.KEY_USER, username)
        settings.putString(Main.KEY_AUTH, if (useKeyAuth) "key" else "password")
        settings.putString(Main.KEY_PASSWORD, password)
        settings.putString(Main.KEY_KEY_PATH, keyPath)
        settings.putString(Main.KEY_KEY_PASSPHRASE, keyPassphrase)
        settings.putString(Main.KEY_REMOTE_PATH, remotePath)
    }

    fun applyToConnection() {
        SshConnectionManager.host = host
        SshConnectionManager.port = port.toIntOrNull() ?: 22
        SshConnectionManager.username = username
        SshConnectionManager.authMethod = if (useKeyAuth) AuthMethod.PRIVATE_KEY else AuthMethod.PASSWORD
        SshConnectionManager.password = password
        SshConnectionManager.privateKeyPath = keyPath
        SshConnectionManager.privateKeyPassphrase = keyPassphrase
        SshConnectionManager.remoteBasePath = remotePath
    }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = if (connected) "Status: Terhubung" else "Status: Tidak terhubung",
            style = MaterialTheme.typography.titleMedium
        )

        OutlinedTextField(
            value = host,
            onValueChange = { host = it },
            label = { Text("Host (IP/domain server)") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !connected
        )
        OutlinedTextField(
            value = port,
            onValueChange = { port = it.filter { c -> c.isDigit() }.take(5) },
            label = { Text("Port") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.fillMaxWidth(),
            enabled = !connected
        )
        OutlinedTextField(
            value = username,
            onValueChange = { username = it },
            label = { Text("Username") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !connected
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text("Gunakan SSH key (bukan password)")
            Switch(checked = useKeyAuth, onCheckedChange = { useKeyAuth = it }, enabled = !connected)
        }

        if (useKeyAuth) {
            OutlinedTextField(
                value = keyPath,
                onValueChange = { keyPath = it },
                label = { Text("Path private key") },
                supportingText = {
                    Text("Pastikan file key ada di storage yang bisa diakses Xed-Editor, bukan di dalam home Termux (app berbeda = sandbox berbeda).")
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !connected
            )
            OutlinedTextField(
                value = keyPassphrase,
                onValueChange = { keyPassphrase = it },
                label = { Text("Passphrase key (kosongkan jika tidak ada)") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !connected
            )
        } else {
            OutlinedTextField(
                value = password,
                onValueChange = { password = it },
                label = { Text("Password") },
                visualTransformation = PasswordVisualTransformation(),
                modifier = Modifier.fillMaxWidth(),
                enabled = !connected
            )
        }

        OutlinedTextField(
            value = remotePath,
            onValueChange = { remotePath = it },
            label = { Text("Folder remote (workspace root)") },
            supportingText = { Text("Contoh: /home/ulul/project") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !connected
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !connected && !busy,
                onClick = {
                    persist()
                    applyToConnection()
                    toast("Pengaturan disimpan")
                }
            ) { Text("Simpan") }

            Button(
                enabled = !busy,
                onClick = {
                    busy = true
                    scope.launch {
                        if (connected) {
                            SshConnectionManager.disconnect()
                        } else {
                            persist()
                            applyToConnection()
                            val ok = SshConnectionManager.connect()
                            if (!ok) toast("Gagal terhubung, cek pengaturan & log di bawah")
                        }
                        busy = false
                    }
                }
            ) { Text(if (connected) "Putuskan" else "Hubungkan") }
        }

        if (connected) {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (!workspaceOpen) {
                    Button(onClick = {
                        scope.launch {
                            val rootAttrs = SshConnectionManager.statOrNull(SshConnectionManager.remoteBasePath)
                            if (rootAttrs == null) {
                                toast("Folder tidak ditemukan: ${SshConnectionManager.remoteBasePath}")
                                return@launch
                            }
                            val root = RemoteFileObject(SshConnectionManager.remoteBasePath, rootAttrs)
                            val activity = MainActivity.instance
                            if (activity == null) {
                                toast("Tidak bisa mengakses jendela utama")
                                return@launch
                            }
                            // save=false: a remote FileObject can't survive Java serialization
                            // (it holds a connection reference), so don't let the host try to
                            // persist it across app restarts.
                            activity.drawerViewModel.addFileTreeTab(root, false)
                            SshConnectionManager.openedRoot = root
                            workspaceOpen = true
                            toast("Workspace dibuka di sidebar")
                        }
                    }) { Text("Buka sebagai Workspace") }
                } else {
                    Button(onClick = {
                        val root = SshConnectionManager.openedRoot
                        if (root != null) {
                            MainActivity.instance?.drawerViewModel?.removeFileTreeTab(root, false)
                            SshConnectionManager.openedRoot = null
                        }
                        workspaceOpen = false
                    }) { Text("Tutup Workspace") }
                }
            }
        }

        HorizontalDivider()
        Text("Jalankan command di server:", style = MaterialTheme.typography.titleSmall)

        OutlinedTextField(
            value = command,
            onValueChange = { command = it },
            label = { Text("Command") },
            placeholder = { Text("contoh: npm run build") },
            modifier = Modifier.fillMaxWidth(),
            enabled = !commandRunning
        )

        Button(
            enabled = !commandRunning && command.isNotBlank(),
            onClick = {
                commandRunning = true
                commandOutput = ""
                val commandToRun = command
                scope.launch {
                    try {
                        if (!SshConnectionManager.isConnected) {
                            persist()
                            applyToConnection()
                            val ok = SshConnectionManager.connect()
                            if (!ok) {
                                commandOutput = "Gagal terhubung ke server."
                                commandRunning = false
                                return@launch
                            }
                        }
                        val result = SshConnectionManager.runCommand(commandToRun) { chunk ->
                            commandOutput = (commandOutput + chunk).takeLast(4000)
                        }
                        commandOutput += "\n\n[exit code: ${result.exitCode}]"
                        if (result.stderr.isNotBlank()) {
                            commandOutput += "\n--- stderr ---\n${result.stderr}"
                        }
                    } catch (e: Exception) {
                        commandOutput += "\nError: ${e.message}"
                    } finally {
                        commandRunning = false
                    }
                }
            }
        ) { Text(if (commandRunning) "Menjalankan..." else "Jalankan") }

        if (commandOutput.isNotEmpty()) {
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = commandOutput,
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(8.dp)
                        .heightIn(max = 240.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }

        if (logText.isNotEmpty()) {
            Text("Log koneksi:", style = MaterialTheme.typography.labelMedium)
            Card(modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = logText.trim(),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier
                        .padding(8.dp)
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                )
            }
        }
    }
}
