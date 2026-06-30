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

## Compatibility

Targets the extension API as shipped in **Xed-Editor v3.2.9 / versionCode 87** (commit
`73835433`). This API version is intentionally minimal: `ExtensionAPI` has a no-arg constructor
and only two lifecycle hooks (`onExtensionLoaded` / `onUninstalled`) - there is no
`SettingsContent`/Compose settings page, no built-in coroutine scope, and no settings storage
helper. Everything that would normally provide is recreated by hand here (a manual
`SharedPreferences` wrapper, a manual `CoroutineScope`, and a `CommandContext` built the same way
the host app itself builds one).

The good news: `FileObject`, `EditorManager`, and the sidebar file tree are all present and
unchanged in this API version (exposed as plain top-level functions in `com.rk.filetree` -
`addProject()` / `removeProject()` - rather than the newer `DrawerViewModel` API), so native
file-tree/editor-tab integration works exactly as designed.

Because there's no settings-page hook, **all configuration and the workspace open/close action
happen through the Command Palette** via plain Android `AlertDialog`s - see Usage below.

## Features

- Connect via SSH (password or private-key auth) and browse a remote folder.
- Adds the remote folder as a real entry in Xed-Editor's sidebar file tree - tapping files there
  opens them as normal editor tabs with full syntax highlighting, exactly like local files.
- Run arbitrary shell commands on the server (via the Command Palette dialog's underlying
  connection) so things like builds/tests run on the server, not the phone.
- Connect/disconnect and open/close-workspace toggles, all from the Command Palette.
- Connection settings (host/port/user/auth/remote path) persist between sessions.

## Usage

Open the Command Palette and use these commands:

- **"Konfigurasi Remote Workspace"** - dialog to fill in host/port/username/password (or private
  key path + passphrase)/remote folder. Tap **Simpan** to save.
- **"Hubungkan Remote Workspace"** / **"Putuskan Remote Workspace"** - connect/disconnect.
- **"Buka Workspace Remote"** / **"Tutup Workspace Remote"** - add/remove the remote folder from
  the sidebar file tree. Connects automatically first if needed.

Typical first-time flow: run "Konfigurasi Remote Workspace", fill in your server details and a
remote path (e.g. `/home/youruser/project`), save, then run "Buka Workspace Remote" - it appears
in the sidebar, and tapping any file inside opens it as a normal editor tab.

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
  until you reconnect. So: reconnect and run "Buka Workspace Remote" again after restarting the
  app, or just leave it connected while you work.
- One shared SFTP channel, used by everything (file tree + tabs); operations are serialized with a
  mutex, so it's correct but not built for heavy parallel access. Command execution uses its own
  separate exec channel per command, so running a command doesn't block file browsing.

## Building

Standard [Xed-Editor extension template](https://github.com/Xed-Editor/Extension-Template)
layout, with one important difference: instead of using upstream's auto-updating `sdk.jar`, CI
builds and caches its own, pinned to the exact Xed-Editor commit this code targets (see
Compatibility above, and the comments in `.github/workflows/plugin-build-test.yml`).

Just push to GitHub (or trigger the workflow manually from the Actions tab) - everything else is
automatic. The **first** run will take noticeably longer than a typical extension build (it's
compiling a slice of Xed-Editor itself, to produce the pinned `sdk.jar`); every run after that
restores it from cache in seconds. `app/libs/README.md` has instructions if you ever want to
build a `sdk.jar` and compile locally instead of via CI.

This extension bundles its own SSH/SFTP library
([`com.github.mwiede:jsch`](https://github.com/mwiede/jsch), a maintained drop-in fork of jcraft's
JSch) since the host app has no SSH client of its own - so the resulting APK is a few MB larger
than a typical extension. That's expected.

## License

MIT
