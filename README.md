# Xed Remote Workspace

A "remote development" extension for [Xed-Editor](https://github.com/Xed-Editor/Xed-Editor),
similar in spirit to VS Code's Remote-SSH: connect to a development server over SSH/SFTP and
edit its files directly in Xed-Editor's native editor and file tree - the files and any commands
you run live on the server, not on your phone's storage/CPU.

## How it works

This isn't a "download, edit locally, re-upload" tool. It implements Xed-Editor's `FileObject`
interface backed by SFTP, so the app's own file tree and editor tabs read/write the remote
filesystem directly, the same way they'd read/write a local folder. Opening a file fetches it on
demand; saving writes it straight back to the server.

For the "workload shouldn't burden the phone" half of the ask, it also includes a simple remote
command runner (SSH exec) so things like builds/tests run on the server, with output streamed
back into Xed-Editor.

## Features

- Connect via SSH (password or private-key auth) and browse a remote folder.
- **"Buka sebagai Workspace"** adds the remote folder as a real entry in Xed-Editor's sidebar file
  tree (`DrawerViewModel.addFileTreeTab`) - tapping files there opens them as normal editor tabs
  with full syntax highlighting, exactly like local files.
- Run arbitrary shell commands on the server and see the output live, without leaving the editor.
- Connect/disconnect toggle also available from the Command Palette.
- Connection settings (host/port/user/auth/remote path) persist between sessions.

## Mengakses pengaturan (penting)

Xed-Editor saat ini punya bug di navigasinya sendiri: ikon gear "Settings" di halaman detail
ekstensi selalu menampilkan "extension not found", untuk **ekstensi apapun** yang punya halaman
settings, bukan cuma ekstensi ini. (`ExtensionDetail.kt` membangun route navigasi dengan teks
literal `{extensionId}` bukannya menggantinya dengan ID ekstensi yang sebenarnya, jadi
`ExtensionSettings` selalu menerima `null`.)

Sampai itu diperbaiki upstream, atur ekstensi ini lewat **Command Palette** alih-alih halaman
Settings. Tiga command yang tersedia:

- **"Konfigurasi Remote Workspace"** - dialog untuk isi host/port/username/password (atau key)/
  folder remote.
- **"Hubungkan Remote Workspace"** / **"Putuskan Remote Workspace"** - connect/disconnect.
- **"Buka Workspace Remote"** / **"Tutup Workspace Remote"** - tambah/hapus folder remote dari
  sidebar file tree.

## Setup

1. Open the Command Palette and run **"Konfigurasi Remote Workspace"**.
2. Fill in host, port, username, and either a password or a private key path + passphrase.
3. Set the remote folder you want as your workspace root (e.g. `/home/youruser/project`), then
   tap **Simpan**.
4. Run **"Hubungkan Remote Workspace"** from the Command Palette to connect.
5. Run **"Buka Workspace Remote"** from the Command Palette - the remote folder appears in
   Xed-Editor's sidebar file tree. Run **"Tutup Workspace Remote"** to remove it again.

(All four actions above are also available as buttons on the extension's Settings screen, once
Xed-Editor's navigation bug - see above - is fixed upstream.)

### About SSH keys on Android

If you generate keys inside Termux (`~/.ssh/id_ed25519`), they live in Termux's own private app
storage, which Xed-Editor's process **cannot** read (different app, different sandbox). Copy the
key to a location Xed-Editor already has access to (shared/internal storage) before pointing the
"Path private key" field at it.

## Known limitations

- **Host key checking is disabled** (`StrictHostKeyChecking=no`) for simplicity. This is fine for
  a personal/trusted dev server on a private network, but means there's no protection against a
  man-in-the-middle on untrusted networks. A future version could add proper host key pinning.
- **Whole-file buffering.** Reads/writes fetch or upload the entire file in one go (simplest way
  to do this correctly over a single shared SFTP channel). Great for source files, not meant for
  multi-gigabyte binaries.
- **The sidebar workspace tab doesn't survive an app restart** - and that's deliberate. Xed-Editor
  persists open tabs via plain Java serialization; a remote file reference is kept connection-free
  specifically so it serializes safely (no crash), but a restored reference has no live connection
  until you reconnect. So: reconnect and tap "Buka sebagai Workspace" again after restarting the
  app, or just leave it connected while you work.
- One shared SFTP channel, used by everything (file tree + tabs); operations are serialized with a
  mutex, so it's correct but not built for heavy parallel access. Command execution uses its own
  separate exec channel per command, so running a command doesn't block file browsing.

## Building

Standard [Xed-Editor extension template](https://github.com/Xed-Editor/Extension-Template) layout.
From Termux or any machine with internet access:

```bash
./compileDebug      # or compileDebug.bat on Windows
```

This extension bundles its own SSH/SFTP library
([`com.github.mwiede:jsch`](https://github.com/mwiede/jsch), a maintained drop-in fork of jcraft's
JSch) since the host app has no SSH client of its own - so the resulting APK is a few MB larger
than a typical extension. That's expected.

## License

MIT
