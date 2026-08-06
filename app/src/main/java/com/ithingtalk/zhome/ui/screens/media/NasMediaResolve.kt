package com.ithingtalk.zhome.ui.screens.media

import android.util.Log
import com.ithingtalk.zhome.data.local.prefs.LocalPrefs
import com.ithingtalk.zhome.data.remote.nas.NasCommands
import com.ithingtalk.zhome.data.remote.nas.NasLanLoopbackHttpServer
import com.ithingtalk.zhome.data.remote.nas.NasUrl
import com.ithingtalk.zhome.data.remote.nas.VideoTranscodeQuality
import com.ithingtalk.zhome.data.remote.nas.transcodedNasImageRemotePath
import com.ithingtalk.zhome.data.remote.nas.transcodedNasVideoRemotePath
import com.ithingtalk.zhome.data.remote.p2p.RemoteLinkCoordinator
import com.ithingtalk.zhome.data.repository.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val TAG = "NasMediaResolve"

data class NasMediaResolution(
    val uri: String?,
    val httpUser: String?,
    val httpPass: String?,
    val error: String?,
    /** When set, caller must [NasLanLoopbackHttpServer.unregister] when playback / download finishes. */
    val loopbackSessionId: String? = null,
)

/** One playlist entry after NAS URL resolution (aligned with Qt AudioService.load). */
data class ResolvedAudioTrack(
    val remotePath: String,
    val resolution: NasMediaResolution,
)

/** Default playback tier: LAN → HD, remote / force P2P → SD (same as iOS VideoPlayerView.onAppear). */
suspend fun defaultVideoTranscodeQuality(deviceRepo: DeviceRepository): VideoTranscodeQuality {
    val device = deviceRepo.getCurrent() ?: return VideoTranscodeQuality.SD
    return if (deviceRepo.useLocalLink(device)) {
        VideoTranscodeQuality.HD
    } else {
        VideoTranscodeQuality.SD
    }
}

suspend fun resolveVideoPlaybackTargetPath(
    originalPath: String,
    quality: VideoTranscodeQuality,
    prefs: LocalPrefs,
    deviceRepo: DeviceRepository,
): String = withContext(Dispatchers.IO) {
    if (quality == VideoTranscodeQuality.ORIGINAL) {
        return@withContext originalPath
    }

    val transcodedPath = transcodedNasVideoRemotePath(originalPath, quality)
    val exists = checkPlayableFileExists(transcodedPath, prefs, deviceRepo)
    if (exists) {
        return@withContext transcodedPath
    }

    Log.w(TAG, "fallback to original video path: quality=$quality path=$transcodedPath")
    return@withContext originalPath
}

suspend fun shouldUseSdImagePreview(
    originalPath: String,
    forceOriginal: Boolean,
    deviceRepo: DeviceRepository,

): Boolean = withContext(Dispatchers.IO) {
    if (forceOriginal) return@withContext false
    if (transcodedNasImageRemotePath(originalPath) == originalPath) return@withContext false
    val device = deviceRepo.getCurrent() ?: return@withContext false
    return@withContext !deviceRepo.useLocalLink(device)
}

suspend fun resolveImagePreviewTargetPath(
    originalPath: String,
    forceOriginal: Boolean,
    prefs: LocalPrefs,
    deviceRepo: DeviceRepository,

): String = withContext(Dispatchers.IO) {
    if (shouldUseSdImagePreview(originalPath, forceOriginal, deviceRepo)) {
        return@withContext transcodedNasImageRemotePath(originalPath)
    }
    return@withContext originalPath
}

suspend fun resolveNasAudioPlaylist(
    remotePaths: List<String>,
    prefs: LocalPrefs,
    deviceRepo: DeviceRepository,
): Pair<List<ResolvedAudioTrack>, String?> = withContext(Dispatchers.IO) {
    val tracks = mutableListOf<ResolvedAudioTrack>()
    var firstError: String? = null
    for (path in remotePaths) {
        val r = resolveNasMediaUrl(path, prefs, deviceRepo)
        if (r.uri != null && r.error == null) {
            tracks.add(ResolvedAudioTrack(path, r))
        } else if (firstError == null && r.error != null) {
            firstError = r.error
        }
    }
    return@withContext tracks to firstError
}

fun remapPlaylistStartIndex(
    originalPaths: List<String>,
    startIndex: Int,
    tracks: List<ResolvedAudioTrack>,
): Int {
    val want = originalPaths.getOrNull(startIndex) ?: return 0
    val idx = tracks.indexOfFirst { it.remotePath == want }
    return if (idx >= 0) idx else 0
}

fun formatMediaTimeMs(ms: Long): String {
    if (ms <= 0L) return "0:00"
    val totalSec = ms / 1000
    val m = totalSec / 60
    val s = totalSec % 60
    return "%d:%02d".format(m, s)
}

suspend fun resolveNasMediaUrl(
    url: String,
    prefs: LocalPrefs,
    deviceRepo: DeviceRepository
): NasMediaResolution = withContext(Dispatchers.IO) {
    val accountUser = prefs.getUser()
    val accountPass = prefs.getPass()
    val sharePwd = prefs.getSharePwd()
    val device = deviceRepo.getCurrent()
    if (device == null) {
        return@withContext NasMediaResolution(
            null, null, null,
            "No device selected. Connect to a NAS from the device list.",
            null,
        )
    }
    val useLocal = deviceRepo.useLocalLink(device)
    val isShared = url.startsWith("/Ftp")

    val ip = if (useLocal) deviceRepo.lanNasHost(device.mac).trim() else ""
    if (useLocal && ip.isEmpty()) {
        return@withContext NasMediaResolution(
            null, null, null,
            "No LAN IP for this device. Connect on the local network or disable Force Remote to use P2P.",
            null,
        )
    }
    val dbBase = if (useLocal) NasUrl.dbFileDownloadAddress(ip) else ""

    if (!useLocal) {
        val p2pErr = RemoteLinkCoordinator.ensureP2pPlaybackReady(device)
        if (p2pErr != null) {
            return@withContext NasMediaResolution(null, null, null, p2pErr, null)
        }
    }

    if (isShared && sharePwd.isBlank()) {
        return@withContext NasMediaResolution(
            null, null, null,
            "Shared folder password is not set. Reconnect to the NAS so share_pwd_for_app is saved (same as Qt).",
            null,
        )
    }

    val mediaUri = NasUrl.getPlayerHttpUrl(url, ip, accountUser, useLocal, isShared)
    if (mediaUri.isEmpty()) {
        return@withContext NasMediaResolution(null, null, null, "Cannot map URL for P2P playback (unknown path).", null)
    }

    val (u, p) = NasUrl.fileHttpCredentials(mediaUri, isShared, dbBase, accountUser, accountPass, sharePwd)
    if (useLocal && mediaUri.startsWith("https://", ignoreCase = true)) {
        val pair = NasLanLoopbackHttpServer.register(
            mediaUri,
            u.takeIf { it.isNotBlank() },
            p.takeIf { it.isNotBlank() },
        )
        if (pair == null) {
            return@withContext NasMediaResolution(
                null,
                null,
                null,
                "Local playback proxy unavailable (port ${NasLanLoopbackHttpServer.LISTEN_PORT} in use or bind error).",
                null,
            )
        }
        return@withContext NasMediaResolution(
            pair.first,
            null,
            null,
            null,
            pair.second.toString(),
        )
    }

    return@withContext NasMediaResolution(
        mediaUri,
        u.takeIf { it.isNotBlank() },
        p.takeIf { it.isNotBlank() },
        null,
        null,
    )
}

private suspend fun checkPlayableFileExists(
    remotePath: String,
    prefs: LocalPrefs,
    deviceRepo: DeviceRepository,
): Boolean {
    val device = deviceRepo.getCurrent() ?: return false
    val user = prefs.getUser()
    val pass = prefs.getPass()

    val probePath = normalizeProbePathForCommand(remotePath, user)
    if (probePath.isBlank()) {
        Log.d(TAG, "[Probe] invalid path: $remotePath")
        return false
    }

    val cmd = NasCommands.checkFileExists(user, pass, probePath)
    val exists = runCatching {
        val resp = deviceRepo.postDeviceCommand(
            device,
            cmd,
            httpUser = user,
            httpPass = pass,
            remoteTimeoutMs = 8_000,
            lanReadTimeoutSec = 8L,
        )
        val parsed = NasCommands.parseCheckFileExists(resp)
        parsed?.first == true && parsed.second
    }.getOrDefault(false)
    Log.d(TAG, "[Probe] check_file_exists path=$probePath exists=$exists")
    return exists
}

private fun normalizeProbePathForCommand(remotePath: String, user: String): String {
    val trimmed = remotePath.trim()
    if (trimmed.isBlank()) return ""
    if (trimmed.startsWith("/Ftp/")) return trimmed
    if (trimmed.startsWith("Ftp/")) return "/$trimmed"

    if (trimmed.startsWith("/MyFiles/")) return "/Ftp/$user$trimmed"
    if (trimmed.startsWith("MyFiles/")) return "/Ftp/$user/$trimmed"

    val myFilesIdx = trimmed.indexOf("MyFiles/")
    if (myFilesIdx >= 0) {
        return "/Ftp/$user/${trimmed.substring(myFilesIdx)}"
    }

    return ""
}
