package com.ithingtalk.zhome.ui.screens.devices

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.local.db.DeviceEntity
import com.ithingtalk.zhome.data.repository.DeviceLinkMode
import com.ithingtalk.zhome.data.repository.DeviceRefreshCoordinator
import com.ithingtalk.zhome.network.DiscoveredDevice
import com.ithingtalk.zhome.network.NetworkMonitor
import com.ithingtalk.zhome.util.DeviceQrPayload
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class DeviceChannelStatus {
    Checking,
    Online,
    Offline,
}

class DevicesViewModel : ViewModel() {
    private val repo = ZhomeApp.instance.deviceRepo
    private val prefs = ZhomeApp.instance.prefs
    private val appCtx = ZhomeApp.instance.applicationContext

    var devices by mutableStateOf<List<DeviceEntity>>(emptyList()); private set
    var error by mutableStateOf<String?>(null); private set
    var forceP2p by mutableStateOf(false); private set

    // Search state
    var discoveredDevices by mutableStateOf<List<DiscoveredDevice>>(emptyList()); private set
    var isSearching by mutableStateOf(false); private set
    var scanQrError by mutableStateOf<String?>(null); private set
    /** MACs waiting for NAS admin to approve this app user ([LocalPrefs] + list badge「未审批」). */
    var pendingUserApprovalMacs by mutableStateOf<Set<String>>(emptySet()); private set
    /** LAN IPs from last discovery; drives list subtitle recomposition. */
    var runtimeIps by mutableStateOf<Map<String, String>>(emptyMap()); private set
    /** MACs seen on latest LAN scan — drives local vs remote subtitle (iOS localOnlineMacs). */
    var localOnlineMacs by mutableStateOf<Set<String>>(emptySet()); private set
    var remoteOnlineMacs by mutableStateOf<Set<String>>(emptySet()); private set
    var isDiscoveringLan by mutableStateOf(false); private set
    var isSyncingCloud by mutableStateOf(false); private set
    var lanScanComplete by mutableStateOf(false); private set

    init {
        viewModelScope.launch {
            repo.observeAll().collect { devices = it }
        }
        viewModelScope.launch {
            prefs.observeForceP2p().collect { forceP2p = it }
        }
        viewModelScope.launch {
            prefs.observePendingUserApprovalMacs().collect { pendingUserApprovalMacs = it }
        }
        viewModelScope.launch {
            repo.runtimeIpByMac.collect { runtimeIps = it }
        }
        viewModelScope.launch {
            repo.localOnlineMacs.collect { localOnlineMacs = it }
        }
        viewModelScope.launch {
            repo.remoteOnlineMacs.collect { remoteOnlineMacs = it }
        }
        viewModelScope.launch {
            DeviceRefreshCoordinator.isDiscoveringLan.collect { isDiscoveringLan = it }
        }
        viewModelScope.launch {
            DeviceRefreshCoordinator.isSyncingCloud.collect { isSyncingCloud = it }
        }
        viewModelScope.launch {
            DeviceRefreshCoordinator.lanScanComplete.collect { lanScanComplete = it }
        }
        refreshDevices()
    }

    fun isUserApprovalPending(mac: String): Boolean = mac in pendingUserApprovalMacs

    /** Cloud sync + one LAN scan per Wi‑Fi session (device list on resume). */
    fun refreshDevices() {
        DeviceRefreshCoordinator.requestDeviceListRefresh()
    }

    /** Menu refresh: cloud + forced LAN scan when on Wi‑Fi. */
    fun refreshDevicesManual() {
        DeviceRefreshCoordinator.requestCloudSync()
        if (NetworkMonitor.supportsLocalDiscovery(appCtx)) {
            DeviceRefreshCoordinator.requestLanDiscovery()
        }
    }

    fun localStatus(device: DeviceEntity): DeviceChannelStatus {
        if (Constants.deviceNeedsConfigure(device.cfg)) return DeviceChannelStatus.Offline
        if (!NetworkMonitor.supportsLocalDiscovery(appCtx)) return DeviceChannelStatus.Offline
        if (device.mac in localOnlineMacs) return DeviceChannelStatus.Online
        if (isDiscoveringLan) return DeviceChannelStatus.Checking
        if (lanScanComplete) return DeviceChannelStatus.Offline
        return DeviceChannelStatus.Checking
    }

    fun remoteStatus(device: DeviceEntity): DeviceChannelStatus {
        if (Constants.deviceNeedsConfigure(device.cfg)) return DeviceChannelStatus.Offline
        if (!NetworkMonitor.currentStatus(appCtx).isConnected) return DeviceChannelStatus.Offline
        if (repo.isRemoteConnected(device)) return DeviceChannelStatus.Online
        if (isSyncingCloud) return DeviceChannelStatus.Checking
        return DeviceChannelStatus.Offline
    }

    fun canConnect(device: DeviceEntity): Boolean =
        localStatus(device) == DeviceChannelStatus.Online ||
            remoteStatus(device) == DeviceChannelStatus.Online

    fun isStatusChecking(device: DeviceEntity): Boolean =
        localStatus(device) == DeviceChannelStatus.Checking ||
            remoteStatus(device) == DeviceChannelStatus.Checking

    fun deleteDevice(mac: String) {
        viewModelScope.launch {
            try {
                // Two-phase delete (aligned with qtApp):
                // 1) mark pending=del locally so list hides it immediately;
                // 2) try cloud delete; on success, physically remove the row.
                repo.markPendingDelete(mac)
                val ok = runCatching { repo.deleteFromCloud(mac) }.getOrDefault(false)
                if (ok) repo.deleteDevice(mac)
                prefs.removePendingUserApprovalMac(mac)
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun deleteAllDevices() {
        viewModelScope.launch {
            repo.deleteAll()
            prefs.clearPendingUserApprovalMacsForCurrentUser()
        }
    }

    fun selectDevice(mac: String) {
        viewModelScope.launch { repo.setCurrent(mac) }
    }

    fun linkModeFor(device: DeviceEntity): DeviceLinkMode =
        repo.resolveLinkMode(device, forceP2p)

    fun runtimeIpFor(mac: String): String = runtimeIps[mac].orEmpty()

    fun searchLocalDevices() {
        viewModelScope.launch {
            if (isSearching) return@launch
            discoveredDevices = emptyList()
            isSearching = true
            error = null
            try {
                val existingMacs = repo.getAll().map { it.mac }.toSet()
                repo.discoverLocal { device ->
                    // Requirement: always show unconfigured devices (cfg == "0"),
                    // even if already present in the local device DB.
                    val needsConfigure = Constants.deviceNeedsConfigure(device.cfg)
                    if (needsConfigure || device.mac !in existingMacs) {
                        withContext(Dispatchers.Main.immediate) {
                            if (discoveredDevices.none { it.mac == device.mac }) {
                                discoveredDevices = discoveredDevices + device
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                error = e.message
            }
            isSearching = false
        }
    }

    fun addDevice(device: DiscoveredDevice) {
        viewModelScope.launch {
            try {
                // Local write first with pending=add (hidden until pushed if you
                // watch selectByPending). Then try cloud; on success clear pending.
                repo.setRuntimeIp(device.mac, device.ip, markOnline = true)
                repo.addLocal(device.mac, device.sn, device.name, cfg = device.cfg)
                val ok = runCatching { repo.addToCloud(device.mac, device.sn, device.name) }.getOrDefault(false)
                if (ok) repo.markSynced(device.mac)
            } catch (e: Exception) {
                error = e.message
            }
        }
    }

    fun clearScanQrError() {
        scanQrError = null
    }

    /**
     * 仅识别 `zh2:` + JSON（v2）设备分享码，写入本地库并尝试同步云端。
     * @param onResult null = success
     */
    fun addDeviceFromQrScan(raw: String, onResult: (String?) -> Unit) {
        viewModelScope.launch {
            scanQrError = null
            val err = try {
                addDeviceFromScannedQr(raw)
            } catch (e: Exception) {
                e.message ?: appCtx.getString(R.string.devices_add_failed)
            }
            if (err != null) scanQrError = err
            onResult(err)
        }
    }

    private suspend fun addDeviceFromScannedQr(raw: String): String? {
        val v2 = DeviceQrPayload.parseV2(raw)
            ?: return appCtx.getString(R.string.devices_invalid_qr)
        val displayName = v2.name.ifBlank { appCtx.getString(R.string.devices_display_name_default) }
        val cfg = v2.cfg.ifBlank { "1" }
        try {
            repo.setRuntimeIp(v2.mac, v2.ip, markOnline = v2.ip.isNotBlank())
            repo.addLocal(
                mac = v2.mac,
                sn = v2.sn,
                name = displayName,
                cfg = cfg,
                online = v2.online,
            )
            val ok = runCatching { repo.addToCloud(v2.mac, v2.sn, displayName) }.getOrDefault(false)
            if (ok) repo.markSynced(v2.mac)
            repo.setCurrent(v2.mac)
        } catch (e: Exception) {
            return e.message ?: appCtx.getString(R.string.devices_add_failed)
        }
        return null
    }
}
