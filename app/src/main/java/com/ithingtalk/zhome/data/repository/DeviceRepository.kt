package com.ithingtalk.zhome.data.repository

import android.util.Log
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.local.db.DeviceDao
import com.ithingtalk.zhome.data.local.db.DeviceEntity
import com.ithingtalk.zhome.data.local.prefs.LocalPrefs
import kotlinx.coroutines.flow.Flow
import com.ithingtalk.zhome.data.remote.aws.AwsApiService
import com.ithingtalk.zhome.data.remote.aws.AwsCredentials
import com.ithingtalk.zhome.data.remote.aws.AwsIotService
import com.ithingtalk.zhome.data.remote.nas.NasLocalClient
import com.ithingtalk.zhome.data.remote.p2p.RemoteLinkCoordinator
import com.ithingtalk.zhome.network.DiscoveredDevice
import com.ithingtalk.zhome.network.LocalDiscovery
import com.ithingtalk.zhome.network.NetworkMonitor
import org.json.JSONObject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import java.net.ConnectException
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Mirrors Qt device list: local IP path, remote (IoT) path, or neither. */
enum class DeviceLinkMode {
    Local,
    Remote,
    Offline,
}

data class DeviceDiscoveryResult(
    val devices: List<DiscoveredDevice>,
    val cloudSyncNeeded: List<DiscoveredDevice>,
)

/**
 * Tri-state cloud-sync marker stored in [DeviceEntity.pending].
 * Mirrors qtApp's `PendingStatus` (see `qtApp/cpp/dbDevices.h`).
 */
object PendingStatus {
    const val NONE: String = ""
    const val ADD: String = "add"
    const val DEL: String = "del"
}

class DeviceRepository(
    private val dao: DeviceDao,
    private val api: AwsApiService,
    private val iot: AwsIotService,
    private val discovery: LocalDiscovery,
    private val prefs: LocalPrefs,
    private val nasLocal: NasLocalClient,
    private val auth: AuthRepository,
) {
    private val TAG = "DeviceRepository"

    // Ephemeral LAN IP from the current scan (never persisted).
    private val _runtimeIpByMac = MutableStateFlow<Map<String, String>>(emptyMap())
    val runtimeIpByMac: StateFlow<Map<String, String>> = _runtimeIpByMac

    /** MACs that responded on the latest LAN discovery pass (aligned with iOS localOnlineMacs). */
    private val _localOnlineMacs = MutableStateFlow<Set<String>>(emptySet())
    val localOnlineMacs: StateFlow<Set<String>> = _localOnlineMacs

    /** MACs reported online by the last successful cloud sync (memory only; iOS remoteOnlineMacs). */
    private val _remoteOnlineMacs = MutableStateFlow<Set<String>>(emptySet())
    val remoteOnlineMacs: StateFlow<Set<String>> = _remoteOnlineMacs

    @Volatile
    private var remoteOnlineFromCloudSync = false

    /** Session-scoped LAN IP cache; survives scan clears (aligned with iOS lastKnownLanIpByMac). */
    private val lastKnownLanIpByMac = mutableMapOf<String, String>()

    /** Clears ephemeral LAN scan state (Wi‑Fi off or before a new scan). */
    fun clearAllLocalOnlineState() {
        _runtimeIpByMac.value = emptyMap()
        _localOnlineMacs.value = emptySet()
    }

    fun markLocalOnline(mac: String) {
        _localOnlineMacs.update { it + mac }
    }

    fun clearLocalOnline(mac: String) {
        _localOnlineMacs.update { it - mac }
        _runtimeIpByMac.update { it - mac }
    }

    /** True when this device answered the latest LAN scan on Wi‑Fi/wired (iOS DeviceScanner.isLocalOnline). */
    fun isLocalOnline(mac: String): Boolean {
        if (!NetworkMonitor.supportsLocalDiscovery(ZhomeApp.instance.applicationContext)) {
            return false
        }
        return mac in _localOnlineMacs.value
    }

    fun clearLastKnownLanIps() {
        lastKnownLanIpByMac.clear()
    }

    fun rememberLanIp(mac: String, host: String) {
        val h = host.trim()
        if (h.isBlank() || h == Constants.P2P_HTTP_IP) return
        lastKnownLanIpByMac[mac] = h
    }

    /** Runtime scan IP first, then session cache — for LAN HTTPS URLs only. */
    fun lanNasHost(mac: String): String {
        val runtime = getRuntimeIp(mac).trim()
        if (runtime.isNotBlank()) return runtime
        return lastKnownLanIpByMac[mac]?.trim().orEmpty()
    }

    /**
     * @param markOnline When true (LAN discovery / explicit LAN configure), device is treated as
     * locally reachable. Do not set from remote get_status [ip_addr] — that is [rememberLanIp] only.
     */
    fun setRuntimeIp(mac: String, ip: String, markOnline: Boolean = false) {
        val trimmed = ip.trim()
        _runtimeIpByMac.update { m ->
            if (trimmed.isBlank()) m - mac else m + (mac to trimmed)
        }
        if (trimmed.isNotBlank()) {
            rememberLanIp(mac, trimmed)
            if (markOnline) markLocalOnline(mac)
        } else if (markOnline) {
            clearLocalOnline(mac)
        }
    }

    fun getRuntimeIp(mac: String): String = _runtimeIpByMac.value[mac].orEmpty()

    private fun redactForLog(json: String): String {
        return try {
            val o = JSONObject(json)
            val sensitiveKeys = listOf(
                Constants.CMD_USER_PASSWD,
                Constants.CMD_ADMIN_PWD,
                Constants.CMD_NEW_PWD,
            )
            for (k in sensitiveKeys) {
                if (o.has(k)) o.put(k, "***")
            }
            o.toString()
        } catch (_: Exception) {
            // If it's not valid JSON, still avoid leaking obvious secrets.
            json.replace(Regex("\"(user_passwd|admin_pwd|new_passwd)\"\\s*:\\s*\"[^\"]*\""), "\"$1\":\"***\"")
        }
    }

    /**
     * In-memory "current device" cache to make device switching immediate.
     *
     * Many UI flows call [getCurrent] while DataStore write for `curr_device_mac`
     * is still in flight; caching avoids accidentally using the previous device
     * for a short window after switching.
     */
    @Volatile
    private var currentMacCache: String? = null

    /**
     * Cognito Identity 临时密钥；**直接使用返回值**签 Gateway，避免 IoT 用内存密钥而 Gateway 误读 prefs 导致 SigV4 与 IoT 行为不一致。
     */
    private suspend fun freshAwsCredentials(reason: String): AwsCredentials? {
        if (!auth.isLoggedIn()) {
            Log.w(TAG, "$reason skipped: not logged in (Cognito sign-in / aws session first)")
            return null
        }
        return try {
            auth.getAwsCredentials()
        } catch (e: Exception) {
            Log.e(TAG, "$reason: Cognito Identity credentials failed (Gateway + IoT use this session)", e)
            null
        }
    }

    suspend fun getAll(): List<DeviceEntity> = dao.getAll()

    fun observeAll(): Flow<List<DeviceEntity>> = dao.observeAll()

    fun observeByMac(mac: String): Flow<DeviceEntity?> = dao.observeByMac(mac)

    suspend fun getByMac(mac: String): DeviceEntity? = dao.getByMac(mac)

    suspend fun addDevice(
        mac: String,
        sn: String,
        name: String,
        cfg: String = "",
        online: String = "",
        pending: String = "",
    ) {
        dao.upsert(
            DeviceEntity(
                mac = mac,
                sn = sn,
                name = name,
                cfg = cfg,
                online = online,
                pending = pending,
            ),
        )
    }

    /**
     * Adds (or re-adds) a device locally, marking it pending=add so the next
     * cloud sync push can push it up. Mirrors qtApp DbDevices::add with
     * PendingStatus::Add.
     */
    suspend fun addLocal(
        mac: String,
        sn: String,
        name: String,
        cfg: String = "",
        online: String = "",
    ) {
        val existing = dao.getByMac(mac)
        val newPending = when (existing?.pending) {
            // Keep pending=add until it gets pushed successfully.
            PendingStatus.ADD -> PendingStatus.ADD
            // Re-adding a soft-deleted record: flip back to add.
            PendingStatus.DEL -> PendingStatus.ADD
            // Brand new local record.
            null -> PendingStatus.ADD
            // Row already synced: don't churn the sync state.
            else -> PendingStatus.NONE
        }
        dao.upsert(
            DeviceEntity(
                mac = mac,
                sn = sn,
                name = name,
                cfg = cfg,
                online = online,
                pending = newPending,
            ),
        )
    }

    /** Marks the row pending=del. The row is kept so we can push the delete to cloud. */
    suspend fun markPendingDelete(mac: String) {
        val existing = dao.getByMac(mac) ?: return
        if (existing.pending == PendingStatus.ADD) {
            // Never synced to cloud; physical delete is safe and cheaper.
            dao.delete(mac)
            return
        }
        dao.updatePending(mac, PendingStatus.DEL)
    }

    /** Marks the row synced with cloud (pending=''). */
    suspend fun markSynced(mac: String) {
        dao.updatePending(mac, PendingStatus.NONE)
    }

    suspend fun deleteDevice(mac: String) {
        dao.delete(mac)
    }

    suspend fun deleteAll() = dao.deleteAll()

    suspend fun updateName(mac: String, name: String) = dao.updateName(mac, name)

    /**
     * Updates the cloud record for an existing device (name change, etc.).
     * Uses the same `/idevices` endpoint as [addToCloud] — API Gateway treats it as upsert.
     */
    suspend fun updateToCloud(mac: String, sn: String, name: String): Boolean =
        addToCloud(mac, sn, name)

    suspend fun setCurrent(mac: String) {
        currentMacCache = mac
        prefs.setCurrDeviceMac(mac)
    }

    suspend fun getCurrent(): DeviceEntity? {
        val mac = currentMacCache ?: prefs.getCurrDeviceMac().also { m ->
            if (m.isNotBlank()) currentMacCache = m
        }
        return if (mac.isNotBlank()) dao.getByMac(mac) else null
    }

    /**
     * 从 API Gateway 拉设备列表（SigV4，对齐 Qt [AwsDbService::all]）。
     * 内部先 [AuthRepository.getAwsCredentials]（Cognito Identity 临时密钥），与 AWS IoT 共用。
     */
    /**
     * Pull cloud device list and reconcile with the local DB, then push any
     * pending Add/Del backlog up. Semantics match qtApp + the unified spec:
     *
     *  1. Merge: for every cloud device, upsert into local DB preserving local
     *     `pending`, `cfg`, and `ip`. Rows with pending=add are left untouched
     *     (local edit wins) so the backlog push can update cloud with local
     *     values. Brand-new cloud rows are inserted with pending='' (None).
     *  2. Orphan cleanup: rows present locally but absent on cloud AND whose
     *     pending is not 'add' are deleted locally — they were deleted on
     *     another device.
     *  3. Backlog push: pending=add rows are posted to /idevices, pending=del
     *     rows are deleted on /idevices/me; on success the rows are cleared
     *     or physically removed.
     *
     * Returns devices whose local name differs from cloud name so the caller
     * can push the authoritative (local-scan) name back to the cloud.
     */
    suspend fun syncFromCloud(): List<DeviceEntity> = withContext(Dispatchers.IO) {
        val c = freshAwsCredentials("syncFromCloud") ?: return@withContext emptyList()
        if (c.accessKeyId.isBlank() || c.secretKey.isBlank() || c.sessionToken.isBlank()) {
            return@withContext emptyList()
        }
        Log.i(TAG, "Cloud sync: POST …/idevices/all (SigV4 execute-api)")
        val needCloudNameUpdate = mutableListOf<DeviceEntity>()
        val cloudMacs = HashSet<String>()
        val remoteOnline = HashSet<String>()
        try {
            val cloudDevices = api.listDevices(c.accessKeyId, c.secretKey, c.sessionToken)
            Log.i(TAG, "Merged ${cloudDevices.size} cloud device row(s) into local DB")
            for (cd in cloudDevices) {
                if (cd.mac.isBlank()) continue
                cloudMacs.add(cd.mac)
                if (isRemoteOnlineField(cd.online)) remoteOnline.add(cd.mac)
                val existing = dao.getByMac(cd.mac)
                if (existing == null) {
                    dao.upsert(
                        DeviceEntity(
                            mac = cd.mac,
                            sn = cd.sn,
                            name = cd.name,
                            online = cd.online,
                            cfg = "1",
                            pending = PendingStatus.NONE,
                        ),
                    )
                    continue
                }
                // Local has unsent Add — let local win until backlog push runs.
                if (existing.pending == PendingStatus.ADD) continue
                val localName = existing.name
                val cloudName = cd.name
                val nameToUse = if (localName.isNotBlank() && localName != cloudName) {
                    needCloudNameUpdate.add(existing)
                    localName
                } else {
                    cloudName.ifBlank { localName }
                }
                // Preserve local cfg / ip / pending; update sn / name / online from cloud.
                dao.upsert(
                    existing.copy(
                        sn = cd.sn.ifBlank { existing.sn },
                        name = nameToUse,
                        online = cd.online,
                    ),
                )
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Cloud sync failed", e)
            throw e
        }

        // Orphan cleanup: local rows whose MAC is absent from cloud AND not
        // pending=add (pending=add rows are local-new waiting to push up).
        try {
            val localAll = dao.getAllRaw()
            for (row in localAll) {
                if (row.mac in cloudMacs) continue
                if (row.pending == PendingStatus.ADD) continue
                Log.i(TAG, "orphan local row ${row.mac} missing from cloud, deleting locally")
                dao.delete(row.mac)
            }
        } catch (e: Exception) {
            Log.w(TAG, "orphan cleanup failed", e)
        }

        // Backlog push: add then del, clearing pending or removing row on success.
        try {
            pushPendingBacklog()
        } catch (e: Exception) {
            Log.w(TAG, "backlog push failed", e)
        }

        _remoteOnlineMacs.value = remoteOnline
        remoteOnlineFromCloudSync = true

        return@withContext needCloudNameUpdate
    }

    /** Pushes locally-pending Add and Del records up to the cloud. */
    private suspend fun pushPendingBacklog() {
        val adds = dao.getByPending(PendingStatus.ADD)
        for (d in adds) {
            val ok = runCatching { addToCloud(d.mac, d.sn, d.name) }.getOrDefault(false)
            if (ok) markSynced(d.mac)
        }
        val dels = dao.getByPending(PendingStatus.DEL)
        for (d in dels) {
            val ok = runCatching { deleteFromCloud(d.mac) }.getOrDefault(false)
            if (ok) dao.delete(d.mac)
        }
    }

    suspend fun addToCloud(mac: String, sn: String, name: String): Boolean {
        val c = freshAwsCredentials("addToCloud") ?: return false
        if (c.accessKeyId.isBlank() || c.secretKey.isBlank() || c.sessionToken.isBlank()) return false
        return api.addDevice(c.accessKeyId, c.secretKey, c.sessionToken, mac, sn, name)
    }

    suspend fun deleteFromCloud(mac: String): Boolean {
        val c = freshAwsCredentials("deleteFromCloud") ?: return false
        if (c.accessKeyId.isBlank() || c.secretKey.isBlank() || c.sessionToken.isBlank()) return false
        return api.deleteDevice(c.accessKeyId, c.secretKey, c.sessionToken, mac)
    }

    /**
     * Discover devices on local network and merge into local DB.
     *
     * For each discovered device already in the DB:
     * - Always updates IP.
     * - If the NAS reports a changed name or SN, updates local DB and returns
     *   the device in [DeviceDiscoveryResult.cloudSyncNeeded] so the caller
     *   can push the change to the cloud.
     *
     * [onDeviceFound] is called once per unique device as replies arrive.
     */
    suspend fun discoverLocal(
        onDeviceFound: suspend (DiscoveredDevice) -> Unit = {}
    ): DeviceDiscoveryResult = withContext(Dispatchers.IO) {
        val ctx = ZhomeApp.instance.applicationContext
        if (!NetworkMonitor.supportsLocalDiscovery(ctx)) {
            Log.d(TAG, "Skipping LAN discovery (cellular-only or offline)")
            return@withContext DeviceDiscoveryResult(emptyList(), emptyList())
        }

        Log.d(TAG, "Starting local discovery (2 passes)...")
        clearAllLocalOnlineState()
        val needCloudSync = mutableListOf<DiscoveredDevice>()
        val allFound = LinkedHashMap<String, DiscoveredDevice>()

        suspend fun handleDevice(device: DiscoveredDevice) {
            setRuntimeIp(device.mac, device.ip, markOnline = true)
            val existing = dao.getByMac(device.mac)
            if (existing != null) {
                val nameChanged = device.name.isNotBlank() && device.name != existing.name
                val snChanged = device.sn.isNotBlank() && device.sn != existing.sn
                val cfgChanged = device.cfg.isNotBlank() && device.cfg != existing.cfg
                if (nameChanged || snChanged || cfgChanged) {
                    val newName = if (nameChanged) device.name else existing.name
                    val newSn = if (snChanged) device.sn else existing.sn
                    val newCfg = if (cfgChanged) device.cfg else existing.cfg
                    
                    // Aligned with iOS: if name/sn changed, mark pending ADD
                    val isInfoChanged = nameChanged || snChanged
                    val newPending = if (isInfoChanged) PendingStatus.ADD else existing.pending
                    
                    val updatedEntity = existing.copy(name = newName, sn = newSn, cfg = newCfg, pending = newPending)
                    dao.upsert(updatedEntity)
                    Log.i(TAG, "Local scan: device ${device.mac} info changed " +
                        "(name: ${existing.name}→$newName, sn: ${existing.sn}→$newSn, cfg: ${existing.cfg}→$newCfg), pending: $newPending")
                    
                    if (isInfoChanged) {
                        // Asynchronously attempt to sync changed info to cloud, just like iOS:
                        val ok = runCatching { addToCloud(device.mac, newSn, newName) }.getOrDefault(false)
                        if (ok) {
                            dao.upsert(updatedEntity.copy(pending = PendingStatus.NONE))
                            Log.i(TAG, "Successfully synced updated device info to cloud for ${device.mac}")
                        } else {
                            Log.w(TAG, "Failed to sync updated device info to cloud for ${device.mac}, kept pendingStatus=ADD")
                        }
                    }
                    
                    if (needCloudSync.none { it.mac == device.mac }) {
                        needCloudSync.add(device)
                    }
                }
            }
            allFound[device.mac] = device
            onDeviceFound(device)
        }

        val passTimeoutMs = 1500L
        repeat(2) { pass ->
            Log.d(TAG, "LAN discovery pass ${pass + 1}/2 (timeout ${passTimeoutMs}ms)")
            discovery.search(timeoutMs = passTimeoutMs) { device ->
                handleDevice(device)
            }
            if (pass == 0) delay(1000)
        }

        Log.d(TAG, "Found ${allFound.size} local devices, ${needCloudSync.size} need cloud sync")
        return@withContext DeviceDiscoveryResult(allFound.values.toList(), needCloudSync)
    }

    /** Same rule as iOS [ConnectionPolicy.useLocalLink] / Qt when LAN scan sees the device. */
    suspend fun useLocalLink(device: DeviceEntity): Boolean =
        !prefs.getForceP2p() && isLocalOnline(device.mac)

    /** Cloud `online` field → remote channel (does not affect LAN flags). */
    private fun isRemoteOnlineField(online: String): Boolean {
        val o = online.trim().lowercase()
        return o == "connected" || o == "online" || o == "true" || o == "1"
    }

    fun isRemoteOnline(mac: String): Boolean =
        remoteOnlineFromCloudSync && mac in _remoteOnlineMacs.value

    /**
     * Qt [DevicesPage.qml]: subtitle green when `devip !== ""` OR `online === "connected"`.
     * Prefer [remoteOnlineMacs] after a successful cloud sync.
     */
    fun isRemoteConnected(device: DeviceEntity): Boolean {
        if (remoteOnlineFromCloudSync) return device.mac in _remoteOnlineMacs.value
        return isRemoteOnlineField(device.online)
    }

    /**
     * Prefer LAN when available (same as Qt `useLocalLink`); else cloud-reported remote session.
     */
    fun resolveLinkMode(device: DeviceEntity, forceP2p: Boolean): DeviceLinkMode =
        when {
            !forceP2p && isLocalOnline(device.mac) -> DeviceLinkMode.Local
            isRemoteConnected(device) -> DeviceLinkMode.Remote
            else -> DeviceLinkMode.Offline
        }

    /**
     * Send a NAS JSON command over LAN HTTP ([NasLocalClient]), matching Qt local [CmdService::send].
     * Injects [Constants.IOT_APP_CLIENT_ID] like [NasApi::addIotAddr].
     *
     * For admin commands, pass [httpUser]="admin" and [httpPass]=adminPassword to match Qt's
     * `send(cmd, "admin", adminPass)` — the NAS requires admin HTTP auth for write operations.
     * For regular user commands, leave null to use the app account credentials from prefs.
     */
    suspend fun postLocalCommand(
        device: DeviceEntity,
        commandJson: String,
        httpUser: String? = null,
        httpPass: String? = null,
        readTimeoutSec: Long = 60L,
    ): String {
        require(useLocalLink(device)) { "Local link unavailable (no IP or Force Remote)" }
        val ip = lanNasHost(device.mac)
        require(ip.isNotBlank()) { "Local link unavailable (no IP)" }
        val mergedObj = JSONObject(commandJson).apply {
            put(Constants.IOT_APP_CLIENT_ID, iot.getClientId())
        }
        val merged = mergedObj.toString()
        val user = httpUser ?: prefs.getUser()
        val pass = httpPass ?: prefs.getPass()
        Log.d(TAG, "postLocalCommand → $ip httpUser=$user (len=${merged.length}) cmd=${redactForLog(merged)}")
        val resp = nasLocal.postCommand(ip, merged, user, pass, readTimeoutSec = readTimeoutSec)
        Log.d(TAG, "postLocalCommand ← $ip (len=${resp.length}) resp=${redactForLog(resp)}")
        return resp
    }

    /**
     * Send a NAS JSON command over IoT MQTT (`{mac}/control` Base64) and wait for the response.
     * Mirrors Qt [CmdService::send] remote path + [CmdService::onP2pCmdFinished].
     * Injects [IOT_APP_CLIENT_ID] and [cmd_service_id] for response correlation.
     */
    suspend fun postRemoteCommand(
        device: DeviceEntity,
        commandJson: String,
        timeoutMs: Long = Constants.REMOTE_COMMAND_TIMEOUT_MS,
    ): String = withContext(Dispatchers.IO) {
        val cmdId = java.util.UUID.randomUUID().toString()
        val mergedObj = JSONObject(commandJson).apply {
            put(Constants.IOT_APP_CLIENT_ID, iot.getClientId())
            put("cmd_service_id", cmdId)
            put("time_stamp", System.currentTimeMillis().toString())
        }
        val merged = mergedObj.toString()
        Log.d(TAG, "postRemoteCommand mac=${device.mac} len=${merged.length} cmdId=$cmdId cmd=${redactForLog(merged)}")
        val resp = iot.postAndWaitCommand(device.mac, merged, timeoutMs)
        Log.d(TAG, "postRemoteCommand ← mac=${device.mac} (len=${resp.length}) cmdId=$cmdId resp=${redactForLog(resp)}")
        return@withContext resp
    }

    /**
     * Single entry for NAS JSON commands: LAN HTTP when [useLocalLink], else IoT after
     * [RemoteLinkCoordinator.ensureRemoteCommandReady] (same decision chain as Qt [CmdService::send]).
     * Prefer this over open-coding `if (useLocalLink) postLocalCommand else postRemoteCommand` in UI layers.
     *
     * Admin writes: pass [httpUser] `admin` and [httpPass] admin password. User commands: leave both null (prefs).
     *
     * Exceptions: first-time configure over known LAN may call [postLocalCommand] directly on a temporary [DeviceEntity].
     */
    suspend fun postDeviceCommand(
        device: DeviceEntity,
        commandJson: String,
        httpUser: String? = null,
        httpPass: String? = null,
        remoteTimeoutMs: Long = Constants.REMOTE_COMMAND_TIMEOUT_MS,
        lanReadTimeoutSec: Long = 60L,
    ): String {
        if (useLocalLink(device)) {
            try {
                return postLocalCommand(device, commandJson, httpUser, httpPass, readTimeoutSec = lanReadTimeoutSec)
            } catch (e: Exception) {
                if (!shouldFallbackLanToRemote(e)) throw e
                Log.w(TAG, "LAN unreachable for ${device.mac}, falling back to remote", e)
                clearLocalOnline(device.mac)
            }
        }
        val err = RemoteLinkCoordinator.ensureRemoteCommandReady(device)
        if (err != null) throw Exception(err)
        return postRemoteCommandWithRetry(device, commandJson, timeoutMs = remoteTimeoutMs)
    }

    /**
     * Remote IoT command with one retry after [RemoteLinkCoordinator.recoverAfterCommandTimeout].
     */
    private suspend fun postRemoteCommandWithRetry(
        device: DeviceEntity,
        commandJson: String,
        timeoutMs: Long,
    ): String {
        var resp = postRemoteCommand(device, commandJson, timeoutMs = timeoutMs)
        if (resp.isNotEmpty()) return resp
        Log.w(TAG, "postRemoteCommand timeout → reconnect IoT, wait ready, retry once mac=${device.mac}")
        val err = RemoteLinkCoordinator.recoverAfterCommandTimeout(device)
        if (err != null) throw Exception(err)
        resp = postRemoteCommand(device, commandJson, timeoutMs = timeoutMs)
        if (resp.isEmpty()) {
            throw Exception("Remote command timed out or connection unavailable")
        }
        return resp
    }

    private fun shouldFallbackLanToRemote(e: Exception): Boolean = when (e) {
        is SocketTimeoutException, is ConnectException -> true
        else -> {
            val msg = e.message.orEmpty().lowercase()
            msg.contains("timeout") || msg.contains("failed to connect") || msg.contains("unreachable")
        }
    }

    /** IoT operations */
    fun publishCommand(deviceSn: String, cmd: String) = iot.publish(deviceSn, cmd)
    fun subscribeDevice(deviceSn: String) = iot.subscribe(deviceSn)
}
