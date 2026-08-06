package com.ithingtalk.zhome.data.local

/**
 * Where a file belongs on the device — unified for upload pickers and download save targets.
 * 视频/图片 → 相册；音频 → 音频库；文档/其它文件 → 文档目录（系统文档库）。
 */
enum class LocalLibraryKind {
    GALLERY_VIDEO,
    GALLERY_IMAGE,
    AUDIO_LIBRARY,
    DOCUMENTS,
    ANY,
}

private val VIDEO_EXT = setOf("mp4", "mkv", "mov", "avi", "wmv", "webm", "3gp", "m4v")
private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "gif", "bmp", "webp", "heic", "heif")
private val AUDIO_EXT = setOf("mp3", "wav", "flac", "aac", "ogg", "m4a", "opus", "wma")
private val DOC_EXT = setOf(
    "pdf", "txt", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "csv", "md",
    "json", "xml", "log", "htm", "html", "rtf", "odt", "ods", "odp",
)

/** Upload: infer from current NAS directory (same rules as before). */
fun inferLocalLibraryKindFromNasPath(currentPath: String): LocalLibraryKind {
    val segments = currentPath
        .trim()
        .trimStart('/')
        .lowercase()
        .split('/')
        .filter { it.isNotBlank() }
    fun has(seg: String) = segments.any { it == seg }
    return when {
        has("video") -> LocalLibraryKind.GALLERY_VIDEO
        has("image") || has("photo") || has("picture") -> LocalLibraryKind.GALLERY_IMAGE
        has("audio") || has("music") -> LocalLibraryKind.AUDIO_LIBRARY
        has("doc") || has("document") -> LocalLibraryKind.DOCUMENTS
        else -> LocalLibraryKind.ANY
    }
}

private fun inferFromPathSegments(remotePath: String): LocalLibraryKind {
    val p = remotePath.lowercase()
    return when {
        p.contains("/video") -> LocalLibraryKind.GALLERY_VIDEO
        p.contains("/image") || p.contains("/photo") || p.contains("/picture") -> LocalLibraryKind.GALLERY_IMAGE
        p.contains("/audio") || p.contains("/music") -> LocalLibraryKind.AUDIO_LIBRARY
        p.contains("/doc") || p.contains("/document") -> LocalLibraryKind.DOCUMENTS
        else -> LocalLibraryKind.ANY
    }
}

private fun inferFromExtension(ext: String): LocalLibraryKind {
    val e = ext.lowercase().removePrefix(".")
    return when {
        e in VIDEO_EXT -> LocalLibraryKind.GALLERY_VIDEO
        e in IMAGE_EXT -> LocalLibraryKind.GALLERY_IMAGE
        e in AUDIO_EXT -> LocalLibraryKind.AUDIO_LIBRARY
        e in DOC_EXT -> LocalLibraryKind.DOCUMENTS
        else -> LocalLibraryKind.ANY
    }
}

/**
 * Download: prefer path segments on NAS (`/MyFiles/Video/...`), else fall back to extension
 * so the saved location matches upload semantics for the same file type.
 */
fun inferLocalLibraryKindFromRemotePath(remotePath: String, fileExtension: String): LocalLibraryKind {
    val fromPath = inferFromPathSegments(remotePath)
    if (fromPath != LocalLibraryKind.ANY) return fromPath
    return inferFromExtension(fileExtension)
}

fun documentMimeTypesForUpload(): Array<String> = arrayOf(
    "application/pdf",
    "text/plain",
    "text/*",
    "application/msword",
    "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
    "application/vnd.ms-excel",
    "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
    "application/vnd.ms-powerpoint",
    "application/vnd.openxmlformats-officedocument.presentationml.presentation",
)
