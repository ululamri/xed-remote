# Changelog

## 1.0.0

- Initial release.
- SSH/SFTP connection management (password or private-key auth).
- `RemoteFileObject`: SFTP-backed implementation of Xed-Editor's `FileObject`, enabling native
  file tree + editor tab integration for a remote folder.
- "Buka sebagai Workspace" / "Tutup Workspace" to add/remove the remote folder from the sidebar
  file tree.
- Remote command runner (SSH exec) with streamed output, for running builds/tests on the server.
- Command Palette connect/disconnect toggle.
