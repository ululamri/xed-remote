package com.ulul.remoteworkspace

/**
 * Lightweight representation of remote file metadata, obtained via `ssh stat`.
 * Kept separate so ConnectionManager, RemoteFileObject, and FileCache can all use it
 * without circular dependencies.
 */
data class RemoteFileStat(
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val mtime: Long,       // Unix seconds
    val permissions: String
)
