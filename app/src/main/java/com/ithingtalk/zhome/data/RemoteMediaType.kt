package com.ithingtalk.zhome.data

import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.data.local.db.FileEntity

enum class RemoteMediaType {
    VIDEO,
    IMAGE,
    AUDIO,
    DOCUMENT,
    UNKNOWN,
}

fun normalizeToMyFilesPath(remotePath: String): String {
    if (remotePath.isEmpty()) return remotePath
    var i = remotePath.indexOf("/MyFiles")
    if (i < 0) i = remotePath.indexOf("MyFiles")
    if (i < 0) return remotePath
    var tail = remotePath.substring(i)
    if (!tail.startsWith("/")) tail = "/$tail"
    return tail
}

fun classifyRemoteMediaType(remotePath: String): RemoteMediaType {
    val p = normalizeToMyFilesPath(remotePath).replace('\\', '/').lowercase()
    return when {
        p.startsWith("${Constants.TAG_VIDEO.lowercase()}/") ||
            p == Constants.TAG_VIDEO.lowercase() -> RemoteMediaType.VIDEO
        p.startsWith("${Constants.TAG_IMAGE.lowercase()}/") ||
            p == Constants.TAG_IMAGE.lowercase() -> RemoteMediaType.IMAGE
        p.startsWith("${Constants.TAG_AUDIO.lowercase()}/") ||
            p == Constants.TAG_AUDIO.lowercase() -> RemoteMediaType.AUDIO
        p.startsWith("${Constants.TAG_DOC.lowercase()}/") ||
            p == Constants.TAG_DOC.lowercase() -> RemoteMediaType.DOCUMENT
        else -> RemoteMediaType.UNKNOWN
    }
}

fun RemoteMediaType.toRecentFileType(): String = when (this) {
    RemoteMediaType.VIDEO -> "video"
    RemoteMediaType.IMAGE -> "image"
    RemoteMediaType.AUDIO -> "audio"
    RemoteMediaType.DOCUMENT -> "document"
    RemoteMediaType.UNKNOWN -> "unknown"
}

fun List<FileEntity>.pathsOfType(type: RemoteMediaType): List<String> =
    filter { !it.isDir && classifyRemoteMediaType(it.remotePath) == type }
        .map { it.remotePath }
