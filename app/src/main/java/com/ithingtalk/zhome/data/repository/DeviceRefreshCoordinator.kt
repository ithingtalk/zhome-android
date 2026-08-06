package com.ithingtalk.zhome.data.repository

import android.util.Log
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.network.NetworkMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Independent cloud sync and LAN discovery (aligned with iOS DeviceStatusService).
 */
object DeviceRefreshCoordinator {

    private const val TAG = "DeviceRefresh"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var cloudJob: Job? = null
    private var lanJob: Job? = null
    @Volatile private var wifiLanScanDoneForSession = false

    private val _isSyncingCloud = MutableStateFlow(false)
    val isSyncingCloud: StateFlow<Boolean> = _isSyncingCloud.asStateFlow()

    private val _isDiscoveringLan = MutableStateFlow(false)
    val isDiscoveringLan: StateFlow<Boolean> = _isDiscoveringLan.asStateFlow()

    private val _lanScanComplete = MutableStateFlow(false)
    val lanScanComplete: StateFlow<Boolean> = _lanScanComplete.asStateFlow()

    /** @deprecated Use [isSyncingCloud] or [isDiscoveringLan]. */
    val isRefreshing: StateFlow<Boolean> = _isSyncingCloud

    fun onWifiDisconnected() {
        wifiLanScanDoneForSession = false
        _lanScanComplete.value = false
        ZhomeApp.instance.deviceRepo.clearAllLocalOnlineState()
    }

    fun requestCloudSync() {
        val ctx = ZhomeApp.instance.applicationContext
        if (!NetworkMonitor.currentStatus(ctx).supportsCloudSync) {
            Log.d(TAG, "Skip cloud sync: offline")
            return
        }
        if (cloudJob?.isActive == true) {
            Log.d(TAG, "Cloud sync already in progress")
            return
        }
        cloudJob = scope.launch {
            _isSyncingCloud.value = true
            try {
                runCloudSync()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Cloud sync error", e)
            } finally {
                _isSyncingCloud.value = false
                cloudJob = null
            }
        }
    }

    fun requestLanDiscovery() {
        val ctx = ZhomeApp.instance.applicationContext
        if (!NetworkMonitor.supportsLocalDiscovery(ctx)) return
        startLanDiscovery(markWifiSessionDone = false)
    }

    /** One LAN scan per Wi‑Fi session (until [onWifiDisconnected]). */
    fun requestLanDiscoveryOnce() {
        val ctx = ZhomeApp.instance.applicationContext
        if (!NetworkMonitor.supportsLocalDiscovery(ctx)) return
        if (wifiLanScanDoneForSession) {
            Log.d(TAG, "Skip LAN discovery: already done this Wi‑Fi session")
            return
        }
        if (lanJob?.isActive == true) {
            Log.d(TAG, "LAN discovery already in progress")
            return
        }
        wifiLanScanDoneForSession = true
        startLanDiscovery(markWifiSessionDone = true)
    }

    fun requestDeviceListRefresh() {
        requestCloudSync()
        requestLanDiscoveryOnce()
    }

    fun requestFullRefresh() = requestDeviceListRefresh()

    private fun startLanDiscovery(markWifiSessionDone: Boolean) {
        if (lanJob?.isActive == true) {
            if (markWifiSessionDone) wifiLanScanDoneForSession = true
            return
        }
        if (markWifiSessionDone) wifiLanScanDoneForSession = true
        lanJob = scope.launch {
            _isDiscoveringLan.value = true
            _lanScanComplete.value = false
            try {
                ZhomeApp.instance.deviceRepo.discoverLocal()
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "LAN discovery error", e)
            } finally {
                _isDiscoveringLan.value = false
                _lanScanComplete.value = true
                lanJob = null
            }
        }
    }

    private suspend fun runCloudSync() {
        val repo = ZhomeApp.instance.deviceRepo
        val nameConflicts = repo.syncFromCloud()
        for (dev in nameConflicts) {
            try {
                repo.updateToCloud(dev.mac, dev.sn, dev.name)
            } catch (_: Exception) {
            }
        }
    }
}
