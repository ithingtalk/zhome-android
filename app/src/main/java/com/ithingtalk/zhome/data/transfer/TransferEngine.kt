package com.ithingtalk.zhome.data.transfer

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.local.LocalLibraryKind
import com.ithingtalk.zhome.data.local.inferLocalLibraryKindFromRemotePath
import com.ithingtalk.zhome.data.local.db.TransferEntity
import com.ithingtalk.zhome.data.remote.nas.NasHttpDownload
import com.ithingtalk.zhome.data.remote.nas.NasHttpUpload
import com.ithingtalk.zhome.data.remote.nas.NasCommands
import com.ithingtalk.zhome.data.remote.nas.NasUrl
import com.ithingtalk.zhome.data.remote.p2p.RemoteLinkCoordinator
import com.ithingtalk.zhome.data.repository.TransferRepository
import com.ithingtalk.zhome.jni.NativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

class TransferEngine(
    private val appCtx: Context,
    private val repo: TransferRepository,
) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    companion object {
        private const val ADD_ONE_FILE_MAX_ATTEMPTS = 5
        private const val ADD_ONE_FILE_RETRY_BASE_MS = 500L
        private const val ADD_ONE_FILE_LARGE_BYTES = 100L * 1024L * 1024L
        private const val ADD_ONE_FILE_LARGE_SETTLE_MS = 1000L
    }
    private val runMutex = Mutex()
    private var job: Job? = null

    fun start() {
        if (job != null) return
        job = scope.launch {
            // Use collect (not collectLatest): status QUEUED→RUNNING causes Room to re-emit.
            // collectLatest would cancel the in-flight transfer and surface as a bogus "failed" error.
            repo.observeQueued().collect { queued ->
                if (queued.isEmpty()) return@collect
                // Drain queue sequentially.
                runMutex.withLock {
                    for (t in queued) {
                        val latest = repo.getById(t.id) ?: continue
                        if (latest.status != TransferRepository.STATUS_QUEUED) continue
                        processOne(latest)
                        // Small yield to avoid starving UI.
                        delay(50)
                    }
                }
            }
        }
    }

    private suspend fun processOne(t: TransferEntity) {
        repo.updateStatus(t.id, TransferRepository.STATUS_RUNNING)
        try {
            when (t.type) {
                TransferRepository.TYPE_DOWNLOAD -> doDownload(t)
                TransferRepository.TYPE_UPLOAD -> doUpload(t)
                else -> error("Unknown type ${t.type}")
            }
            repo.success(t.id) // success => delete record
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            repo.fail(t.id, e.message ?: "Error")
            Log.e("TransferEngine", "transfer failed id=${t.id}", e)
        }
    }

    private suspend fun doDownload(t: TransferEntity) {
        val device = ZhomeApp.instance.deviceRepo.getByMac(t.deviceMac) ?: error("No device")
        val user = ZhomeApp.instance.prefs.getUser()
        val pass = ZhomeApp.instance.prefs.getPass()
        if (user.isBlank() || pass.isBlank()) error("No account")

        val isLocal = ZhomeApp.instance.deviceRepo.useLocalLink(device)
        val ip = if (isLocal) {
            ZhomeApp.instance.deviceRepo.lanNasHost(device.mac).trim()
        } else {
            ""
        }

        val fileName = t.remotePath.substringAfterLast("/").ifBlank { "file" }
        val ext = fileName.substringAfterLast('.', "").lowercase()
        val libraryKind = inferLocalLibraryKindFromRemotePath(t.remotePath, ext)

        val tempDest = File(t.localPath.ifBlank {
            // Unified "~/Downloads/zhome/download" root (not user / device
            // scoped per the cross-app path spec).
            val base = com.ithingtalk.zhome.data.local.AppPaths
                .globalDownloadDir(appCtx)
            File(base, fileName).absolutePath
        })

        if (isLocal) {
            if (ip.isBlank()) error("No device IP")
            val isShared = t.remotePath.startsWith("/Ftp")
            val url = NasUrl.getRemoteFilePath(ip, user, t.remotePath, isShared)
            val dbBase = NasUrl.dbFileDownloadAddress(ip)
            val sharePwd = ZhomeApp.instance.prefs.getSharePwd()
            val (httpUser, httpPass) = NasUrl.fileHttpCredentials(url, isShared, dbBase, user, pass, sharePwd)
            NasHttpDownload.downloadAuthenticated(
                url = url,
                user = httpUser,
                pass = httpPass,
                dest = tempDest,
                onProgress = { transferred, total ->
                    val pct = if (total > 0) (transferred.toDouble() / total.toDouble() * 100.0).toFloat() else 0f
                    scope.launch {
                        repo.updateProgress(
                            id = t.id,
                            status = TransferRepository.STATUS_RUNNING,
                            progress = pct,
                            transferred = transferred,
                            total = total,
                        )
                    }
                }
            ).getOrThrow()
        } else {
            val err = RemoteLinkCoordinator.ensureP2pPlaybackReady(device)
            if (err != null) error(err)
            val remoteP2pPath = NasUrl.p2pRemoteFilePath(ip, user, t.remotePath, t.remotePath.startsWith("/Ftp"))
            if (remoteP2pPath.isBlank()) error("Cannot map P2P path")

            val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val prevHandler = NativeBridge.libp2pAppMessageHandler
            NativeBridge.libp2pAppMessageHandler = { msg ->
                prevHandler?.invoke(msg)
                try {
                    val json = JSONObject(msg)
                    val status = json.optString("hip2p_cmd_file_status", "")
                    val name = json.optString("p2p_cmd_file_name", "")
                    val percent = json.optString("p2p_cmd_file_persent", "").toFloatOrNull()
                    val offset = json.optString("p2p_cmd_file_offset", "").toLongOrNull()
                    val size = json.optString("p2p_cmd_file_size", "").toLongOrNull()
                    if (percent != null) {
                        scope.launch {
                            repo.updateProgress(
                                id = t.id,
                                status = TransferRepository.STATUS_RUNNING,
                                progress = percent,
                                transferred = offset ?: 0L,
                                total = size ?: 0L,
                            )
                        }
                    }
                    if (name.contains(fileName)) {
                        when (status) {
                            "hip2p_cmd_file_success" -> done.complete(true)
                            "hip2p_cmd_file_error",
                            "hip2p_cmd_file_terminated" -> done.complete(false)
                        }
                    }
                } catch (_: Exception) {}
            }
            try {
                withContext(Dispatchers.IO) {
                    NativeBridge.libp2pDownloadFile(
                        remotePath = remoteP2pPath,
                        localPath = tempDest.absolutePath,
                    )
                }
                val ok = withTimeoutOrNull(60_000) { done.await() } ?: false
                if (!ok) error("P2P download failed")
            } finally {
                NativeBridge.libp2pAppMessageHandler = prevHandler
            }
        }

        try {
            when (libraryKind) {
                LocalLibraryKind.GALLERY_VIDEO,
                LocalLibraryKind.GALLERY_IMAGE,
                LocalLibraryKind.AUDIO_LIBRARY,
                -> saveToGalleryOrAudioLibrary(appCtx, tempDest, ext, fileName, libraryKind)
                LocalLibraryKind.DOCUMENTS,
                LocalLibraryKind.ANY,
                -> saveToDocumentsLibrary(appCtx, tempDest, ext, fileName)
            }
        } finally {
            tempDest.delete()
        }
    }

    private suspend fun doUpload(t: TransferEntity) {
        val device = ZhomeApp.instance.deviceRepo.getByMac(t.deviceMac) ?: error("No device")
        val user = ZhomeApp.instance.prefs.getUser()
        val pass = ZhomeApp.instance.prefs.getPass()
        if (user.isBlank() || pass.isBlank()) error("No account")

        val isLocal = ZhomeApp.instance.deviceRepo.useLocalLink(device)
        val ip = if (isLocal) {
            ZhomeApp.instance.deviceRepo.lanNasHost(device.mac).trim()
        } else {
            ""
        }

        val src = File(t.localPath)
        if (!src.exists()) error("Local file missing")

        if (isLocal) {
            if (ip.isBlank()) error("No device IP")
            val url = NasUrl.getRemoteFilePath(ip, user, t.remotePath, isShared = false)
            NasHttpUpload.putAuthenticated(
                url = url,
                user = user,
                pass = pass,
                file = src,
                onProgress = { sent, total ->
                    val pct = if (total > 0) (sent.toDouble() / total.toDouble() * 100.0).toFloat() else 0f
                    scope.launch {
                        repo.updateProgress(
                            id = t.id,
                            status = TransferRepository.STATUS_RUNNING,
                            progress = pct,
                            transferred = sent,
                            total = total,
                        )
                    }
                }
            ).getOrThrow()
        } else {
            val err = RemoteLinkCoordinator.ensureP2pPlaybackReady(device)
            if (err != null) error(err)
            val remoteP2pPath = NasUrl.p2pRemoteFilePath(ip, user, t.remotePath, isShared = false)
            if (remoteP2pPath.isBlank()) error("Cannot map P2P path")

            val fileName = t.remotePath.substringAfterLast("/")
            val done = kotlinx.coroutines.CompletableDeferred<Boolean>()
            val prevHandler = NativeBridge.libp2pAppMessageHandler
            NativeBridge.libp2pAppMessageHandler = { msg ->
                prevHandler?.invoke(msg)
                try {
                    val json = JSONObject(msg)
                    val status = json.optString("hip2p_cmd_file_status", "")
                    val name = json.optString("p2p_cmd_file_name", "")
                    val percent = json.optString("p2p_cmd_file_persent", "").toFloatOrNull()
                    val offset = json.optString("p2p_cmd_file_offset", "").toLongOrNull()
                    val size = json.optString("p2p_cmd_file_size", "").toLongOrNull()
                    if (percent != null) {
                        scope.launch {
                            repo.updateProgress(
                                id = t.id,
                                status = TransferRepository.STATUS_RUNNING,
                                progress = percent,
                                transferred = offset ?: 0L,
                                total = size ?: 0L,
                            )
                        }
                    }
                    if (name.contains(fileName)) {
                        when (status) {
                            "hip2p_cmd_file_success" -> done.complete(true)
                            "hip2p_cmd_file_error",
                            "hip2p_cmd_file_terminated" -> done.complete(false)
                        }
                    }
                } catch (_: Exception) {}
            }
            try {
                withContext(Dispatchers.IO) {
                    NativeBridge.libp2pUploadFile(
                        localPath = src.absolutePath,
                        remotePath = remoteP2pPath,
                    )
                }
                val ok = withTimeoutOrNull(120_000) { done.await() } ?: false
                if (!ok) error("P2P upload failed")
            } finally {
                NativeBridge.libp2pAppMessageHandler = prevHandler
            }
        }

        // Upload transaction: bytes OK + add_one_file OK, then local index. Index failure fails the transfer.
        val existingName = t.remotePath.substringAfterLast("/")
        val nasRel = NasCommands.normalizeMyFilesPath(t.remotePath).ifBlank { t.remotePath }
        // Large WebDAV PUTs often land via a temp name ending in '~' then rename;
        // immediate add_one_file can race and get NAS "fail" (stat miss). Retry.
        val uploadSize = src.length()
        if (uploadSize >= ADD_ONE_FILE_LARGE_BYTES) {
            delay(ADD_ONE_FILE_LARGE_SETTLE_MS)
        }
        var lastResp = ""
        var indexed = false
        for (attempt in 0 until ADD_ONE_FILE_MAX_ATTEMPTS) {
            if (attempt > 0) {
                val waitMs = ADD_ONE_FILE_RETRY_BASE_MS * (1L shl (attempt - 1).coerceAtMost(3))
                Log.w(
                    "TransferEngine",
                    "add_one_file retry ${attempt + 1}/$ADD_ONE_FILE_MAX_ATTEMPTS after ${waitMs}ms nasRel=$nasRel last=$lastResp",
                )
                delay(waitMs)
            }
            lastResp = ZhomeApp.instance.deviceRepo.postDeviceCommand(
                device,
                NasCommands.addOneFile(user, pass, nasRel),
            )
            if (NasCommands.addOneFileSuccess(lastResp)) {
                indexed = true
                break
            }
        }
        if (!indexed) {
            error("add_one_file failed for $nasRel: $lastResp")
        }
        ZhomeApp.instance.fileRepo.addFile(
            com.ithingtalk.zhome.data.local.db.FileEntity(
                remotePath = t.remotePath,
                size = uploadSize,
                date = System.currentTimeMillis(),
                isDir = false,
                owner = user,
            )
        )
        Log.i("TransferEngine", "upload OK: $existingName")
    }

    /** 相册（图/视频）或系统音频库 — 与上传选择入口一致。 */
    private fun saveToGalleryOrAudioLibrary(
        ctx: Context,
        file: File,
        ext: String,
        displayName: String,
        kind: LocalLibraryKind,
    ) {
        val mime = mimeForExt(ext)
        val collection = when (kind) {
            LocalLibraryKind.GALLERY_VIDEO -> MediaStore.Video.Media.EXTERNAL_CONTENT_URI
            LocalLibraryKind.GALLERY_IMAGE -> MediaStore.Images.Media.EXTERNAL_CONTENT_URI
            LocalLibraryKind.AUDIO_LIBRARY -> MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
            else -> error("invalid kind for gallery/audio save")
        }
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
            put(MediaStore.MediaColumns.MIME_TYPE, mime)
            put(MediaStore.MediaColumns.DATE_ADDED, System.currentTimeMillis() / 1000)
            put(MediaStore.MediaColumns.DATE_MODIFIED, System.currentTimeMillis() / 1000)
            if (Build.VERSION.SDK_INT >= 29) put(MediaStore.MediaColumns.IS_PENDING, 1)
        }
        val uri = ctx.contentResolver.insert(collection, values) ?: throw IllegalStateException("MediaStore insert failed")
        try {
            ctx.contentResolver.openOutputStream(uri)?.use { out ->
                file.inputStream().use { it.copyTo(out) }
            } ?: throw IllegalStateException("Cannot open MediaStore output")
            if (Build.VERSION.SDK_INT >= 29) {
                val v2 = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, v2, null, null)
            }
        } catch (e: Exception) {
            ctx.contentResolver.delete(uri, null, null)
            throw e
        }
    }

    /** 系统文档目录（MediaStore Downloads + Documents），与 OpenDocument 文档库一致。 */
    private fun saveToDocumentsLibrary(ctx: Context, file: File, ext: String, displayName: String) {
        val mime = mimeForExt(ext)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, displayName)
                put(MediaStore.MediaColumns.MIME_TYPE, mime)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOCUMENTS)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val uri = ctx.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                ?: throw IllegalStateException("MediaStore Downloads insert failed")
            try {
                ctx.contentResolver.openOutputStream(uri)?.use { out ->
                    file.inputStream().use { it.copyTo(out) }
                } ?: throw IllegalStateException("Cannot open document output")
                val v2 = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                ctx.contentResolver.update(uri, v2, null, null)
            } catch (e: Exception) {
                ctx.contentResolver.delete(uri, null, null)
                throw e
            }
        } else {
            val dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOCUMENTS)
            dir.mkdirs()
            val dest = File(dir, displayName)
            if (dest.exists()) dest.delete()
            file.copyTo(dest)
        }
    }

    private fun mimeForExt(ext: String): String = when (ext.lowercase()) {
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "gif" -> "image/gif"
        "bmp" -> "image/bmp"
        "webp" -> "image/webp"
        "mp4" -> "video/mp4"
        "mkv" -> "video/x-matroska"
        "mov" -> "video/quicktime"
        "avi" -> "video/x-msvideo"
        "wmv" -> "video/x-ms-wmv"
        "webm" -> "video/webm"
        "mp3" -> "audio/mpeg"
        "wav" -> "audio/wav"
        "flac" -> "audio/flac"
        "aac" -> "audio/aac"
        "ogg" -> "audio/ogg"
        "m4a" -> "audio/mp4"
        "opus" -> "audio/opus"
        "pdf" -> "application/pdf"
        "txt" -> "text/plain"
        "htm", "html" -> "text/html"
        "csv" -> "text/csv"
        "json" -> "application/json"
        "xml" -> "application/xml"
        "doc" -> "application/msword"
        "docx" -> "application/vnd.openxmlformats-officedocument.wordprocessingml.document"
        "xls" -> "application/vnd.ms-excel"
        "xlsx" -> "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"
        "ppt" -> "application/vnd.ms-powerpoint"
        "pptx" -> "application/vnd.openxmlformats-officedocument.presentationml.presentation"
        else -> "application/octet-stream"
    }
}

