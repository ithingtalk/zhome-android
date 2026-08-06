package com.ithingtalk.zhome.data.remote.nas

import android.util.Log
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.remote.p2p.RemoteLinkCoordinator
import com.ithingtalk.zhome.data.repository.DeviceLinkMode
import com.ithingtalk.zhome.jni.NativeBridge
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.io.File

/**
 * Downloads `file.db` from the NAS and imports normal + trash rows, then `shared.db`.
 * Shared by main-page refresh and file browser / recycle bin refresh.
 */
object NasUserDbSync {
    private const val TAG = "NasUserDbSync"
    /** Matches desktopApp [lanReadTimeoutSec] for userLogin / userGetStatus on LAN. */
    private const val LAN_CONNECT_READ_TIMEOUT_SEC = 10L

    sealed interface SyncFromDeviceResult {
        data object Success : SyncFromDeviceResult
        /** Qt [ConnectDevicePage] `idNeedAllow` — `check_user_login` none or `user_authority` denied/none. */
        data object NeedAdminApproval : SyncFromDeviceResult
        /** User password rejected by NAS — do not treat as a transient network error for retry logic. */
        data object WrongPassword : SyncFromDeviceResult
        /** [hdd_uninit] — UI may offer navigation to device management / disk format. */
        data object DiskUninitialized : SyncFromDeviceResult
        data class Error(val message: String) : SyncFromDeviceResult
    }

    /** Sync only `shared.db` into owner `__shared__<user>`. */
    suspend fun syncSharedDbOnly(mac: String): Boolean {
        val app = ZhomeApp.instance
        val deviceRepo = app.deviceRepo
        val prefs = app.prefs
        val fileRepo = app.fileRepo
        val nasLocal = app.nasLocal
        val appCtx = app.applicationContext

        val d = deviceRepo.getByMac(mac) ?: return false
        val userEmail = prefs.getUser()
        val userPass = prefs.getPass()
        if (userEmail.isBlank() || userPass.isBlank()) return false

        val forceP2p = prefs.getForceP2p()
        val mode = deviceRepo.resolveLinkMode(d, forceP2p)
        if (mode == DeviceLinkMode.Offline) return false
        val useLocal = mode == DeviceLinkMode.Local
        // Per-user + per-device config dir (qtApp layout).
        val dir = com.ithingtalk.zhome.data.local.AppPaths
            .deviceRoot(appCtx, userEmail, mac)
            .apply { mkdirs() }

        return try {
            withContext(Dispatchers.IO) {
                val sharedLocal = File(dir, "shared.db")
                if (useLocal) {
                    val ip = deviceRepo.lanNasHost(mac)
                    if (ip.isBlank()) throw IllegalStateException("No device IP")
                    val tmp = File(dir, "shared.db~")
                    val url = NasLocalClient.sharedDbDownloadUrl(ip)
                    nasLocal.downloadToFile(url, tmp, userEmail, userPass)
                    if (sharedLocal.exists()) sharedLocal.delete()
                    tmp.renameTo(sharedLocal)
                } else {
                    val p2pErr = RemoteLinkCoordinator.ensureRemoteCommandReady(d)
                    if (p2pErr != null) throw IllegalStateException(p2pErr)
                    val remotePath = NasUrl.p2pSharedDbPath()
                    val downloadDone = CompletableDeferred<Boolean>()
                    val prevHandler = NativeBridge.libp2pAppMessageHandler
                    NativeBridge.libp2pAppMessageHandler = { msg ->
                        prevHandler?.invoke(msg)
                        try {
                            val json = JSONObject(msg)
                            val fileStatus = json.optString("hip2p_cmd_file_status", "")
                            val fileName = json.optString("p2p_cmd_file_name", "")
                            if (fileName.contains("shared.db")) {
                                when (fileStatus) {
                                    "hip2p_cmd_file_success" -> downloadDone.complete(true)
                                    "hip2p_cmd_file_error",
                                    "hip2p_cmd_file_terminated" -> downloadDone.complete(false)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    NativeBridge.libp2pDownloadFile(
                        remotePath = remotePath,
                        localPath = sharedLocal.absolutePath,
                    )
                    val ok = withTimeoutOrNull(60_000) { downloadDone.await() } ?: false
                    NativeBridge.libp2pAppMessageHandler = prevHandler
                    if (!ok) throw IllegalStateException("shared.db download failed")
                }
                if (sharedLocal.isFile) {
                    fileRepo.importFromNasSharedSqliteFile(sharedLocal.absolutePath, "__shared__${userEmail}")
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "syncSharedDbOnly failed", e)
            false
        }
    }

    suspend fun syncFromDevice(
        mac: String,
        onProgress: ((ConnectStage) -> Unit)? = null,
    ): SyncFromDeviceResult {
        val app = ZhomeApp.instance
        val deviceRepo = app.deviceRepo
        val prefs = app.prefs
        val fileRepo = app.fileRepo
        val nasLocal = app.nasLocal
        val appCtx = app.applicationContext

        val d = deviceRepo.getByMac(mac) ?: run {
            Log.w(TAG, "device not found: $mac")
            return SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_device_not_found))
        }
        val userEmail = prefs.getUser()
        val userPass = prefs.getPass()
        if (userEmail.isBlank() || userPass.isBlank()) {
            return SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_not_logged_in))
        }

        val forceP2p = prefs.getForceP2p()
        val mode = deviceRepo.resolveLinkMode(d, forceP2p)
        if (mode == DeviceLinkMode.Offline) return SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_device_offline))
        val useLocal = mode == DeviceLinkMode.Local
        // Per-user + per-device config dir (qtApp layout).
        val dir = com.ithingtalk.zhome.data.local.AppPaths
            .deviceRoot(appCtx, userEmail, mac)
            .apply { mkdirs() }
        val finalDb = File(dir, Constants.NAS_USER_DB_FILE)

        try {
            if (useLocal) {
                onProgress?.invoke(ConnectStage.UserLogin)
                val loginCmd = NasCommands.userLogin(userEmail, userPass)
                val loginResp = try {
                    withContext(Dispatchers.IO) {
                        deviceRepo.postDeviceCommand(
                            d,
                            loginCmd,
                            lanReadTimeoutSec = LAN_CONNECT_READ_TIMEOUT_SEC,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "local login failed", e)
                    return SyncFromDeviceResult.Error(
                        e.message?.takeIf { it.isNotBlank() }
                            ?: appCtx.getString(R.string.sync_lan_failed),
                    )
                }
                userLoginOutcome(loginResp)?.let { return it }
                onProgress?.invoke(ConnectStage.GetStatus)
                val statusCmd = NasCommands.userGetStatus(userEmail, userPass)
                val statusJson = try {
                    withContext(Dispatchers.IO) {
                        deviceRepo.postDeviceCommand(
                            d,
                            statusCmd,
                            lanReadTimeoutSec = LAN_CONNECT_READ_TIMEOUT_SEC,
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "local get_status failed", e)
                    return SyncFromDeviceResult.Error(
                        e.message?.takeIf { it.isNotBlank() }
                            ?: appCtx.getString(R.string.sync_lan_failed),
                    )
                }
                if (statusJson.isBlank()) {
                    return SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_status_empty))
                }
                userAuthorityOutcome(statusJson)?.let { return it }
                diskBlockingResult(statusJson)?.let { return it }
                val sharePwd = NasCommands.parseSharePwdForApp(statusJson)
                if (sharePwd.isNotBlank()) prefs.setSharePwd(sharePwd)
                applyUserProfileFromStatus(prefs, statusJson, userEmail)
                NasCommands.parseDeviceIp(statusJson).takeIf { it.isNotBlank() }?.let { lanIp ->
                    deviceRepo.rememberLanIp(mac, lanIp)
                }

                try {
                    withContext(Dispatchers.IO) {
                        onProgress?.invoke(ConnectStage.DownloadFileDb)
                        val ip = deviceRepo.lanNasHost(mac)
                        if (ip.isBlank()) throw IllegalStateException("No device IP")
                        val tmp = File(dir, "${Constants.NAS_USER_DB_FILE}~")
                        val url = NasLocalClient.userDbDownloadUrl(ip)
                        nasLocal.downloadToFile(url, tmp, userEmail, userPass)
                        if (finalDb.exists()) finalDb.delete()
                        if (!tmp.renameTo(finalDb)) {
                            throw IllegalStateException(appCtx.getString(R.string.sync_cannot_save_file_db))
                        }

                        onProgress?.invoke(ConnectStage.ImportFileDb)
                        fileRepo.importFromNasSqliteFile(finalDb.absolutePath, userEmail)
                        fileRepo.importFromNasSqliteFileByStatus(
                            finalDb.absolutePath,
                            "__trash__${userEmail}",
                            "inTrash",
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "local file.db sync failed", e)
                    return SyncFromDeviceResult.Error(
                        e.message?.takeIf { it.isNotBlank() }
                            ?: appCtx.getString(R.string.sync_file_db_failed),
                    )
                }
            } else {
                onProgress?.invoke(ConnectStage.ConnectingRemote)
                onProgress?.invoke(ConnectStage.UserLogin)
                val loginCmd = NasCommands.userLogin(userEmail, userPass)
                val loginResp = withContext(Dispatchers.IO) {
                    deviceRepo.postDeviceCommand(d, loginCmd)
                }
                userLoginOutcome(loginResp)?.let { return it }
                onProgress?.invoke(ConnectStage.GetStatus)
                val statusCmd = NasCommands.userGetStatus(userEmail, userPass)
                val statusJson = withContext(Dispatchers.IO) {
                    deviceRepo.postDeviceCommand(d, statusCmd)
                }
                if (statusJson.isBlank()) {
                    Log.w(TAG, "status empty")
                    return SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_status_empty))
                }
                userAuthorityOutcome(statusJson)?.let { return it }
                diskBlockingResult(statusJson)?.let { return it }
                val sharePwd = NasCommands.parseSharePwdForApp(statusJson)
                if (sharePwd.isNotBlank()) prefs.setSharePwd(sharePwd)
                applyUserProfileFromStatus(prefs, statusJson, userEmail)
                NasCommands.parseDeviceIp(statusJson).takeIf { it.isNotBlank() }?.let { lanIp ->
                    deviceRepo.rememberLanIp(mac, lanIp)
                }

                try {
                    withContext(Dispatchers.IO) {
                        onProgress?.invoke(ConnectStage.DownloadFileDb)
                        val p2pErr = RemoteLinkCoordinator.ensureRemoteCommandReady(d)
                        if (p2pErr != null) throw IllegalStateException(p2pErr)

                        val remotePath = NasUrl.p2pUserDbPath(userEmail)
                        val downloadDone = CompletableDeferred<Boolean>()

                        val prevHandler = NativeBridge.libp2pAppMessageHandler
                        NativeBridge.libp2pAppMessageHandler = { msg ->
                            prevHandler?.invoke(msg)
                            try {
                                val json = JSONObject(msg)
                                val fileStatus = json.optString("hip2p_cmd_file_status", "")
                                val fileName = json.optString("p2p_cmd_file_name", "")
                                if (fileName.contains(Constants.NAS_USER_DB_FILE)) {
                                    when (fileStatus) {
                                        "hip2p_cmd_file_success" -> downloadDone.complete(true)
                                        "hip2p_cmd_file_error",
                                        "hip2p_cmd_file_terminated" -> downloadDone.complete(false)
                                    }
                                }
                            } catch (_: Exception) {}
                        }

                        NativeBridge.libp2pDownloadFile(
                            remotePath = remotePath,
                            localPath = finalDb.absolutePath,
                        )

                        val ok = withTimeoutOrNull(60_000) { downloadDone.await() } ?: false
                        NativeBridge.libp2pAppMessageHandler = prevHandler
                        if (!ok) throw IllegalStateException("P2P file.db download failed or timed out")

                        onProgress?.invoke(ConnectStage.ImportFileDb)
                        fileRepo.importFromNasSqliteFile(finalDb.absolutePath, userEmail)
                        fileRepo.importFromNasSqliteFileByStatus(
                            finalDb.absolutePath,
                            "__trash__${userEmail}",
                            "inTrash",
                        )
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "remote file.db sync failed", e)
                    return SyncFromDeviceResult.Error(
                        e.message?.takeIf { it.isNotBlank() }
                            ?: appCtx.getString(R.string.sync_file_db_failed),
                    )
                }
            }
        } catch (e: CancellationException) {
            Log.w(TAG, "syncFromDevice cancelled", e)
            return SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_connection_interrupted))
        } catch (e: Exception) {
            Log.e(TAG, "syncFromDevice file.db failed", e)
            return SyncFromDeviceResult.Error(
                e.message?.takeIf { it.isNotBlank() } ?: appCtx.getString(R.string.sync_file_db_failed),
            )
        }

        try {
            withContext(Dispatchers.IO) {
                onProgress?.invoke(ConnectStage.DownloadSharedDb)
                val sharedLocal = File(dir, "shared.db")
                if (useLocal) {
                    val ip = deviceRepo.lanNasHost(mac)
                    if (ip.isBlank()) throw IllegalStateException("No device IP")
                    val tmp = File(dir, "shared.db~")
                    val url = NasLocalClient.sharedDbDownloadUrl(ip)
                    nasLocal.downloadToFile(url, tmp, userEmail, userPass)
                    if (sharedLocal.exists()) sharedLocal.delete()
                    tmp.renameTo(sharedLocal)
                } else {
                    onProgress?.invoke(ConnectStage.ConnectingRemote)
                    val p2pErr = RemoteLinkCoordinator.ensureRemoteCommandReady(d)
                    if (p2pErr != null) throw IllegalStateException(p2pErr)
                    val remotePath = NasUrl.p2pSharedDbPath()
                    val downloadDone = CompletableDeferred<Boolean>()
                    val prevHandler = NativeBridge.libp2pAppMessageHandler
                    NativeBridge.libp2pAppMessageHandler = { msg ->
                        prevHandler?.invoke(msg)
                        try {
                            val json = JSONObject(msg)
                            val fileStatus = json.optString("hip2p_cmd_file_status", "")
                            val fileName = json.optString("p2p_cmd_file_name", "")
                            if (fileName.contains("shared.db")) {
                                when (fileStatus) {
                                    "hip2p_cmd_file_success" -> downloadDone.complete(true)
                                    "hip2p_cmd_file_error",
                                    "hip2p_cmd_file_terminated" -> downloadDone.complete(false)
                                }
                            }
                        } catch (_: Exception) {}
                    }
                    NativeBridge.libp2pDownloadFile(
                        remotePath = remotePath,
                        localPath = sharedLocal.absolutePath,
                    )
                    val ok = withTimeoutOrNull(60_000) { downloadDone.await() } ?: false
                    NativeBridge.libp2pAppMessageHandler = prevHandler
                    if (!ok) throw IllegalStateException("shared.db download failed")
                }
                if (sharedLocal.isFile) {
                    onProgress?.invoke(ConnectStage.ImportSharedDb)
                    fileRepo.importFromNasSharedSqliteFile(sharedLocal.absolutePath, "__shared__${userEmail}")
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "shared.db sync failed", e)
        }

        onProgress?.invoke(ConnectStage.Finishing)
        return SyncFromDeviceResult.Success
    }

    private fun userLoginOutcome(loginResp: String): SyncFromDeviceResult? {
        val appCtx = ZhomeApp.instance.applicationContext
        return when {
            NasCommands.userLoginSuccess(loginResp) -> null
            NasCommands.userLoginNeedAllow(loginResp) -> SyncFromDeviceResult.NeedAdminApproval
            NasCommands.userLoginFail(loginResp) -> SyncFromDeviceResult.WrongPassword
            else -> SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_login_failed))
        }
    }

    private fun userAuthorityOutcome(statusJson: String): SyncFromDeviceResult? {
        val appCtx = ZhomeApp.instance.applicationContext
        val a = NasCommands.parseUserAuthorityStatus(statusJson)
        return when (a) {
            Constants.VAL_USER_AUTHORITY_PASS -> null
            Constants.VAL_USER_AUTHORITY_DENIED, Constants.VAL_LOGIN_STATUS_NONE -> SyncFromDeviceResult.NeedAdminApproval
            "" -> SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_auth_invalid))
            else -> SyncFromDeviceResult.Error(
                appCtx.getString(R.string.sync_auth_failed_with_code, a),
            )
        }
    }

    /**
     * Blocks file.db sync when disk is not ready. [DiskUninitialized] is a dedicated case for main-screen UI.
     */
    private fun diskBlockingResult(statusJson: String): SyncFromDeviceResult? {
        val appCtx = ZhomeApp.instance.applicationContext
        val s = NasCommands.parseHddStatusFromUserStatus(statusJson).trim()
        return when (s) {
            "", Constants.VAL_HDD_STATUS_OK -> null
            Constants.VAL_HDD_STATUS_UNINIT -> SyncFromDeviceResult.DiskUninitialized
            Constants.VAL_HDD_STATUS_READY -> SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_disk_preparing))
            Constants.VAL_HDD_STATUS_INITING -> SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_disk_formatting))
            Constants.VAL_HDD_STATUS_NONE -> SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_disk_none))
            Constants.VAL_HDD_STATUS_UMOUNT -> SyncFromDeviceResult.Error(appCtx.getString(R.string.sync_disk_umount))
            else -> null
        }
    }

    suspend fun applyUserProfileFromStatus(
        prefs: com.ithingtalk.zhome.data.local.prefs.LocalPrefs,
        statusJson: String,
        userEmail: String,
    ) {
        val profile = NasCommands.parseUserProfileFromStatus(statusJson, userEmail)
        prefs.setUserNickname(profile.nickname)
        prefs.setUserStorage(profile.userStorage.ifBlank { "0" })
    }
}
