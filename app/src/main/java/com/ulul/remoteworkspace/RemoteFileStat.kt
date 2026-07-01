package com.ulul.remoteworkspace

data class RemoteFileStat(
    val isDir: Boolean,
    val isLink: Boolean,
    val size: Long,
    val mtime: Long,
    val permissions: String
)
