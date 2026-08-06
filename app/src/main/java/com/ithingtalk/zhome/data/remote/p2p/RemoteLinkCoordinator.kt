package com.ithingtalk.zhome.data.remote.p2p

import android.util.Log
import com.ithingtalk.zhome.Constants
import com.ithingtalk.zhome.Constants.IceGatherMode
import com.ithingtalk.zhome.R
import com.ithingtalk.zhome.ZhomeApp
import com.ithingtalk.zhome.data.local.db.DeviceEntity
import com.ithingtalk.zhome.data.remote.aws.AwsIotService
import com.ithingtalk.zhome.jni.NativeBridge
import com.ithingtalk.zhome.network.NetworkMonitor
import kotlinx.coroutines.GlobalScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CancellationException
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

private const val TAG = "RemoteLink"

/**
 * 远程链路：与 Qt [awsIot.cpp] + [cmdService.cpp] 对齐。
 *
 * In Qt every remote command (user login, admin login, file ops, admin management) goes through
 * the libp2p P2P session on `{mac}/control`. The session must be established via
 * [nativeLibp2pStartSession] before the NAS will process any command from the client.
 *
 * Two readiness levels:
 *
 * 1. **P2P session ready** ([ensureRemoteCommandReady]): libp2p init + P2P session started.
 *    Sufficient for ALL remote NAS JSON commands — file listing, user ops, admin management.
 *    Does NOT wait for the local HTTP proxy on 127.0.0.1:5000.
 *
 * 2. **Full playback ready** ([ensureP2pPlaybackReady]): P2P session ready + HTTP proxy up.
 *    Required before VLC plays `http://127.0.0.1:5000/...`.
 */
object RemoteLinkCoordinator {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val p2pMutex = Mutex()
    private var currentP2pJob: Deferred<String?>? = null

    /** MAC for which a full P2P session (libp2p init + session start) is active. */
    @Volatile private var lastSessionReadyMac: String? = null

    /** ICE gather mode used for [lastSessionReadyMac] / [lastP2pReadyMac]; mismatch forces new session. */
    @Volatile private var lastSessionIceGatherMode: Int = IceGatherMode.BOTH

    /** MAC for which the local HTTP proxy is also confirmed up (superset of session ready). */
    @Volatile private var lastP2pReadyMac: String? = null

    @Volatile private var libp2pInitialized = false

    /**
     * Register to be notified when the IoT MQTT connection is lost or credentials
     * are rotated. Both events destroy all existing MQTT subscriptions, so the
     * P2P session "ready" state must be invalidated to force a re-subscribe on
     * the next [ensureRemoteCommandReady] call.
     */
    fun installIotConnectionResetHook() {
        ZhomeApp.instance.awsIot.onConnectionReset = {
            Log.w(TAG, "IoT connection reset → soft P2P invalidate (keep in-flight session job)")
            invalidateP2pSessionState(cancelInFlightSession = false)
        }
    }

    /** Drop stale P2P/IoT state after network interface changes (aligned with iOS AwsRemoteService). */
    fun handleNetworkChange() {
        Log.w(TAG, "Network changed → hard P2P invalidate")
        invalidateP2pSessionState(cancelInFlightSession = true)
        val iot = ZhomeApp.instance.awsIot
        val ctx = ZhomeApp.instance.applicationContext
        scope.launch {
            iot.disconnect()
            if (NetworkMonitor.currentStatus(ctx).isConnected) {
                runCatching { ZhomeApp.instance.authRepo.getAwsCredentials() }
                iot.connect()
            }
        }
    }

    /**
     * @param cancelInFlightSession When false (IoT credential rotation / reconnect), do not cancel
     * [currentP2pJob] — [startP2pSessionLocked] may be awaiting the same job that triggered credential refresh.
     */
    private fun invalidateP2pSessionState(cancelInFlightSession: Boolean) {
        lastSessionReadyMac = null
        lastP2pReadyMac = null
        if (cancelInFlightSession) {
            currentP2pJob?.cancel()
            currentP2pJob = null
            try {
                NativeBridge.libp2pLeaveDevice()
            } catch (_: Throwable) {
            }
        }
    }

    /** Soft invalidate after command timeout (no leaveDevice). */
    fun invalidateSessionCache(reason: String) {
        Log.w(TAG, "invalidate session cache (soft): $reason")
        invalidateP2pSessionState(cancelInFlightSession = false)
    }

    /**
     * After a remote command times out: reconnect IoT, wait until ready, re-establish P2P session.
     * @return error message on failure; null on success.
     */
    suspend fun recoverAfterCommandTimeout(device: DeviceEntity): String? = withContext(Dispatchers.IO) {
        Log.w(TAG, "command timeout: reconnect IoT mac=${device.mac}")
        ZhomeApp.instance.awsIot.reconnectCommandChannel()
        invalidateSessionCache("command timeout")
        ensureRemoteCommandReady(device)
    }

    /**
     * Ensures a libp2p P2P session is established for [device] so the NAS processes
     * commands sent on `{mac}/control`.
     *
     * Safe to call repeatedly — returns immediately if a session is already active for
     * this device. On LAN, returns immediately without starting any P2P infrastructure.
     *
     * @return error message on failure; null on success.
     */
    suspend fun ensureRemoteCommandReady(device: DeviceEntity): String? = withContext(Dispatchers.IO) {
        val repo = ZhomeApp.instance.deviceRepo
        val ctx = ZhomeApp.instance.applicationContext
        if (repo.useLocalLink(device)) return@withContext null

        val mac = device.mac.trim()
        if (mac.isBlank()) return@withContext ctx.getString(R.string.remote_mac_invalid)

        repeat(2) { attempt ->
            val job = p2pMutex.withLock {
                val ice = IceGatherMode.clamp(ZhomeApp.instance.prefs.getIceGatherMode())
                if ((mac == lastSessionReadyMac || mac == lastP2pReadyMac) && ice == lastSessionIceGatherMode) {
                    Log.i(TAG, "ensureRemoteCommandReady: session already ready mac=$mac iceGatherMode=$ice")
                    return@withContext null
                }
                if (currentP2pJob == null || currentP2pJob?.isActive != true) {
                    currentP2pJob = scope.async { startP2pSessionLocked(device) }
                }
                currentP2pJob!!
            }
            try {
                return@withContext job.await()
            } catch (e: CancellationException) {
                if (attempt == 0) {
                    Log.w(TAG, "ensureRemoteCommandReady: session job cancelled, retrying once mac=$mac")
                    p2pMutex.withLock {
                        currentP2pJob = null
                    }
                } else {
                    throw e
                }
            }
        }
        return@withContext null
    }

    /**
     * Full P2P playback stack: P2P session ready + local HTTP proxy on 127.0.0.1:5000.
     * Call before VLC plays `http://127.0.0.1:5000/...`.
     *
     * @return error message on failure; null on success.
     */
    suspend fun ensureP2pPlaybackReady(device: DeviceEntity): String? = withContext(Dispatchers.IO) {
        val repo = ZhomeApp.instance.deviceRepo
        val ctx = ZhomeApp.instance.applicationContext
        if (repo.useLocalLink(device)) {
            lastSessionReadyMac = null
            lastP2pReadyMac = null
            return@withContext null
        }
        val mac = device.mac.trim()
        if (mac.isBlank()) return@withContext ctx.getString(R.string.remote_mac_invalid)

        val proxyUpAlready = p2pMutex.withLock {
            val ice = IceGatherMode.clamp(ZhomeApp.instance.prefs.getIceGatherMode())
            if (mac == lastP2pReadyMac && ice == lastSessionIceGatherMode) {
                val proxyUp = probeLocalHttpProxy()
                if (proxyUp) {
                    Log.i(TAG, "ensureP2pPlaybackReady: fast path mac=$mac")
                    return@withLock true
                }
            }
            false
        }
        if (proxyUpAlready) return@withContext null

        // Establish P2P session if not already done
        val err = ensureRemoteCommandReady(device)
        if (err != null) return@withContext err

        if (!waitForLocalHttpProxy()) {
            lastP2pReadyMac = null
            return@withContext ctx.getString(
                R.string.remote_proxy_not_ready,
                Constants.P2P_HTTP_IP,
                Constants.P2P_HTTP_PORT,
            )
        }
        p2pMutex.withLock { lastP2pReadyMac = mac }
        Log.i(
            TAG,
            "P2P 播放栈就绪 mac=$mac，本地代理 ${Constants.P2P_HTTP_IP}:${Constants.P2P_HTTP_PORT}",
        )
        return@withContext null
    }

    /**
     * Establishes the libp2p P2P session for [device]:
     * 1. Refresh AWS credentials + wait for IoT MQTT connected
     * 2. Init native libp2p library
     * 3. Subscribe to `{mac}/{clientId}` for inbound messages
     * 4. Start P2P session (sends session initiation to NAS via `{mac}/control`)
     *
     * After this the NAS will process any command received on `{mac}/control`.
     * Does NOT wait for the local HTTP proxy.
     */
    private suspend fun startP2pSessionLocked(device: DeviceEntity): String? {
        val iot: AwsIotService = ZhomeApp.instance.awsIot
        val authRepo = ZhomeApp.instance.authRepo
        val prefs = ZhomeApp.instance.prefs
        val appCtx = ZhomeApp.instance.applicationContext
        val user = prefs.getUser().trim()
        val pass = prefs.getPass()
        val mac = device.mac.trim()

        if (user.isBlank()) return appCtx.getString(R.string.remote_not_signed_in)

        try {
            authRepo.getAwsCredentials()
        } catch (e: Exception) {
            Log.e(TAG, "getAwsCredentials failed", e)
            return appCtx.getString(R.string.remote_cloud_credentials, e.message ?: "")
        }

        if (!waitForIotConnected(iot)) {
            return appCtx.getString(R.string.remote_iot_disconnected)
        }

        val home = File(appCtx.filesDir, "p2p_download").apply { mkdirs() }.absolutePath
        val cache = File(appCtx.filesDir, "p2p_cache").apply { mkdirs() }.absolutePath
        val ice = IceGatherMode.clamp(prefs.getIceGatherMode())

        try {
            NativeBridge.libp2pInit(ice)
            Log.i(TAG, "libp2pInit ok ice=$ice")
        } catch (e: Throwable) {
            Log.e(TAG, "libp2pInit failed", e)
            throw e
        }
        libp2pInitialized = true

        val clientId = iot.getClientId()

        NativeBridge.libp2pIotSendHandler = { msg ->
            iot.publishP2pControl(mac, msg)
        }
        NativeBridge.libp2pAppMessageHandler = { msg ->
            Log.i(TAG, "libp2p app msg len=${msg.length} preview=${msg.take(120)}")
        }

        if (!subscribeWithRetry(iot, mac, clientId, iotInboundHandler)) {
            return appCtx.getString(R.string.remote_mqtt_subscribe_failed)
        }

        try {
            NativeBridge.libp2pIntoDevice(home, cache, clientId, mac)
            Log.i(TAG, "P2P into_device ok appIotId=$clientId nasId=$mac iceGatherMode=$ice")
        } catch (e: Throwable) {
            Log.e(TAG, "native P2P session start failed", e)
            return appCtx.getString(R.string.remote_p2p_start_failed, e.message ?: "")
        }

        lastSessionReadyMac = mac
        lastSessionIceGatherMode = ice
        Log.i(TAG, "P2P 会话就绪 mac=$mac clientId=$clientId iceGatherMode=$ice")
        return null
    }

    /**
     * Unified inbound handler for `{mac}/{clientId}` topic.
     * P2P packets → native libp2p. NAS command responses → [AwsIotService.routeAppData].
     */
    private val iotInboundHandler: (String) -> Unit = { decoded ->
        kotlinx.coroutines.GlobalScope.launch(Dispatchers.IO) {
            val iot = ZhomeApp.instance.awsIot
            if (libp2pInitialized) {
                try {
                    val isP2p = NativeBridge.libp2pRecvFromIot(decoded)
                    if (!isP2p) iot.routeAppData(decoded)
                } catch (e: Throwable) {
                    Log.e(TAG, "libp2pRecvFromIot failed, fallback routeAppData", e)
                    iot.routeAppData(decoded)
                }
            } else {
                iot.routeAppData(decoded)
            }
        }
    }

    private fun probeLocalHttpProxy(): Boolean = try {
        Socket().use { s ->
            s.connect(
                InetSocketAddress(Constants.P2P_HTTP_IP, Constants.P2P_HTTP_PORT.toInt()),
                400,
            )
        }
        true
    } catch (_: Exception) {
        false
    }

    private suspend fun subscribeWithRetry(
        iot: AwsIotService,
        mac: String,
        clientId: String,
        handler: (String) -> Unit,
        maxAttempts: Int = 5,
    ): Boolean {
        repeat(maxAttempts) { attempt ->
            if (!iot.isConnected) {
                Log.i(TAG, "subscribeWithRetry: waiting IoT (attempt ${attempt + 1})")
                val ok = waitForIotConnected(iot, Constants.REMOTE_IOT_READY_TIMEOUT_MS)
                if (!ok) {
                    Log.w(TAG, "subscribeWithRetry: IoT not connected after wait")
                    return false
                }
            }
            if (iot.subscribeP2pInbound(mac, clientId, handler)) return true
            Log.w(TAG, "subscribeWithRetry attempt ${attempt + 1} failed, retrying…")
            delay(1000L * (attempt + 1))
        }
        return false
    }

    private suspend fun waitForIotConnected(
        iot: AwsIotService,
        timeoutMs: Long = Constants.REMOTE_IOT_READY_TIMEOUT_MS,
    ): Boolean {
        if (iot.isConnected) return true
        iot.connect()
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            if (iot.isConnected) return true
            delay(300)
        }
        return iot.isConnected
    }

    private suspend fun waitForLocalHttpProxy(timeoutMs: Long = 10000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val ok = withContext(Dispatchers.IO) { probeLocalHttpProxy() }
            if (ok) return true
            delay(100)
        }
        return false
    }

    fun stopLibp2p() {
        Log.i(TAG, "stopLibp2p")
        lastSessionReadyMac = null
        lastP2pReadyMac = null
        lastSessionIceGatherMode = IceGatherMode.BOTH
        libp2pInitialized = false
        try {
            NativeBridge.libp2pIotSendHandler = null
            NativeBridge.libp2pAppMessageHandler = null
            NativeBridge.libp2pExit()
        } catch (e: Throwable) {
            Log.w(TAG, "stopLibp2p native exit", e)
        }
    }
}
