package com.ithingtalk.zhome.ui.screens.about

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.local.db.DeviceEntity
import com.ithingtalk.zhome.data.remote.nas.NasCommands
import com.ithingtalk.zhome.data.repository.DeviceLinkMode
import com.ithingtalk.zhome.data.repository.DeviceRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AboutViewModel : ViewModel() {
    private val repo: DeviceRepository = ZhomeApp.instance.deviceRepo
    private val prefs = ZhomeApp.instance.prefs
    private val appCtx = ZhomeApp.instance.applicationContext

    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var deviceRows by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set
    var diskRows by mutableStateOf<List<Pair<String, String>>>(emptyList())
        private set

    fun load(mac: String) {
        viewModelScope.launch {
            busy = true
            error = null
            deviceRows = emptyList()
            diskRows = emptyList()
            try {
                val device = withContext(Dispatchers.IO) { repo.getByMac(mac) }
                if (device == null) {
                    error = appCtx.getString(R.string.about_err_no_device)
                    return@launch
                }
                val user = prefs.getUser()
                val pass = prefs.getPass()
                if (user.isBlank() || pass.isBlank()) {
                    error = appCtx.getString(R.string.about_err_no_login)
                    return@launch
                }
                val loginResp = withContext(Dispatchers.IO) {
                    repo.postDeviceCommand(device, NasCommands.userLogin(user, pass))
                }
                when {
                    NasCommands.userLoginSuccess(loginResp) -> {
                        val statusJson = withContext(Dispatchers.IO) {
                            repo.postDeviceCommand(device, NasCommands.userGetStatus(user, pass))
                        }
                        if (statusJson.isBlank()) {
                            error = appCtx.getString(R.string.about_err_no_status)
                            return@launch
                        }
                        val forceP2p = prefs.getForceP2p()
                        deviceRows = buildDeviceRows(device, statusJson, forceP2p)
                        diskRows = buildDiskRows(statusJson, user)
                    }
                    NasCommands.userLoginNeedAllow(loginResp) ->
                        error = appCtx.getString(R.string.about_err_need_allow)
                    NasCommands.userLoginFail(loginResp) ->
                        error = appCtx.getString(R.string.about_err_bad_password)
                    else -> error = appCtx.getString(R.string.about_err_connect)
                }
            } catch (e: Exception) {
                Log.w(TAG, "load about failed", e)
                error = e.message?.takeIf { it.isNotBlank() } ?: appCtx.getString(R.string.about_err_load_failed)
            } finally {
                busy = false
            }
        }
    }

    private fun buildDeviceRows(device: DeviceEntity, statusJson: String, forceP2p: Boolean): List<Pair<String, String>> {
        val link = when (repo.resolveLinkMode(device, forceP2p)) {
            DeviceLinkMode.Local -> appCtx.getString(R.string.about_link_local)
            DeviceLinkMode.Remote -> appCtx.getString(R.string.about_link_remote)
            DeviceLinkMode.Offline -> appCtx.getString(R.string.about_link_offline)
        }
        val ip = NasCommands.parseDeviceIp(statusJson).ifBlank { repo.getRuntimeIp(device.mac) }
        val fw = NasCommands.parseDeviceVersion(statusJson)
        val hdd = hddStatusLabel(NasCommands.parseHddStatusFromUserStatus(statusJson))
        val auth = userAuthorityLabel(NasCommands.parseUserAuthorityStatus(statusJson))
        val dash = "-"
        return listOf(
            appCtx.getString(R.string.about_row_name) to device.name.ifBlank { dash },
            appCtx.getString(R.string.about_row_mac) to device.mac.ifBlank { dash },
            appCtx.getString(R.string.about_row_link) to link,
            appCtx.getString(R.string.about_row_ip) to ip.ifBlank { dash },
            appCtx.getString(R.string.about_row_fw) to fw.ifBlank { dash },
            appCtx.getString(R.string.about_row_disk) to hdd,
            appCtx.getString(R.string.about_row_auth) to auth,
        )
    }

    private fun buildDiskRows(statusJson: String, userEmail: String): List<Pair<String, String>> {
        val profile = NasCommands.parseUserProfileFromStatus(statusJson, userEmail)
        return listOf(
            appCtx.getString(R.string.about_row_disk_total) to
                NasCommands.displayNasStorageRaw(appCtx, profile.hardDiskSpace),
            appCtx.getString(R.string.about_row_disk_remain) to
                NasCommands.displayNasStorageRaw(appCtx, profile.hardDiskRemain),
            appCtx.getString(R.string.about_row_disk_user_used) to
                NasCommands.displayNasStorageRaw(appCtx, profile.userStorage),
        )
    }

    private fun hddStatusLabel(raw: String): String = when (raw.trim()) {
        "", Constants.VAL_HDD_STATUS_OK -> appCtx.getString(R.string.about_hdd_ok)
        Constants.VAL_HDD_STATUS_READY -> appCtx.getString(R.string.about_hdd_ready)
        Constants.VAL_HDD_STATUS_UNINIT -> appCtx.getString(R.string.about_hdd_uninit)
        Constants.VAL_HDD_STATUS_INITING -> appCtx.getString(R.string.about_hdd_initing)
        Constants.VAL_HDD_STATUS_NONE -> appCtx.getString(R.string.about_hdd_none)
        Constants.VAL_HDD_STATUS_UMOUNT -> appCtx.getString(R.string.about_hdd_umount)
        else -> raw.ifBlank { "-" }
    }

    private fun userAuthorityLabel(raw: String): String = when (raw.trim()) {
        Constants.VAL_USER_AUTHORITY_PASS -> appCtx.getString(R.string.about_auth_pass)
        Constants.VAL_USER_AUTHORITY_DENIED -> appCtx.getString(R.string.about_auth_denied)
        Constants.VAL_LOGIN_STATUS_NONE -> appCtx.getString(R.string.about_auth_pending)
        "" -> "-"
        else -> raw
    }

    companion object {
        private const val TAG = "AboutVM"
    }
}
