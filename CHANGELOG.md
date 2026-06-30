# Changelog

## 1.0.1

- **Breaking rewrite**: retargeted to the extension API actually shipped in Xed-Editor v3.2.9 /
  versionCode 87 (commit `73835433`), instead of a newer API that doesn't exist in that build.
  This fixes the install crash (`NoSuchMethodException: Main.<init> []`).
  - `Main` now has a no-arg constructor (`ExtensionAPI()` instead of
    `ExtensionAPI(context: ExtensionContext)`), matching what the host's loader actually expects.
  - Removed the Compose `SettingsContent` screen (doesn't exist in this API version - and the
    screen wasn't reachable anyway due to an unrelated upstream navigation bug). Configuration,
    connect/disconnect, and workspace open/close are now all Command Palette commands showing
    plain `AlertDialog`s / acting directly.
  - Settings persistence moved from the (nonexistent) `ExtensionContext.settings` to a manual
    `SharedPreferences` wrapper.
  - Sidebar file tree integration moved from `DrawerViewModel.addFileTreeTab`/`removeFileTreeTab`
    (doesn't exist in this API version) to the top-level `com.rk.filetree.addProject`/
    `removeProject` functions, which this API version uses instead.
  - CI now builds and caches its own pinned `sdk.jar` matching commit `73835433`, instead of
    using upstream's auto-updating `sdk-latest` (which tracks a newer, incompatible API and was
    the root cause of the mismatch above).

## 1.0.0

- Initial release.
- SSH/SFTP connection management (password or private-key auth).
- `RemoteFileObject`: SFTP-backed implementation of Xed-Editor's `FileObject`, enabling native
  file tree + editor tab integration for a remote folder.
- Remote command runner (SSH exec) with streamed output, for running builds/tests on the server.
- Command Palette connect/disconnect toggle.
