@file:OptIn(ExperimentalMaterial3Api::class)

package com.ithingtalk.zhome.ui.screens.management

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.activity.ComponentActivity
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.local.db.DeviceEntity
import com.ithingtalk.zhome.data.remote.nas.NasCommands
import com.ithingtalk.zhome.data.repository.DeviceRepository
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

import java.io.IOException
import java.net.SocketTimeoutException

/**
 * ViewModel for device admin + user-admin + user-service screens.
 * Mirrors Qt DeviceManagmentPage / DeviceUserPage / UserServicePage.
 *
 * Admin password is kept after login for commands. [DeviceUsersScreen] is opened from the main menu
 * with an empty route password (user logs in there) or reuses this session if already logged in.
 */
class ManagementViewModel : ViewModel() {
    private val repo: DeviceRepository = ZhomeApp.instance.deviceRepo

    private val appCtx = ZhomeApp.instance.applicationContext

    /**
     * Short success strings shown inline on management screens — never raw errors or "timeout".
     * Non-fatal failures are logged only ([logNonFatal]); login failures use [adminLoginError] inline.
     */
    companion object {
        private const val TAG = "MgmtVM"
        fun inlineSuccessMessages(ctx: android.content.Context): Set<String> = setOf(
            ctx.getString(R.string.mgmt_success_device_name),
            ctx.getString(R.string.mgmt_success_password),
        )
    }

    var result by mutableStateOf(""); private set
    var busy by mutableStateOf(false); private set
    /**
     * Set after [initHdd] command succeeds — hide 磁盘格式化 until [refreshAdminDeviceStatusOnce] sees a non-[hdd_uninit] state.
     */
    var hddFormatPending by mutableStateOf(false); private set
    /** Admin login form only — inline text, no dialog. */
    var adminLoginError by mutableStateOf<String?>(null); private set
    /** User service auto-login only — inline text, no dialog. */


    /**
     * User management: after allow/reject/delete hits HTTP timeout, the NAS may still apply the command;
     * we re-fetch the list and show this hint so the UI is not stuck on stale rows.
     */
    var userAdminListHint by mutableStateOf<String?>(null); private set
    /** When true, [userAdminListHint] is shown as error color (e.g. timeout). */
    var userAdminListHintIsError by mutableStateOf(false); private set

    /** Auto-dismiss success toast on user management (allow/reject/delete) — no tap required. */
    var userActionToast by mutableStateOf<String?>(null); private set
    private var userActionToastJob: Job? = null

    // ---- Admin state ----
    var adminLoggedIn by mutableStateOf(false); private set
    var adminPasswd by mutableStateOf(""); private set
    var currentDeviceName by mutableStateOf(""); private set
    var currentDeviceMac by mutableStateOf(""); private set
    var hddStatus by mutableStateOf(""); private set
    var hddFormatProgress by mutableStateOf(""); private set
    var userCount by mutableStateOf(-1); private set

    var userList by mutableStateOf<List<NasUser>>(emptyList()); private set



    private fun userAlertMessageFromThrowable(e: Throwable): String {
        val raw = (e.message ?: "").trim()
        if (raw.isEmpty()) return appCtx.getString(R.string.mgmt_err_generic)
        val low = raw.lowercase()
        if (low == "timeout" || low.contains("timeout") || low.contains("timed out") || low.contains("read timed out")) {
            return appCtx.getString(R.string.mgmt_err_timeout)
        }
        return if (raw.length > 500) raw.take(500) + "…" else raw
    }

    private fun userAlertMessageFromNasFailure(resp: String): String {
        val t = resp.trim()
        if (t.isEmpty()) return appCtx.getString(R.string.mgmt_err_device_reply_empty)
        return if (t.length > 400) t.take(400) + "…" else t
    }

    /** Non-fatal issues: log to Logcat for debugging — do not show AlertDialog. */
    private fun logNonFatal(what: String, throwable: Throwable? = null) {
        if (throwable != null) Log.w(TAG, what, throwable) else Log.w(TAG, what)
    }

    /** True when the admin command likely reached the device but the client did not get a full HTTP response. */
    private fun isLikelyNetworkTimeoutOrStaleConnection(e: Throwable): Boolean {
        var x: Throwable? = e
        while (x != null) {
            when (x) {
                is SocketTimeoutException -> return true
                is IOException -> {
                    val m = x.message?.lowercase().orEmpty()
                    if (m.contains("timeout") ||
                        m.contains("unexpected end of stream") ||
                        m.contains("socket closed") ||
                        m.contains("connection reset") ||
                        m.contains("broken pipe")
                    ) {
                        return true
                    }
                }
            }
            x = x.cause
        }
        return false
    }

    private suspend fun postCommand(device: DeviceEntity, json: String): String =
        repo.postDeviceCommand(device, json, httpUser = "admin", httpPass = adminPasswd)

    /** LAN read 5s + remote wait 5s for allow/reject/delete; on timeout we refresh the list once. */
    private suspend fun postUserAdminMutation(device: DeviceEntity, json: String): String =
        repo.postDeviceCommand(
            device,
            json,
            httpUser = "admin",
            httpPass = adminPasswd,
            remoteTimeoutMs = 5_000L,
            lanReadTimeoutSec = 5L,
        )

    // ---- Admin login (DeviceManagmentPage.qml) ----

    fun adminLogin(adminPass: String) {
        viewModelScope.launch {
            busy = true; result = ""; adminLoginError = null
            try {
                val device = repo.getCurrent() ?: run {
                    adminLoginError = appCtx.getString(R.string.mgmt_no_device)
                    logNonFatal("adminLogin: no current device")
                    busy = false
                    return@launch
                }
                val resp = postCommand(device, NasCommands.adminLogin(adminPass))
                when {
                    NasCommands.adminLoginSuccess(resp) -> {
                        adminLoggedIn = true
                        adminPasswd = adminPass
                        currentDeviceName = device.name
                        currentDeviceMac = device.mac
                        try {
                            fetchUserListOnce()
                        } catch (e: Exception) {
                            logNonFatal("fetchUserListAfterAdminLogin failed", e)
                        }
                        busy = false
                    }
                    resp.isBlank() -> {
                        adminLoginError = appCtx.getString(R.string.mgmt_err_admin_timeout)
                        logNonFatal("adminLogin: empty response (remote timeout or no reply)")
                        busy = false
                    }
                    else -> {
                        adminLoginError = appCtx.getString(R.string.mgmt_wrong_admin_password)
                        logNonFatal("adminLogin: check_admin_login not success, respLen=${resp.length}")
                        busy = false
                    }
                }
            } catch (e: Exception) {
                val msg = userAlertMessageFromThrowable(e)
                adminLoginError = msg
                logNonFatal("adminLogin failed: $msg", e)
                busy = false
            }
        }
    }

    /**
     * Called by DeviceUsersScreen when adminPass is passed via route.
     * If already logged in (VM is activity-scoped and DeviceManagementScreen already
     * authenticated), returns immediately — user list is already populated.
     * Otherwise logs in and fetches only the user list (single remote command).
     */
    fun initAuthenticated(adminPass: String) {
        if (adminLoggedIn) return
        if (adminPass.isBlank()) return
        adminPasswd = adminPass
        adminLoggedIn = true
        viewModelScope.launch {
            val device = repo.getCurrent() ?: return@launch
            currentDeviceName = device.name
        }
        refreshUserListOnly()
    }

    /**
     * Qt [Logic.getDeviceUsersListModel]: same JSON shape for `get_user_list` and allow/reject/delete responses
     * (`user_list`, optional `user_num` / `user_total`). Skips if `user_list` absent — other commands must not wipe list.
     */
    /** @return true if [resp] contained `user_list` and the in-memory list was updated. */
    private fun applyUserListFromNasJson(resp: String): Boolean {
        if (!NasCommands.jsonHasUserList(resp)) return false
        val parsed = NasCommands.parseUserList(resp).map {
            NasUser(it.email, it.status, it.storage, it.nickname)
        }
        userList = parsed
        val uc = NasCommands.parseUserCount(resp)
        userCount = when {
            uc >= 0 -> uc
            parsed.isNotEmpty() -> parsed.size
            else -> userCount
        }
        return true
    }

    private fun showTransientUserActionToast(message: String) {
        userActionToastJob?.cancel()
        userActionToast = message
        userActionToastJob = viewModelScope.launch {
            delay(1_000)
            userActionToast = null
        }
    }

    /**
     * Qt [DeviceManagmentPage] onDataReceived: HDD + user count/list from one JSON (idevice_key_get_admin_device_status
     * or any reply that includes the same fields, e.g. init_disk).
     */
    private fun applyAdminDeviceStatusFromJson(resp: String) {
        val hs = NasCommands.parseHddStatus(resp)
        if (hs.isNotBlank()) {
            if (hs == Constants.VAL_HDD_STATUS_OK && hddStatus != Constants.VAL_HDD_STATUS_OK) {
                clearResult()
            }
            hddStatus = hs
            hddFormatProgress = NasCommands.parseFormatProgress(resp)
        }
        if (hddFormatPending && hs.isNotBlank() && hs != "hdd_uninit") {
            hddFormatPending = false
        }
        applyUserListFromNasJson(resp)
    }

    /** Refresh list only — separate round-trip (toolbar Refresh). */
    private suspend fun fetchUserListOnce() {
        if (!adminLoggedIn) return
        val device = repo.getCurrent() ?: return
        val listResp = postCommand(device, NasCommands.getUserList(adminPasswd))
        applyUserListFromNasJson(listResp)
    }

    /** Fetches only the user list — single remote command. */
    fun refreshUserListOnly() {
        viewModelScope.launch {
            userAdminListHint = null
            userAdminListHintIsError = false
            busy = true
            try {
                fetchUserListOnce()
            } catch (e: Exception) {
                logNonFatal("refreshUserListOnly failed", e)
            } finally {
                busy = false
            }
        }
    }

    /**
     * Single fetch: [CMD_KEY_GET_ADMIN_DEVICE_STATUS] — same as Qt [Logic.sendCmdGetAdminDeviceStatus] / [getAdminDeviceStatus].
     */
    suspend fun refreshAdminDeviceStatusOnce() {
        if (!adminLoggedIn) return
        val device = repo.getCurrent() ?: return
        try {
            val resp = postCommand(device, NasCommands.getAdminDeviceStatus(adminPasswd))
            applyAdminDeviceStatusFromJson(resp)
        } catch (e: Exception) {
            logNonFatal("refreshAdminDeviceStatusOnce failed", e)
        }
    }

    fun allowUser(user: String) = adminUserAction { NasCommands.allowUser(adminPasswd, user) }
    fun rejectUser(user: String) = adminUserAction { NasCommands.rejectUser(adminPasswd, user) }
    fun deleteUser(user: String) = adminUserAction { NasCommands.deleteUser(adminPasswd, user) }

    private fun adminUserAction(buildCmd: () -> String) {
        viewModelScope.launch {
            busy = true
            result = ""
            userAdminListHint = null
            userAdminListHintIsError = false
            try {
                val device = repo.getCurrent() ?: run { busy = false; return@launch }
                // Qt DeviceUserPage: one send → onDataReceived(strResult) → getDeviceUsersListModel(strResult).
                val resp = postUserAdminMutation(device, buildCmd())
                if (applyUserListFromNasJson(resp)) {
                    showTransientUserActionToast(appCtx.getString(R.string.mgmt_toast_op_success))
                }
            } catch (e: Exception) {
                logNonFatal("adminUserAction: ${e.message}", e)
                if (isLikelyNetworkTimeoutOrStaleConnection(e)) {
                    try {
                        fetchUserListOnce()
                    } catch (e2: Exception) {
                        logNonFatal("adminUserAction: list sync after timeout failed", e2)
                        userAdminListHint = appCtx.getString(R.string.mgmt_user_list_hint_timeout)
                        userAdminListHintIsError = true
                    }
                }
            } finally {
                busy = false
            }
        }
    }

    fun changeDeviceName(newName: String) {
        viewModelScope.launch {
            busy = true; result = ""
            try {
                val device = repo.getCurrent() ?: run {
                    logNonFatal("changeDeviceName: no device")
                    return@launch
                }
                val resp = postCommand(device, NasCommands.changeDeviceName(adminPasswd, newName))
                if (NasCommands.changeDeviceNameSuccess(resp)) {
                    currentDeviceName = newName
                    repo.updateName(device.mac, newName)
                    result = appCtx.getString(R.string.mgmt_success_device_name)
                    launch {
                        try {
                            repo.updateToCloud(device.mac, device.sn, newName)
                        } catch (e: Exception) {
                            Log.w(TAG, "Cloud device name sync failed", e)
                        }
                    }
                } else {
                    logNonFatal("changeDeviceName failed: ${userAlertMessageFromNasFailure(resp)}")
                }
            } catch (e: Exception) {
                logNonFatal("changeDeviceName: ${userAlertMessageFromThrowable(e)}", e)
            } finally {
                busy = false
            }
        }
    }

    fun changeAdminPass(newPass: String) {
        viewModelScope.launch {
            busy = true; result = ""
            try {
                val device = repo.getCurrent() ?: run {
                    logNonFatal("changeAdminPass: no device")
                    return@launch
                }
                val resp = postCommand(device, NasCommands.changeAdminPass(adminPasswd, newPass))
                when {
                    NasCommands.changeAdminPassSuccess(resp) -> {
                        adminPasswd = newPass
                        result = appCtx.getString(R.string.mgmt_success_password)
                    }
                    NasCommands.changeAdminPassFail(resp) -> {
                        logNonFatal("changeAdminPass: format rejected by NAS")
                    }
                    else -> {
                        logNonFatal("changeAdminPass failed: ${userAlertMessageFromNasFailure(resp)}")
                    }
                }
            } catch (e: Exception) {
                logNonFatal("changeAdminPass: ${userAlertMessageFromThrowable(e)}", e)
            } finally {
                busy = false
            }
        }
    }

    fun initHdd() {
        viewModelScope.launch {
            busy = true
            result = ""
            try {
                val device = repo.getCurrent() ?: run {
                    logNonFatal("initHdd: no device")
                    busy = false
                    return@launch
                }
                val resp = postCommand(device, NasCommands.initHdd(adminPasswd))
                hddFormatPending = true
                applyAdminDeviceStatusFromJson(resp)
            } catch (e: Exception) {
                logNonFatal("initHdd: ${userAlertMessageFromThrowable(e)}", e)
                hddFormatPending = false
            } finally {
                busy = false
            }
        }
    }



    fun clearAdminLoginError() { adminLoginError = null }

    /** Clears leftover [result] from other screens sharing this Activity-scoped VM (e.g. User Services). */
    fun clearResult() {
        result = ""
        userAdminListHint = null
        userAdminListHintIsError = false
        userActionToastJob?.cancel()
        userActionToast = null
    }

    /** Call when switching devices or signing out so stale admin session is not reused. */
    fun resetAdminSession() {
        hddFormatPending = false
        adminLoggedIn = false
        adminPasswd = ""
        currentDeviceName = ""
        currentDeviceMac = ""
        hddStatus = ""
        hddFormatProgress = ""
        userCount = -1
        userList = emptyList()
        result = ""
        adminLoginError = null
        userAdminListHint = null
        userAdminListHintIsError = false
        userActionToastJob?.cancel()
        userActionToast = null
    }
}

// ---- Screens ----

/**
 * Device administration: disk status / format, device name, admin password.
 * User list is on [DeviceUsersScreen] (separate entry from the main menu).
 */
@Composable
fun DeviceManagementScreen(
    initialAdminPass: String = "",
    autoLogin: Boolean = false,
    onBack: () -> Unit,
    onUserManagement: (String) -> Unit = {},
    onReplaceFinished: () -> Unit = {},
    vm: ManagementViewModel = viewModel(LocalContext.current as ComponentActivity),
) {
    var adminPassInput by remember { mutableStateOf("") }
    var deviceNameEdit by remember { mutableStateOf("") }
    var adminPassEdit by remember { mutableStateOf("") }
    var adminPassInputVisible by remember { mutableStateOf(false) }
    var adminPassEditVisible by remember { mutableStateOf(false) }
    var showLoginConfirm by remember { mutableStateOf(false) }
    var showChangeNameConfirm by remember { mutableStateOf(false) }
    var showChangePassConfirm by remember { mutableStateOf(false) }
    var showInitConfirm by remember { mutableStateOf(false) }
    var showReplaceWizard by remember { mutableStateOf(false) }

    // Reset admin session when device has changed since last management visit
    LaunchedEffect(Unit) {
        val currentMac = ZhomeApp.instance.prefs.getCurrDeviceMac()
        if (vm.adminLoggedIn && vm.currentDeviceMac.isNotBlank() && vm.currentDeviceMac != currentMac) {
            vm.resetAdminSession()
        }
    }

    val mgmtScope = rememberCoroutineScope()
    // Qt DeviceManagmentPage: login → getAdminDeviceStatus once; if HDD not ready, Timer 3s → getAdminDeviceStatus again.
    // When hdd_ok, no periodic poll (toolbar Refresh still calls [refreshAdminDeviceStatusOnce]).
    LaunchedEffect(vm.adminLoggedIn) {
        if (!vm.adminLoggedIn) return@LaunchedEffect
        vm.refreshAdminDeviceStatusOnce()
        while (vm.adminLoggedIn && vm.hddStatus != Constants.VAL_HDD_STATUS_OK) {
            delay(3000)
            if (!vm.adminLoggedIn) break
            vm.refreshAdminDeviceStatusOnce()
        }
    }

    // Post-config flow: auto-login once using the admin password just entered on configure page.
    var didAutoLogin by remember { mutableStateOf(false) }
    LaunchedEffect(autoLogin, initialAdminPass, vm.adminLoggedIn) {
        if (!didAutoLogin && autoLogin && initialAdminPass.isNotBlank() && !vm.adminLoggedIn) {
            didAutoLogin = true
            adminPassInput = initialAdminPass
            vm.adminLogin(initialAdminPass)
        }
    }

    LaunchedEffect(vm.currentDeviceName) {
        if (vm.adminLoggedIn) deviceNameEdit = vm.currentDeviceName
    }
    LaunchedEffect(vm.adminPasswd) {
        if (vm.adminLoggedIn) adminPassEdit = vm.adminPasswd
    }

    // ---- Confirm dialogs (matching Qt ConfirmDialog pattern) ----

    if (showLoginConfirm) {
        AlertDialog(
            onDismissRequest = { showLoginConfirm = false },
            title = { Text(stringResource(R.string.mgmt_login_confirm_title)) },
            text = { Text(adminPassInput) },
            confirmButton = {
                TextButton(onClick = {
                    showLoginConfirm = false
                    vm.adminLogin(adminPassInput)
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showLoginConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showChangeNameConfirm) {
        AlertDialog(
            onDismissRequest = { showChangeNameConfirm = false },
            title = { Text(stringResource(R.string.mgmt_change_name_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.mgmt_confirm_change_preview,
                        vm.currentDeviceName,
                        deviceNameEdit,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showChangeNameConfirm = false
                    vm.changeDeviceName(deviceNameEdit)
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showChangeNameConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showChangePassConfirm) {
        AlertDialog(
            onDismissRequest = { showChangePassConfirm = false },
            title = { Text(stringResource(R.string.mgmt_change_pass_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.mgmt_confirm_change_preview,
                        vm.adminPasswd,
                        adminPassEdit,
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showChangePassConfirm = false
                    vm.changeAdminPass(adminPassEdit)
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showChangePassConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    if (showInitConfirm) {
        AlertDialog(
            onDismissRequest = { showInitConfirm = false },
            title = { Text(stringResource(R.string.mgmt_disk_format_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.mgmt_disk_format_body,
                        stringResource(R.string.mgmt_disk_format_safety),
                    ),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showInitConfirm = false
                    vm.initHdd()
                }) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showInitConfirm = false }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mgmt_device_mgmt_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            stringResource(R.string.common_back),
                        )
                    }
                },
                actions = {
                    if (vm.adminLoggedIn && vm.hddStatus == Constants.VAL_HDD_STATUS_OK) {
                        IconButton(
                            onClick = { mgmtScope.launch { vm.refreshAdminDeviceStatusOnce() } },
                            enabled = !vm.busy,
                        ) {
                            Icon(Icons.Default.Refresh, stringResource(R.string.common_refresh))
                        }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(Icons.Default.MoreVert, stringResource(R.string.devices_cd_menu))
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false },
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.mgmt_reset_session)) },
                                    onClick = { showMenu = false; vm.resetAdminSession() },
                                    leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                                )
                            }
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp),
        ) {
            if (showReplaceWizard && vm.adminLoggedIn) {
                DiskReplaceWizardPanel(
                    adminPass = vm.adminPasswd,
                    onCancel = { showReplaceWizard = false },
                    onFinished = {
                        showReplaceWizard = false
                        onReplaceFinished()
                    },
                )
            } else if (!vm.adminLoggedIn) {
                // Phase 1: Login — centered, matches Qt ColumnLayout visible: !loginSuccess
                Column(
                    Modifier
                        .align(Alignment.Center)
                        .fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Text(
                        stringResource(R.string.mgmt_admin_password_lead),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    OutlinedTextField(
                        value = adminPassInput,
                        onValueChange = {
                            adminPassInput = it
                            vm.clearAdminLoginError()
                        },
                        label = { Text(stringResource(R.string.mgmt_admin_password)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        visualTransformation = if (adminPassInputVisible) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = { adminPassInputVisible = !adminPassInputVisible }) {
                                Icon(
                                    if (adminPassInputVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                    contentDescription = if (adminPassInputVisible) {
                                        stringResource(R.string.common_hide_password)
                                    } else {
                                        stringResource(R.string.common_show_password)
                                    },
                                )
                            }
                        },
                    )
                    Button(
                        onClick = { showLoginConfirm = true },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = adminPassInput.isNotBlank() && !vm.busy,
                    ) {
                        if (vm.busy) {
                            CircularProgressIndicator(
                                Modifier.size(18.dp),
                                strokeWidth = 2.dp,
                                color = MaterialTheme.colorScheme.onPrimary,
                            )
                            Spacer(Modifier.width(8.dp))
                        }
                        Text(stringResource(R.string.mgmt_login))
                    }
                    vm.adminLoginError?.let { err ->
                        Text(
                            err,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            } else {
                val diskReady = vm.hddStatus == Constants.VAL_HDD_STATUS_OK
                val hddStateLabel = when {
                    vm.hddStatus.isBlank() -> stringResource(R.string.mgmt_hdd_loading)
                    vm.hddStatus == Constants.VAL_HDD_STATUS_OK -> stringResource(R.string.mgmt_hdd_ready)
                    vm.hddFormatPending && vm.hddStatus == "hdd_uninit" ->
                        stringResource(R.string.mgmt_hdd_format_wait)
                    vm.hddStatus == "hdd_uninit" -> stringResource(R.string.mgmt_hdd_uninit)
                    vm.hddStatus == "hdd_initing" -> stringResource(R.string.mgmt_hdd_formatting)
                    else -> vm.hddStatus
                }
                val formatPercentText = NasCommands.formatProgressPercentText(vm.hddFormatProgress)
                val formatFraction = NasCommands.formatProgressFraction(vm.hddFormatProgress)
                /** true while waiting after init command, or NAS reports [hdd_initing]. */
                val showFormatProgressBar =
                    vm.hddStatus == "hdd_initing" ||
                        (vm.hddFormatPending && vm.hddStatus == "hdd_uninit")
                val hddColor =
                    if (vm.hddStatus == Constants.VAL_HDD_STATUS_OK) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.error
                /** From confirm / command success until [hdd_ok] — matches 格式化期间. */
                val showFormatSafetyHint =
                    vm.hddFormatPending || vm.hddStatus == "hdd_initing"

                if (!diskReady) {
                    Column(Modifier.fillMaxSize()) {
                        if (showFormatSafetyHint) {
                            Text(
                                stringResource(R.string.mgmt_disk_format_safety),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(bottom = 8.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error,
                                textAlign = TextAlign.Start,
                            )
                        }
                        Box(
                            Modifier
                                .weight(1f)
                                .fillMaxWidth(),
                            contentAlignment = Alignment.Center,
                        ) {
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                verticalArrangement = Arrangement.spacedBy(16.dp),
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp),
                            ) {
                                Text(
                                    stringResource(R.string.mgmt_disk_state_label, hddStateLabel),
                                    color = hddColor,
                                    style = MaterialTheme.typography.bodyLarge,
                                )
                                if (formatPercentText.isNotBlank() && showFormatProgressBar) {
                                    Text(
                                        formatPercentText,
                                        style = MaterialTheme.typography.headlineSmall,
                                        color = MaterialTheme.colorScheme.primary,
                                    )
                                }
                                if (showFormatProgressBar) {
                                    if (formatFraction != null) {
                                        LinearProgressIndicator(
                                            progress = { formatFraction },
                                            modifier = Modifier.fillMaxWidth(),
                                        )
                                    } else {
                                        LinearProgressIndicator(Modifier.fillMaxWidth())
                                    }
                                }
                                if (vm.hddStatus == "hdd_uninit" && !vm.hddFormatPending) {
                                    Button(
                                        onClick = { showInitConfirm = true },
                                        enabled = !vm.busy && !vm.hddFormatPending,
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = MaterialTheme.colorScheme.error,
                                        ),
                                    ) { Text(stringResource(R.string.mgmt_disk_format_button)) }
                                }
                            }
                        }
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = deviceNameEdit,
                            onValueChange = { deviceNameEdit = it },
                            label = { Text(stringResource(R.string.mgmt_device_name_label)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.mgmt_loading_placeholder)) },
                            enabled = !vm.busy || deviceNameEdit.isNotBlank(),
                        )
                        Button(
                            onClick = { showChangeNameConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = deviceNameEdit.isNotBlank()
                                && deviceNameEdit != vm.currentDeviceName
                                && !vm.busy,
                        ) { Text(stringResource(R.string.mgmt_change_device_name)) }

                        Spacer(Modifier.height(16.dp))
                        OutlinedTextField(
                            value = adminPassEdit,
                            onValueChange = { adminPassEdit = it },
                            label = { Text(stringResource(R.string.mgmt_admin_password)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            placeholder = { Text(stringResource(R.string.mgmt_loading_placeholder)) },
                            visualTransformation = if (adminPassEditVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { adminPassEditVisible = !adminPassEditVisible }) {
                                    Icon(
                                        if (adminPassEditVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (adminPassEditVisible) {
                                            stringResource(R.string.common_hide_password)
                                        } else {
                                            stringResource(R.string.common_show_password)
                                        },
                                    )
                                }
                            },
                        )
                        Button(
                            onClick = { showChangePassConfirm = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = adminPassEdit.isNotBlank()
                                && adminPassEdit != vm.adminPasswd
                                && !vm.busy,
                        ) { Text(stringResource(R.string.mgmt_change_admin_password)) }

                        Spacer(Modifier.height(16.dp))
                        Text(
                            stringResource(R.string.mgmt_replace_disk_title),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(
                            stringResource(R.string.mgmt_replace_disk_subtitle),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.outline,
                        )
                        Button(
                            onClick = { showReplaceWizard = true },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !vm.busy,
                        ) { Text(stringResource(R.string.mgmt_replace_disk_btn)) }

                        Spacer(Modifier.height(16.dp))
                        HorizontalDivider()
                        ListItem(
                            headlineContent = { Text(stringResource(R.string.mgmt_user_mgmt_title)) },
                            leadingContent = { Icon(Icons.Default.ManageAccounts, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(Icons.Default.ChevronRight, null) },
                            modifier = Modifier.clickable { onUserManagement(vm.adminPasswd) }
                        )
                        HorizontalDivider()

                        if (vm.result in ManagementViewModel.inlineSuccessMessages(LocalContext.current)) {
                            Text(vm.result, color = MaterialTheme.colorScheme.primary)
                        }
                    }
                }
            }
        }
    }
}

/**
 * User management (allow / reject / delete). Standalone screen: open from main menu with empty [adminPass]
 * to log in, or pass [adminPass] after device management if sharing the same session.
 */
@Composable
fun DeviceUsersScreen(
    adminPass: String = "",
    onBack: () -> Unit,
    vm: ManagementViewModel = viewModel(LocalContext.current as ComponentActivity),
) {
    var deleteConfirm by remember { mutableStateOf<NasUser?>(null) }
    var rejectConfirm by remember { mutableStateOf<NasUser?>(null) }
    var allowConfirm by remember { mutableStateOf<NasUser?>(null) }

    LaunchedEffect(adminPass) {
        if (adminPass.isNotBlank()) {
            vm.initAuthenticated(adminPass)
        }
    }

    DisposableEffect(Unit) {
        if (vm.adminLoggedIn) {
            vm.refreshUserListOnly()
        }
        onDispose { vm.clearResult() }
    }

    deleteConfirm?.let { u ->
        AlertDialog(
            onDismissRequest = { deleteConfirm = null },
            title = { Text(stringResource(R.string.mgmt_delete_user_title)) },
            text = { Text("${u.username}\n\n${u.storage.ifBlank { "" }}") },
            confirmButton = {
                TextButton(onClick = {
                    deleteConfirm = null
                    vm.deleteUser(u.username)
                }) { Text(stringResource(R.string.mgmt_delete), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirm = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    rejectConfirm?.let { u ->
        AlertDialog(
            onDismissRequest = { rejectConfirm = null },
            title = { Text(stringResource(R.string.mgmt_reject_user_title)) },
            text = { Text(u.username) },
            confirmButton = {
                TextButton(onClick = {
                    rejectConfirm = null
                    vm.rejectUser(u.username)
                }) { Text(stringResource(R.string.mgmt_reject), color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = {
                TextButton(onClick = { rejectConfirm = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    allowConfirm?.let { u ->
        AlertDialog(
            onDismissRequest = { allowConfirm = null },
            title = { Text(stringResource(R.string.mgmt_allow_user_title)) },
            text = { Text(u.username) },
            confirmButton = {
                TextButton(onClick = {
                    allowConfirm = null
                    vm.allowUser(u.username)
                }) { Text(stringResource(R.string.mgmt_allow)) }
            },
            dismissButton = {
                TextButton(onClick = { allowConfirm = null }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }

    var showMenu by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mgmt_user_mgmt_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
                actions = {
                    // Refresh button always visible, left of menu button (Qt-style).
                    IconButton(
                        onClick = { vm.refreshUserListOnly() },
                        enabled = vm.adminLoggedIn && !vm.busy,
                    ) { Icon(Icons.Default.Refresh, stringResource(R.string.common_refresh)) }

                    Box {
                        IconButton(onClick = { showMenu = true }) {
                            Icon(Icons.Default.MoreVert, stringResource(R.string.devices_cd_menu))
                        }
                        DropdownMenu(
                            expanded = showMenu,
                            onDismissRequest = { showMenu = false },
                        ) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.mgmt_reset_session)) },
                                onClick = { showMenu = false; vm.resetAdminSession() },
                                leadingIcon = { Icon(Icons.Default.RestartAlt, null) },
                            )
                        }
                    }
                },
            )
        },
    ) { padding ->
        Box(
            Modifier
                .fillMaxSize()
                .padding(padding),
        ) {
            Column(Modifier.fillMaxSize()) {
            if (!vm.adminLoggedIn) {
                // Standalone entry: admin password vertically centered on screen
                var localAdminPass by remember { mutableStateOf("") }
                var localAdminPassVisible by remember { mutableStateOf(false) }
                Box(
                    Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        OutlinedTextField(
                            value = localAdminPass,
                            onValueChange = { localAdminPass = it },
                            label = { Text(stringResource(R.string.mgmt_admin_password)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation = if (localAdminPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                            trailingIcon = {
                                IconButton(onClick = { localAdminPassVisible = !localAdminPassVisible }) {
                                    Icon(
                                        if (localAdminPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                                        contentDescription = if (localAdminPassVisible) {
                                            stringResource(R.string.common_hide_password)
                                        } else {
                                            stringResource(R.string.common_show_password)
                                        },
                                    )
                                }
                            },
                        )
                        Button(
                            onClick = { vm.adminLogin(localAdminPass) },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = localAdminPass.isNotBlank() && !vm.busy,
                        ) { Text(stringResource(R.string.mgmt_login)) }
                    }
                }
            } else {
                Column(Modifier.weight(1f).fillMaxWidth()) {
                    if (vm.busy) LinearProgressIndicator(Modifier.fillMaxWidth())

                    if (vm.userList.isEmpty()) {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .weight(1f),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(stringResource(R.string.mgmt_no_users), color = MaterialTheme.colorScheme.outline)
                        }
                    } else {
                        LazyColumn(Modifier.fillMaxSize()) {
                        items(vm.userList, key = { it.username }) { user ->
                            val statusLabel = when (user.status) {
                                Constants.VAL_USER_AUTHORITY_PASS -> stringResource(R.string.mgmt_user_status_allowed)
                                Constants.VAL_USER_AUTHORITY_DENIED -> stringResource(R.string.mgmt_user_status_denied)
                                Constants.VAL_LOGIN_STATUS_NONE -> stringResource(R.string.mgmt_user_status_pending)
                                else -> user.status
                            }
                            val statusColor = when (user.status) {
                                Constants.VAL_USER_AUTHORITY_PASS -> Color(0xFF2E7D32)
                                Constants.VAL_USER_AUTHORITY_DENIED -> MaterialTheme.colorScheme.error
                                else -> MaterialTheme.colorScheme.outline
                            }
                            ListItem(
                                headlineContent = {
                                    Text(user.nickname.ifBlank { user.username })
                                },
                                supportingContent = {
                                    val dash = stringResource(R.string.common_em_dash)
                                    Text("$statusLabel · ${user.storage.ifBlank { dash }}")
                                },
                                leadingContent = {
                                    Icon(Icons.Default.Person, null, tint = statusColor)
                                },
                                trailingContent = {
                                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                                        if (user.status != Constants.VAL_USER_AUTHORITY_PASS) {
                                            IconButton(
                                                onClick = { allowConfirm = user },
                                                enabled = !vm.busy,
                                            ) {
                                                Icon(
                                                    Icons.Default.Check,
                                                    stringResource(R.string.mgmt_cd_allow_user),
                                                    tint = Color(0xFF2E7D32),
                                                )
                                            }
                                        }
                                        if (user.status == Constants.VAL_USER_AUTHORITY_PASS) {
                                            IconButton(
                                                onClick = { deleteConfirm = user },
                                                enabled = !vm.busy,
                                            ) {
                                                Icon(
                                                    Icons.Default.Delete,
                                                    stringResource(R.string.mgmt_cd_delete_user),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        } else {
                                            IconButton(
                                                onClick = { rejectConfirm = user },
                                                enabled = !vm.busy,
                                            ) {
                                                Icon(
                                                    Icons.Default.Block,
                                                    stringResource(R.string.mgmt_cd_reject_user),
                                                    tint = MaterialTheme.colorScheme.error,
                                                )
                                            }
                                        }
                                    }
                                },
                            )
                            HorizontalDivider()
                        }
                        }
                    }
                }
            }
            if (vm.result in ManagementViewModel.inlineSuccessMessages(LocalContext.current)) {
                Text(
                    vm.result,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(16.dp),
                )
            }
            vm.userAdminListHint?.let { hint ->
                val isError = vm.userAdminListHintIsError
                Text(
                    hint,
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp).padding(bottom = 8.dp),
                )
            }
            }
            vm.userActionToast?.let { msg ->
                Surface(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 24.dp),
                    shape = RoundedCornerShape(8.dp),
                    color = MaterialTheme.colorScheme.inverseSurface,
                    contentColor = MaterialTheme.colorScheme.inverseOnSurface,
                    shadowElevation = 4.dp,
                ) {
                    Text(
                        msg,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    }
}

/**
 * Mirrors Qt DeviceConfigureNewPage.qml — first-time device setup.
 */
@Composable
fun DeviceConfigureScreen(
    mac: String,
    sn: String,
    initialName: String,
    ip: String,
    onBack: () -> Unit,
    onConfigured: (String) -> Unit,
) {
    val resCtx = LocalContext.current
    val repo = remember { ZhomeApp.instance.deviceRepo }
    val prefs = remember { ZhomeApp.instance.prefs }
    val authRepo = remember { ZhomeApp.instance.authRepo }
    val scope = rememberCoroutineScope()
    var adminPass by remember { mutableStateOf("") }
    var adminPassVisible by remember { mutableStateOf(false) }
    var deviceName by remember { mutableStateOf(initialName) }
    var userEmail by remember { mutableStateOf("") }
    var userPass by remember { mutableStateOf("") }
    var cognitoIdentityId by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf("") }
    var errorMsg by remember { mutableStateOf<String?>(null) }
    var showConfirm by remember { mutableStateOf(false) }

    LaunchedEffect(mac, ip) {
        repo.setRuntimeIp(mac, ip, markOnline = true)
        userEmail = prefs.getUser()
        userPass = prefs.getPass()
        cognitoIdentityId = prefs.getIdentityId()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.mgmt_configure_new_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, stringResource(R.string.common_back))
                    }
                },
            )
        },
    ) { padding ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(16.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                stringResource(R.string.mgmt_ip_label, ip),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            OutlinedTextField(
                value = adminPass,
                onValueChange = { adminPass = it },
                label = { Text(stringResource(R.string.mgmt_admin_password)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                visualTransformation = if (adminPassVisible) VisualTransformation.None else PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                trailingIcon = {
                    IconButton(onClick = { adminPassVisible = !adminPassVisible }) {
                        Icon(
                            if (adminPassVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
                            contentDescription = if (adminPassVisible) {
                                stringResource(R.string.common_hide_password)
                            } else {
                                stringResource(R.string.common_show_password)
                            },
                        )
                    }
                },
            )
            OutlinedTextField(
                value = deviceName,
                onValueChange = { deviceName = it },
                label = { Text(stringResource(R.string.mgmt_device_name_label)) },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
            )
            if (userEmail.isNotBlank()) {
                Text(
                    stringResource(R.string.mgmt_user_label, userEmail),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Button(
                onClick = {
                    showConfirm = true
                },
                enabled = adminPass.isNotBlank() && deviceName.isNotBlank() && !busy,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.mgmt_configure_device)) }

            if (busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if (result.isNotBlank()) Text(result, color = MaterialTheme.colorScheme.primary)
            errorMsg?.let { Text(it, color = MaterialTheme.colorScheme.error) }
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text(stringResource(R.string.mgmt_configure_confirm_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.mgmt_configure_confirm_body,
                        deviceName,
                        adminPass,
                        userEmail,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showConfirm = false
                        busy = true
                        result = ""
                        errorMsg = null
                        scope.launch {
                            try {
                                repo.setRuntimeIp(mac, ip, markOnline = true)
                                val temp = com.ithingtalk.zhome.data.local.db.DeviceEntity(
                                    mac = mac,
                                    sn = sn,
                                    name = deviceName,
                                    cfg = "0",
                                )
                                if (userEmail.isBlank() || userPass.isBlank()) {
                                    errorMsg = resCtx.getString(R.string.file_not_signed_in)
                                    return@launch
                                }
                                // NAS expects Cognito IdentityId in user_id (Qt aligns to Identity Pool id).
                                var userId = cognitoIdentityId
                                if (userId.isBlank()) {
                                    runCatching { authRepo.getAwsCredentials() }
                                    userId = prefs.getIdentityId()
                                    cognitoIdentityId = userId
                                }
                                if (userId.isBlank()) {
                                    errorMsg = resCtx.getString(R.string.mgmt_err_no_cloud_user_id)
                                    return@launch
                                }
                                val cmd = NasCommands.configureNewDevice(adminPass, deviceName, userEmail, userPass, userId)
                                // LAN-only first configure (temp [DeviceEntity]); keep [postLocalCommand], not [postDeviceCommand].
                                val resp = repo.postLocalCommand(temp, cmd, httpUser = "admin", httpPass = adminPass)
                                result = resp
                                val ok = NasCommands.configureNewDeviceSuccess(resp)
                                if (!ok) {
                                    Log.w("DeviceConfigure", "configureNewDevice NAS response not success: ${resp.take(200)}")
                                    errorMsg = resCtx.getString(R.string.mgmt_err_configure_failed)
                                    return@launch
                                }
                                repo.setRuntimeIp(mac, ip, markOnline = true)
                                repo.addLocal(mac = mac, sn = sn, name = deviceName, cfg = "1")
                                repo.setCurrent(mac)
                                val addOk = runCatching { repo.addToCloud(mac, sn, deviceName) }.getOrDefault(false)
                                if (addOk) repo.markSynced(mac)
                                onConfigured(adminPass)
                            } catch (e: Exception) {
                                Log.w("DeviceConfigure", "configure failed", e)
                                errorMsg = e.message ?: resCtx.getString(R.string.shared_err_generic)
                            } finally {
                                busy = false
                            }
                        }
                    },
                    enabled = !busy,
                ) { Text(stringResource(R.string.common_confirm)) }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text(stringResource(R.string.common_cancel)) }
            },
        )
    }
}





data class NasUser(
    val username: String,
    val status: String,
    val storage: String,
    val nickname: String,
)
