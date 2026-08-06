package com.ithingtalk.zhome.ui.screens.content

import android.util.Log
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.local.db.DeviceEntity
import com.ithingtalk.zhome.data.local.db.FileEntity
import com.ithingtalk.zhome.data.local.db.RecentFileEntity
import com.ithingtalk.zhome.data.remote.nas.NasCommands
import com.ithingtalk.zhome.data.remote.nas.NasUserDbSync
import com.ithingtalk.zhome.data.remote.nas.NasUserDbSync.SyncFromDeviceResult
import com.ithingtalk.zhome.data.remote.nas.NasLocalClient
import com.ithingtalk.zhome.data.remote.p2p.RemoteLinkCoordinator
import com.ithingtalk.zhome.data.RemoteMediaType
import com.ithingtalk.zhome.data.classifyRemoteMediaType
import com.ithingtalk.zhome.data.repository.CategoryCounts
import com.ithingtalk.zhome.data.repository.DeviceLinkMode
import com.ithingtalk.zhome.data.repository.DeviceRefreshCoordinator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

class ContentViewModel : ViewModel() {
    private val deviceRepo = ZhomeApp.instance.deviceRepo
    private val fileRepo = ZhomeApp.instance.fileRepo
    private val transferRepo = ZhomeApp.instance.transferRepo
    private val prefs = ZhomeApp.instance.prefs
    private val appCtx = ZhomeApp.instance.applicationContext
    private val nasLocal: NasLocalClient = ZhomeApp.instance.nasLocal

    var device by mutableStateOf<DeviceEntity?>(null); private set
    var user by mutableStateOf(""); private set
    var userNickname by mutableStateOf(""); private set
    var userStorageDisplay by mutableStateOf(""); private set
    var categoryCounts by mutableStateOf<CategoryCounts?>(null); private set

    var connectionStatus by mutableStateOf<String?>(null); private set
    var isRefreshing by mutableStateOf(false); private set

    var searchQuery by mutableStateOf(""); private set
    var searchResults by mutableStateOf<List<FileEntity>>(emptyList()); private set

    var recentFiles by mutableStateOf<List<RecentFileEntity>>(emptyList()); private set

    var allDevices by mutableStateOf<List<DeviceEntity>>(emptyList()); private set
    var forceP2p by mutableStateOf(false); private set

    var currentMac by mutableStateOf(""); private set
    private var initialized = false
    private var recentFilesJob: Job? = null
    private var deviceListRefreshedOnce = false
    private var playbackPrewarmJob: Job? = null
    private var playbackPrewarmMac: String? = null

    var isConnectingDevice by mutableStateOf(false); private set
    /** First full sync after opening main failed; show dialog (replaces removed ConnectDevice screen). */
    var initialSyncFailedMessage by mutableStateOf<String?>(null); private set
    /** When true, dialog uses disk-init title and “open device management” action ([SyncFromDeviceResult.DiskUninitialized]). */
    var initialSyncFailedDiskUninit by mutableStateOf(false); private set
    /** Qt [ConnectDevicePage] `idNeedAllow` — show dialog then return to device list. */
    var pendingAdminApproval by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            deviceRepo.observeAll().collect { list ->
                allDevices = list
                // Keep current device in sync — picks up name changes made in ManagementScreen.
                val mac = device?.mac ?: return@collect
                list.find { it.mac == mac }?.let { device = it }
            }
        }
        viewModelScope.launch {
            prefs.observeForceP2p().collect { forceP2p = it }
        }
        viewModelScope.launch {
            prefs.observeUserNickname().collect { userNickname = it }
        }
        viewModelScope.launch {
            prefs.observeUserStorage().collect { raw ->
                userStorageDisplay = NasCommands.displayNasStorageRaw(
                    appCtx,
                    raw.takeIf { it.isNotBlank() && it != "0" },
                )
            }
        }
    }

    fun initIfNeeded(mac: String) {
        if (initialized) return
        initialized = true
        currentMac = mac
        loadDevice(mac)
        // Ensure Room file list belongs to this device immediately.
        viewModelScope.launch {
            reimportCachedFileDb(mac)
            reimportCachedTrashDb(mac)
            reimportCachedSharedDb(mac)
            loadFileCounts(mac)
        }
        collectRecentFiles(mac)
        loadConnectionStatusFromDb(mac)
        prewarmRemotePlayback(mac)
        // 1) Auto-refresh device list once (LAN scan + cloud pull).
        if (!deviceListRefreshedOnce) {
            deviceListRefreshedOnce = true
            refreshDeviceList()
        }
        refreshUserProfileFromNas(mac)
    }

    /**
     * Switch to a different device in-place, without navigation.
     * Re-imports the cached file.db for the new device, reloads
     * category counts, restarts recent-files observation, and
     * kicks off a background device status refresh.
     */
    fun switchDevice(newMac: String) {
        if (newMac == currentMac) return
        currentMac = newMac
        searchQuery = ""
        searchResults = emptyList()
        viewModelScope.launch {
            deviceRepo.setCurrent(newMac)
            device = deviceRepo.getByMac(newMac)
            reimportCachedFileDb(newMac)
            loadFileCounts(newMac)
        }
        collectRecentFiles(newMac)
        loadConnectionStatusFromDb(newMac)
        prewarmRemotePlayback(newMac)
        refreshUserProfileFromNas(newMac)
    }

    private suspend fun reimportCachedFileDb(mac: String) = withContext(Dispatchers.IO) {
        val owner = prefs.getUser()
        val cachedDb = com.ithingtalk.zhome.data.local.AppPaths
            .deviceFileDb(appCtx, owner, mac)
        if (cachedDb.isFile && owner.isNotBlank()) {
            try {
                fileRepo.importFromNasSqliteFile(cachedDb.absolutePath, owner)
                Log.i("ContentVM", "Re-imported cached file.db for device $mac")
            } catch (e: Exception) {
                Log.w("ContentVM", "Failed to re-import cached file.db for $mac", e)
            }
        } else {
            Log.i("ContentVM", "No cached file.db for device $mac, clearing counts")
            categoryCounts = CategoryCounts(0, 0, 0, 0, 0, 0, 0, 0)
        }
    }

    private suspend fun reimportCachedTrashDb(mac: String) = withContext(Dispatchers.IO) {
        val user = prefs.getUser()
        val cachedDb = com.ithingtalk.zhome.data.local.AppPaths
            .deviceFileDb(appCtx, user, mac)
        val owner = "__trash__$user"
        if (cachedDb.isFile && user.isNotBlank()) {
            try {
                fileRepo.importFromNasSqliteFileByStatus(cachedDb.absolutePath, owner, "inTrash")
                Log.i("ContentVM", "Re-imported cached trash entries for device $mac")
            } catch (e: Exception) {
                Log.w("ContentVM", "Failed to re-import cached trash entries for $mac", e)
            }
        }
    }

    private suspend fun reimportCachedSharedDb(mac: String) = withContext(Dispatchers.IO) {
        val user = prefs.getUser()
        val cachedDb = com.ithingtalk.zhome.data.local.AppPaths
            .deviceSharedDb(appCtx, user, mac)
        val owner = "__shared__$user"
        if (cachedDb.isFile && user.isNotBlank()) {
            try {
                fileRepo.importFromNasSharedSqliteFile(cachedDb.absolutePath, owner)
                Log.i("ContentVM", "Re-imported cached shared.db for device $mac")
            } catch (e: Exception) {
                Log.w("ContentVM", "Failed to re-import cached shared.db for $mac", e)
            }
        }
    }

    /**
     * Main-page "refresh" action: connect current device.
     *
     * Flow:
     * - userLogin
     * - idevice_key_get_status
     * - compare status DB timestamp vs cached `{filesDir}/{mac}/file.db` lastModified
     * - if needed: download `file.db` + import → update UI counts
     */
    fun connectCurrentDevice(mac: String) {
        viewModelScope.launch {
            if (isConnectingDevice) return@launch
            isConnectingDevice = true
            initialSyncFailedMessage = null
            initialSyncFailedDiskUninit = false
            pendingAdminApproval = false
            try {
                val r = withContext(Dispatchers.IO) { NasUserDbSync.syncFromDevice(mac) }
                when (r) {
                    SyncFromDeviceResult.Success -> {
                        prefs.removePendingUserApprovalMac(mac)
                        loadDevice(mac)
                        loadFileCounts(mac)
                        loadConnectionStatusFromDb(mac)
                        prewarmRemotePlayback(mac)
                    }
                    SyncFromDeviceResult.NeedAdminApproval -> {
                        prefs.addPendingUserApprovalMac(mac)
                        pendingAdminApproval = true
                    }
                    SyncFromDeviceResult.WrongPassword ->
                        initialSyncFailedMessage = appCtx.getString(R.string.sync_wrong_password)
                    SyncFromDeviceResult.DiskUninitialized -> {
                        initialSyncFailedDiskUninit = true
                        initialSyncFailedMessage = appCtx.getString(R.string.sync_disk_uninit_dialog_body)
                    }
                    is SyncFromDeviceResult.Error -> {
                        initialSyncFailedDiskUninit = false
                        initialSyncFailedMessage = r.message
                    }
                }
            } catch (e: Exception) {
                Log.e("ContentVM", "connectCurrentDevice failed", e)
                initialSyncFailedMessage = e.message ?: appCtx.getString(R.string.sync_failed_generic)
            } finally {
                isConnectingDevice = false
            }
        }
    }

    fun clearInitialSyncError() {
        initialSyncFailedMessage = null
        initialSyncFailedDiskUninit = false
    }

    fun clearPendingAdminApproval() {
        pendingAdminApproval = false
    }

    /**
     * When non-null, main-page navigations (files, transfers, device admin, etc.) should be blocked
     * and the user shown this message. Refresh / device sheet / Settings stay available.
     */
    fun blockMainActionsReason(): String? {
        if (isConnectingDevice) return appCtx.getString(R.string.content_connecting_device)
        if (pendingAdminApproval) return appCtx.getString(R.string.content_need_admin_short)
        initialSyncFailedMessage?.let { return it }
        val mac = currentMac
        if (mac.isBlank()) return appCtx.getString(R.string.content_device_missing)
        val d = device
        if (d == null) {
            if (allDevices.isEmpty()) return appCtx.getString(R.string.content_connecting_device)
            return if (allDevices.none { it.mac == mac }) {
                appCtx.getString(R.string.content_device_missing)
            } else {
                appCtx.getString(R.string.content_connecting_device)
            }
        }
        return if (deviceRepo.resolveLinkMode(d, forceP2p) == DeviceLinkMode.Offline) {
            appCtx.getString(R.string.sync_device_offline)
        } else {
            null
        }
    }

    private fun loadConnectionStatusFromDb(mac: String) {
        viewModelScope.launch {
            val d = deviceRepo.getByMac(mac) ?: return@launch
            device = d
            connectionStatus = when (deviceRepo.resolveLinkMode(d, forceP2p)) {
                DeviceLinkMode.Local -> appCtx.getString(R.string.content_status_local_ip, deviceRepo.getRuntimeIp(d.mac))
                DeviceLinkMode.Remote -> appCtx.getString(R.string.content_remote_online)
                DeviceLinkMode.Offline -> appCtx.getString(R.string.content_offline)
            }
        }
    }

    private fun loadDevice(mac: String) {
        viewModelScope.launch {
            device = deviceRepo.getByMac(mac)
            user = prefs.getUser()
            userNickname = prefs.getUserNickname()
            val raw = prefs.getUserStorage()
            userStorageDisplay = NasCommands.displayNasStorageRaw(
                appCtx,
                raw.takeIf { it.isNotBlank() && it != "0" },
            )
        }
    }

    fun refreshUserProfileFromNas(mac: String) {
        viewModelScope.launch {
            val d = deviceRepo.getByMac(mac) ?: return@launch
            if (deviceRepo.resolveLinkMode(d, forceP2p) == DeviceLinkMode.Offline) return@launch
            val userEmail = prefs.getUser()
            val userPass = prefs.getPass()
            if (userEmail.isBlank() || userPass.isBlank()) return@launch
            try {
                withContext(Dispatchers.IO) {
                    val loginResp = deviceRepo.postDeviceCommand(d, NasCommands.userLogin(userEmail, userPass))
                    if (!NasCommands.userLoginSuccess(loginResp)) return@withContext
                    val statusJson = deviceRepo.postDeviceCommand(d, NasCommands.userGetStatus(userEmail, userPass))
                    if (statusJson.isBlank()) return@withContext
                    NasUserDbSync.applyUserProfileFromStatus(prefs, statusJson, userEmail)
                }
            } catch (e: Exception) {
                Log.w("ContentVM", "refreshUserProfileFromNas failed", e)
            }
        }
    }

    fun loadFileCounts(mac: String) {
        viewModelScope.launch {
            user = prefs.getUser()
            categoryCounts = if (user.isNotBlank()) {
                val fc = fileRepo.categoryCounts(user)
                fc.copy(transfers = transferRepo.totalCount())
            } else {
                CategoryCounts(0, 0, 0, 0, 0, 0, 0, 0)
            }
        }
    }

    private fun collectRecentFiles(mac: String) {
        recentFilesJob?.cancel()
        recentFiles = emptyList()
        recentFilesJob = viewModelScope.launch {
            fileRepo.observeRecentFiles(mac).collect { recentFiles = it }
        }
    }

    /**
     * Runs local discovery and cloud sync concurrently.
     * Local scan always runs (even with Force P2P) to detect device info changes
     * (name, SN) — only the connection status skips LAN when Force P2P is on.
     *
     * Both tasks run to completion — local DB is fully updated.
     * As soon as the current device is found by either source, UI status is
     * updated immediately so the user can interact with the app right away.
     *
     * If local scan detects changed device info (e.g. renamed on NAS), the change
     * is written to local DB during scan and pushed to the cloud afterwards.
     */
    fun refreshDeviceStatus(mac: String) {
        viewModelScope.launch {
            isRefreshing = true
            try {
                val currentForceP2p = forceP2p
                val statusUpdated = AtomicBoolean(false)

                // Local discovery — always runs to update IP / detect info changes
                val localJob = launch(Dispatchers.IO) {
                    try {
                        val result = deviceRepo.discoverLocal { discovered ->
                            if (discovered.mac == mac && !currentForceP2p
                                && statusUpdated.compareAndSet(false, true)) {
                                device = deviceRepo.getByMac(mac) ?: device
                                connectionStatus = appCtx.getString(R.string.content_status_local_ip, discovered.ip)
                            }
                        }
                        // Post-scan: push changed devices to cloud
                        for (changed in result.cloudSyncNeeded) {
                            try {
                                deviceRepo.updateToCloud(changed.mac, changed.sn, changed.name)
                            } catch (e: Exception) {
                                Log.w("ContentVM", "Cloud sync for ${changed.mac} failed", e)
                            }
                        }
                        // Post-scan: re-check status if not yet updated
                        if (!statusUpdated.get() && !currentForceP2p) {
                            val d = deviceRepo.getByMac(mac)
                            if (d != null && deviceRepo.resolveLinkMode(d, currentForceP2p) == DeviceLinkMode.Local
                                && statusUpdated.compareAndSet(false, true)) {
                                device = d
                                connectionStatus = appCtx.getString(R.string.content_status_local_ip, deviceRepo.getRuntimeIp(d.mac))
                            }
                        }
                    } catch (e: Exception) {
                        Log.e("ContentVM", "Local discovery failed", e)
                    }
                }

                // Cloud sync — always runs to update the full device DB
                val cloudJob = launch(Dispatchers.IO) {
                    try {
                        val nameConflicts = deviceRepo.syncFromCloud()
                        // Push local name to cloud for devices where local name differs
                        for (dev in nameConflicts) {
                            try {
                                deviceRepo.updateToCloud(dev.mac, dev.sn, dev.name)
                            } catch (e: Exception) {
                                Log.w("ContentVM", "Cloud name update for ${dev.mac} failed", e)
                            }
                        }
                        val d = deviceRepo.getByMac(mac)
                        if (d != null && deviceRepo.isRemoteConnected(d)
                            && statusUpdated.compareAndSet(false, true)) {
                            device = d
                            connectionStatus = appCtx.getString(R.string.content_remote_online)
                        }
                    } catch (e: Exception) {
                        Log.e("ContentVM", "Cloud sync failed", e)
                    }
                }

                localJob.join()
                cloudJob.join()

                // Both tasks finished — if neither found the device, resolve from DB now
                if (!statusUpdated.get()) {
                    val d = deviceRepo.getByMac(mac)
                    device = d
                    connectionStatus = if (d != null) {
                        when (deviceRepo.resolveLinkMode(d, forceP2p)) {
                            DeviceLinkMode.Local -> appCtx.getString(R.string.content_status_local_ip, deviceRepo.getRuntimeIp(d.mac))
                            DeviceLinkMode.Remote -> appCtx.getString(R.string.content_remote_online)
                            DeviceLinkMode.Offline -> appCtx.getString(R.string.content_offline)
                        }
                    } else {
                        appCtx.getString(R.string.common_unknown)
                    }
                }
            } catch (e: Exception) {
                Log.e("ContentVM", "refreshDeviceStatus failed", e)
            } finally {
                isRefreshing = false
            }
        }
    }

    fun refreshDeviceList() {
        DeviceRefreshCoordinator.requestCloudSync()
        prewarmRemotePlayback(currentMac)
    }

    private fun prewarmRemotePlayback(mac: String) {
        if (mac.isBlank()) return
        if (playbackPrewarmMac == mac && playbackPrewarmJob?.isActive == true) return
        playbackPrewarmMac = mac
        playbackPrewarmJob?.cancel()
        playbackPrewarmJob = viewModelScope.launch(Dispatchers.IO) {
            try {
                val d = deviceRepo.getByMac(mac) ?: return@launch
                if (deviceRepo.useLocalLink(d)) return@launch
                if (!deviceRepo.isRemoteConnected(d)) return@launch
                val err = RemoteLinkCoordinator.ensureP2pPlaybackReady(d)
                if (err != null) {
                    Log.w("ContentVM", "prewarmRemotePlayback skipped for $mac: $err")
                }
            } catch (e: Exception) {
                Log.w("ContentVM", "prewarmRemotePlayback failed for $mac", e)
            }
        }
    }

    // No blocking "refresh now" API: device list refresh happens only at defined entry points
    // (app startup, enter device list page, open device sheet) or manual refresh.

    fun updateSearch(query: String) {
        searchQuery = query
        if (query.isBlank()) {
            searchResults = emptyList()
            return
        }
        viewModelScope.launch {
            searchResults = fileRepo.searchFiles(user, query)
        }
    }

    fun linkModeFor(device: DeviceEntity): DeviceLinkMode =
        deviceRepo.resolveLinkMode(device, forceP2p)

    fun deleteDevice(mac: String) {
        viewModelScope.launch {
            try {
                deviceRepo.deleteFromCloud(mac)
                deviceRepo.deleteDevice(mac)
            } catch (e: Exception) {
                Log.e("ContentVM", "deleteDevice failed", e)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ContentMainScreen(
    mac: String,
    onBrowseFiles: (String) -> Unit,
    onPlayVideo: (List<String>, Int) -> Unit,
    onPlayAudio: (List<String>, Int) -> Unit,
    onPreviewImage: (List<String>, Int) -> Unit,
    onOpenDocument: (String) -> Unit,
    onAddDevice: () -> Unit,
    onConfigureDevice: (DeviceEntity) -> Unit = {},
    onManageDevices: () -> Unit = {},
    onDeviceManage: () -> Unit,
    onAbout: () -> Unit = {},
    onTransfers: () -> Unit,
    onSettings: () -> Unit,
    onBack: () -> Unit,
    /** When main was opened as start destination, use this to reach the device list after a failed sync. */
    onReturnToDevices: () -> Unit = onBack,
    /** 与 Qt [ContentMainPage]「出示设备二维码」一致：分享 `zh2:` + JSON（设备库全字段）。 */
    onDeviceQr: () -> Unit = {},
    vm: ContentViewModel = viewModel()
) {
    var showMenu by remember { mutableStateOf(false) }
    var searchActive by remember { mutableStateOf(false) }
    var deleteDeviceConfirm by remember { mutableStateOf<DeviceEntity?>(null) }
    val scrollState = rememberScrollState()
    val focusManager = LocalFocusManager.current
    val context = LocalContext.current

    val activeMac = vm.currentMac.ifBlank { mac }

    val snackHost = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    fun guardMainAction(block: () -> Unit) {
        vm.blockMainActionsReason()?.let { msg ->
            scope.launch { snackHost.showSnackbar(context.getString(R.string.content_snackbar_offline)) }
        } ?: block()
    }

    LaunchedEffect(mac) { vm.initIfNeeded(mac) }

    vm.initialSyncFailedMessage?.let { msg ->
        val isDiskUninit = vm.initialSyncFailedDiskUninit
        AlertDialog(
            onDismissRequest = vm::clearInitialSyncError,
            title = {
                Text(
                    if (isDiskUninit) stringResource(R.string.content_dialog_disk_init_title)
                    else stringResource(R.string.content_dialog_connect_fail_title),
                )
            },
            text = { Text(msg) },
            confirmButton = {
                if (isDiskUninit) {
                    TextButton(onClick = {
                        vm.clearInitialSyncError()
                        onDeviceManage()
                    }) { Text(stringResource(R.string.content_go_device_mgmt)) }
                } else {
                    TextButton(onClick = vm::clearInitialSyncError) { Text(stringResource(R.string.common_ok)) }
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    vm.clearInitialSyncError()
                    onReturnToDevices()
                }) { Text(stringResource(R.string.content_device_list)) }
            },
        )
    }

    if (vm.pendingAdminApproval) {
        AlertDialog(
            onDismissRequest = {
                vm.clearPendingAdminApproval()
                onReturnToDevices()
            },
            title = { Text(stringResource(R.string.content_admin_approval_title)) },
            text = { Text(stringResource(R.string.content_admin_approval_body)) },
            confirmButton = {
                TextButton(onClick = {
                    vm.clearPendingAdminApproval()
                    onReturnToDevices()
                }) { Text(stringResource(R.string.common_ok)) }
            },
        )
    }

    deleteDeviceConfirm?.let { dev ->
        AlertDialog(
            onDismissRequest = { deleteDeviceConfirm = null },
            title = { Text(stringResource(R.string.content_delete_device_title)) },
            text = {
                Text(
                    stringResource(
                        R.string.content_delete_device_confirm,
                        dev.name.ifBlank { dev.mac },
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        deleteDeviceConfirm = null
                        vm.deleteDevice(dev.mac)
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text(stringResource(R.string.common_delete)) }
            },
            dismissButton = {
                TextButton(onClick = { deleteDeviceConfirm = null }) {
                    Text(stringResource(R.string.common_cancel))
                }
            },
        )
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackHost) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        vm.device?.name ?: stringResource(R.string.content_topbar_nas),
                        style = MaterialTheme.typography.bodyLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                },
                actions = {
                    val reason = vm.blockMainActionsReason()
                    if (reason != null) {
                        Text(
                            reason,
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.outline,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    IconButton(onClick = { showMenu = true }) { Icon(Icons.Default.MoreVert, "Menu") }
                    DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
                        if (vm.blockMainActionsReason() == null) {
                            DropdownMenuItem(
                                text = { Text(stringResource(R.string.content_menu_show_qr)) },
                                onClick = {
                                    showMenu = false
                                    onDeviceQr()
                                },
                                leadingIcon = { Icon(Icons.Default.QrCode2, null) },
                            )
                        }
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.content_menu_device_mgmt)) },
                            onClick = {
                                showMenu = false
                                guardMainAction { onDeviceManage() }
                            },
                            leadingIcon = { Icon(Icons.Default.Storage, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.common_settings)) },
                            onClick = { showMenu = false; onSettings() },
                            leadingIcon = { Icon(Icons.Default.Tune, null) },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.content_menu_about)) },
                            onClick = {
                                showMenu = false
                                onAbout()
                            },
                            leadingIcon = { Icon(Icons.Default.Info, null) },
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            LazyColumn(
                Modifier
                    .fillMaxSize()
                    .padding(horizontal = 12.dp)
            ) {
                item {
                    // User banner (read-only: nickname or username + connection + used space)
                    Card(
                        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
                    ) {
                        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Default.AccountCircle, null, Modifier.size(48.dp), tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column {
                                Text(
                                    vm.userNickname.ifBlank { vm.user },
                                    style = MaterialTheme.typography.titleMedium,
                                )
                                vm.connectionStatus?.let { status ->
                                    Text(
                                        status,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                Text(
                                    stringResource(R.string.home_used_space, vm.userStorageDisplay),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }

                    val blockReason = vm.blockMainActionsReason()
                    // Search bar
                    OutlinedTextField(
                        value = vm.searchQuery,
                        onValueChange = { if (blockReason == null) vm.updateSearch(it) },
                        enabled = blockReason == null,
                        placeholder = {
                            Text(
                                if (blockReason != null) blockReason else stringResource(R.string.content_search_placeholder),
                            )
                        },
                        leadingIcon = { Icon(Icons.Default.Search, null) },
                        trailingIcon = {
                            if (vm.searchQuery.isNotEmpty() || searchActive) {
                                IconButton(onClick = {
                                    vm.updateSearch("")
                                    focusManager.clearFocus()
                                    searchActive = false
                                }) {
                                    Icon(Icons.Default.Clear, "Clear")
                                }
                            }
                        },
                        singleLine = true,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .onFocusChanged { searchActive = it.isFocused },
                    )
                }

                when {
                    // Typed search query → show search results
                    searchActive && vm.searchQuery.isNotBlank() -> {
                        searchResultsItems(
                            results = vm.searchResults,
                            onFileClick = { file ->
                                guardMainAction {
                                    handleFileClick(file, onBrowseFiles, onPlayVideo, onPlayAudio, onPreviewImage, onOpenDocument)
                                }
                            },
                        )
                    }
                    // Search focused but empty → show recent files
                    searchActive && vm.searchQuery.isBlank() -> {
                        if (vm.recentFiles.isEmpty()) {
                            item {
                                Box(
                                    Modifier.fillMaxWidth().padding(vertical = 32.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text(
                                        stringResource(R.string.content_no_recent),
                                        color = MaterialTheme.colorScheme.outline,
                                    )
                                }
                            }
                        } else {
                            item {
                                SectionHeader(stringResource(R.string.content_section_recent))
                            }
                            items(vm.recentFiles) { recent ->
                                RecentFileRow(
                                    recent = recent,
                                    onClick = {
                                        guardMainAction {
                                            focusManager.clearFocus()
                                            searchActive = false
                                            handleRecentFileClick(recent, onPlayVideo, onPlayAudio, onPreviewImage, onOpenDocument, onBrowseFiles)
                                        }
                                    },
                                )
                                HorizontalDivider()
                            }
                        }
                    }
                    // Normal view
                    else -> {
                        item {
                            // Quick Access
                            SectionHeader(stringResource(R.string.content_section_quick))
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                            ) {
                                FunctionCard(
                                    title = stringResource(R.string.content_transfer_tasks),
                                    icon = Icons.Default.SwapVert,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    guardMainAction { onTransfers() }
                                }
                                FunctionCard(
                                    title = stringResource(R.string.content_trash),
                                    icon = Icons.Default.Delete,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    guardMainAction { onBrowseFiles("__trash__") }
                                }
                                FunctionCard(
                                    title = stringResource(R.string.content_shared_files),
                                    icon = Icons.Default.Share,
                                    modifier = Modifier.weight(1f)
                                ) {
                                    guardMainAction { onBrowseFiles("__shared__") }
                                }
                            }

                            Spacer(Modifier.height(8.dp))

                            // My Documents
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    stringResource(R.string.content_my_documents),
                                    style = MaterialTheme.typography.titleSmall,
                                    modifier = Modifier.weight(1f),
                                )
                                IconButton(onClick = { vm.refreshDeviceList() }, modifier = Modifier.size(32.dp)) {
                                    Icon(Icons.Default.Refresh, "Refresh", Modifier.size(18.dp))
                                }
                            }

                            val c = vm.categoryCounts
                            CategoryRow(stringResource(R.string.content_category_image), Icons.Default.Image, c?.images, Constants.TAG_IMAGE) { dir ->
                                guardMainAction { onBrowseFiles(dir) }
                            }
                            CategoryRow(stringResource(R.string.content_category_video), Icons.Default.VideoLibrary, c?.videos, Constants.TAG_VIDEO) { dir ->
                                guardMainAction { onBrowseFiles(dir) }
                            }
                            CategoryRow(stringResource(R.string.content_category_audio), Icons.Default.MusicNote, c?.audio, Constants.TAG_AUDIO) { dir ->
                                guardMainAction { onBrowseFiles(dir) }
                            }
                            CategoryRow(stringResource(R.string.content_category_doc), Icons.Default.Description, c?.documents, Constants.TAG_DOC) { dir ->
                                guardMainAction { onBrowseFiles(dir) }
                            }

                            Spacer(Modifier.height(80.dp))
                        }
                    }
                }
            }
        }
    }
}

// Device switch sheet removed: Android now matches iOS flow (devices -> connecting -> main).

@Composable
private fun SectionHeader(title: String) {
    Text(
        title,
        style = MaterialTheme.typography.titleSmall,
        modifier = Modifier.padding(vertical = 8.dp)
    )
}

@Composable
private fun FunctionCard(
    title: String,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Card(
        modifier = modifier
            .height(80.dp)
            .clickable(onClick = onClick)
    ) {
        Column(
            Modifier.fillMaxSize().padding(12.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(icon, null, Modifier.size(28.dp), tint = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(4.dp))
            Text(title, style = MaterialTheme.typography.labelMedium, maxLines = 1)
        }
    }
}

@Composable
private fun CategoryRow(
    title: String,
    icon: ImageVector,
    count: Int?,
    tag: String,
    onCategoryClick: (String) -> Unit,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = {
            Text(
                if (count != null) {
                    stringResource(R.string.content_files_count, count)
                } else {
                    stringResource(R.string.content_files_count_unknown)
                },
            )
        },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        trailingContent = {
            Icon(Icons.Default.ChevronRight, null, tint = MaterialTheme.colorScheme.outline)
        },
        modifier = Modifier.clickable { onCategoryClick(tag) }
    )
}

@Composable
private fun RecentFileRow(recent: RecentFileEntity, onClick: () -> Unit) {
    val icon = when (recent.fileType) {
        "image" -> Icons.Default.Image
        "video" -> Icons.Default.VideoFile
        "audio" -> Icons.Default.AudioFile
        "document" -> Icons.Default.Description
        else -> Icons.AutoMirrored.Filled.InsertDriveFile
    }
    val elapsed = System.currentTimeMillis() - recent.accessedAt
    val timeText = when {
        elapsed < 60_000 -> stringResource(R.string.content_time_just_now)
        elapsed < 3_600_000 ->
            stringResource(R.string.content_time_minutes_ago, (elapsed / 60_000).toInt())
        elapsed < 86_400_000 ->
            stringResource(R.string.content_time_hours_ago, (elapsed / 3_600_000).toInt())
        else -> stringResource(R.string.content_time_days_ago, (elapsed / 86_400_000).toInt())
    }

    ListItem(
        headlineContent = {
            Text(recent.displayName, maxLines = 1, overflow = TextOverflow.Ellipsis)
        },
        supportingContent = { Text(timeText) },
        leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
        modifier = Modifier.clickable(onClick = onClick)
    )
}

private fun LazyListScope.searchResultsItems(
    results: List<FileEntity>,
    onFileClick: (FileEntity) -> Unit,
) {
    if (results.isEmpty()) {
        item {
            Box(
                Modifier.fillMaxWidth().padding(vertical = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(stringResource(R.string.content_no_search_results), color = MaterialTheme.colorScheme.outline)
            }
        }
    } else {
        items(results) { file ->
            val icon = if (file.isDir) Icons.Default.Folder else {
                when (classifyRemoteMediaType(file.remotePath)) {
                    RemoteMediaType.IMAGE -> Icons.Default.Image
                    RemoteMediaType.VIDEO -> Icons.Default.VideoFile
                    RemoteMediaType.AUDIO -> Icons.Default.AudioFile
                    RemoteMediaType.DOCUMENT -> Icons.Default.Description
                    RemoteMediaType.UNKNOWN -> Icons.AutoMirrored.Filled.InsertDriveFile
                }
            }
            ListItem(
                headlineContent = {
                    Text(file.remotePath.substringAfterLast("/"), maxLines = 1, overflow = TextOverflow.Ellipsis)
                },
                leadingContent = { Icon(icon, null, tint = MaterialTheme.colorScheme.primary) },
                modifier = Modifier.clickable { onFileClick(file) }
            )
            HorizontalDivider()
        }
    }
}

private fun handleFileClick(
    file: FileEntity,
    onBrowseFiles: (String) -> Unit,
    onPlayVideo: (List<String>, Int) -> Unit,
    onPlayAudio: (List<String>, Int) -> Unit,
    onPreviewImage: (List<String>, Int) -> Unit,
    onOpenDocument: (String) -> Unit,
) {
    if (file.isDir) {
        onBrowseFiles(file.remotePath)
        return
    }
    when (classifyRemoteMediaType(file.remotePath)) {
        RemoteMediaType.VIDEO -> onPlayVideo(listOf(file.remotePath), 0)
        RemoteMediaType.AUDIO -> onPlayAudio(listOf(file.remotePath), 0)
        RemoteMediaType.IMAGE -> onPreviewImage(listOf(file.remotePath), 0)
        RemoteMediaType.DOCUMENT -> onOpenDocument(file.remotePath)
        RemoteMediaType.UNKNOWN -> onBrowseFiles(file.remotePath.substringBeforeLast("/", ""))
    }
}

private fun handleRecentFileClick(
    recent: RecentFileEntity,
    onPlayVideo: (List<String>, Int) -> Unit,
    onPlayAudio: (List<String>, Int) -> Unit,
    onPreviewImage: (List<String>, Int) -> Unit,
    onOpenDocument: (String) -> Unit,
    onBrowseFiles: (String) -> Unit,
) {
    when (classifyRemoteMediaType(recent.remotePath)) {
        RemoteMediaType.VIDEO -> onPlayVideo(listOf(recent.remotePath), 0)
        RemoteMediaType.AUDIO -> onPlayAudio(listOf(recent.remotePath), 0)
        RemoteMediaType.IMAGE -> onPreviewImage(listOf(recent.remotePath), 0)
        RemoteMediaType.DOCUMENT -> onOpenDocument(recent.remotePath)
        RemoteMediaType.UNKNOWN -> onBrowseFiles(recent.remotePath.substringBeforeLast("/", ""))
    }
}
