package com.ithingtalk.zhome.data.remote.aws

import android.content.Context
import android.util.Base64
import android.util.Log
import com.amazonaws.auth.AWSCredentialsProvider
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.mobileconnectors.iot.AWSIotMqttClientStatusCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttManager
import com.amazonaws.mobileconnectors.iot.AWSIotMqttNewMessageCallback
import com.amazonaws.mobileconnectors.iot.AWSIotMqttQos
import com.ithingtalk.zhome.AwsConfig
import com.ithingtalk.zhome.Constants
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * AWS IoT Core MQTT：设备命令与 P2P/ICE 信令（对齐 Qt）。
 * 凭证与 API Gateway 设备列表相同：由 [AuthRepository.getAwsCredentials] 经 Cognito Identity 换发后 [updateCredentials] 注入。
 * - 网关风格：`zhome/{sn}/…`
 * - libp2p（对齐 Qt）：订阅 `{mac}/{clientId}`、发布 `{mac}/control`，负载 **Base64**。
 */
class AwsIotService(private val context: Context) {

    private val TAG = "AwsIot"
    private val DEBUG_IOT_PAYLOAD = true

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST)
    val messages: SharedFlow<String> = _messages

    /** Pending remote NAS commands awaiting response keyed by cmd_service_id. */
    private val pendingRemoteCommands = ConcurrentHashMap<String, CompletableDeferred<String>>()

    private var clientId: String = "zhome-android-${UUID.randomUUID()}"
    @Volatile
    var isConnected = false
        private set

    private var accessKeyId: String = ""
    private var secretKey: String = ""
    private var sessionToken: String = ""

    private var mqttManager: AWSIotMqttManager? = null

    private val credentialsProvider: AWSCredentialsProvider
        get() = object : AWSCredentialsProvider {
            override fun getCredentials() =
                BasicSessionCredentials(accessKeyId, secretKey, sessionToken)
            override fun refresh() {}
        }

    /** Called when the IoT MQTT connection is lost or intentionally reset. */
    @Volatile
    var onConnectionReset: (() -> Unit)? = null

    @Volatile
    private var suppressConnectionResetDepth = 0

    fun updateCredentials(ak: String, sk: String, st: String) {
        if (ak == accessKeyId && sk == secretKey && st == sessionToken) {
            Log.d(TAG, "updateCredentials: unchanged, skip")
            return
        }
        val wasConnected = mqttManager != null
        accessKeyId = ak
        secretKey = sk
        sessionToken = st
        if (wasConnected) {
            Log.i(TAG, "updateCredentials: credentials changed, reconnecting")
            try {
                mqttManager?.disconnect()
            } catch (_: Exception) {}
            isConnected = false
            mqttManager = null
            p2pTopic = null
            p2pPayloadHandler = null
            onConnectionReset?.invoke()
        }
    }

    /** Same role as Qt [AwsIot::getIotClientId] for [Constants.IOT_APP_CLIENT_ID] in NAS JSON. */
    fun getClientId(): String = clientId

    fun connect() {
        if (accessKeyId.isBlank()) {
            Log.w(TAG, "No AWS session credentials; IoT MQTT skipped (sign in + getAwsCredentials first)")
            return
        }
        val mgr = mqttManager
        if (mgr != null && isConnected) {
            Log.d(TAG, "IoT already connected clientId=$clientId")
            return
        }
        if (mgr != null) {
            Log.d(TAG, "IoT manager exists (reconnecting/connecting), waiting for callback")
            return
        }
        val newMgr = AWSIotMqttManager(clientId, AwsConfig.iotHost).apply {
            setAutoReconnect(true)
            setCredentialsProvider(credentialsProvider)
        }
        mqttManager = newMgr
        Log.i(
            TAG,
            "IoT MQTT connecting... endpoint=${AwsConfig.iotHost} region=${AwsConfig.region} clientId=$clientId",
        )
        try {
            newMgr.connect(credentialsProvider, object : AWSIotMqttClientStatusCallback {
                override fun onStatusChanged(
                    status: AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus,
                    throwable: Throwable?,
                ) {
                    if (mqttManager !== newMgr) return
                    when (status) {
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connecting ->
                            Log.d(TAG, "IoT status: Connecting")
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Connected -> {
                            isConnected = true
                            Log.i(TAG, "IoT MQTT Connected (Juice/lsquic 信令可下发)")
                        }
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.ConnectionLost -> {
                            isConnected = false
                            Log.w(TAG, "IoT MQTT connection lost", throwable)
                            onConnectionReset?.invoke()
                        }
                        AWSIotMqttClientStatusCallback.AWSIotMqttClientStatus.Reconnecting ->
                            Log.i(TAG, "IoT MQTT reconnecting…")
                    }
                }
            })
        } catch (e: Exception) {
            isConnected = false
            mqttManager = null
            Log.e(TAG, "IoT MQTT connect failed", e)
        }
    }

    fun disconnect() {
        signalingListener = null
        p2pPayloadHandler = null
        p2pTopic = null
        try {
            mqttManager?.disconnect()
        } catch (_: Exception) {
        }
        mqttManager = null
        isConnected = false
        if (suppressConnectionResetDepth == 0) {
            onConnectionReset?.invoke()
        }
        Log.i(TAG, "IoT MQTT disconnected")
    }

    /** Force fresh MQTT connect (stale socket may still report connected). */
    fun reconnectCommandChannel() {
        suppressConnectionResetDepth++
        pendingRemoteCommands.values.forEach { if (!it.isCompleted) it.complete("") }
        pendingRemoteCommands.clear()
        Log.i(TAG, "reconnectCommandChannel")
        try {
            mqttManager?.disconnect()
        } catch (_: Exception) {
        }
        mqttManager = null
        isConnected = false
        p2pTopic = null
        connect()
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            suppressConnectionResetDepth = maxOf(0, suppressConnectionResetDepth - 1)
        }, 2000L)
    }

    /**
     * Publishes JSON command to device topic `zhome/{deviceSn}/cmd`.
     */
    fun publish(deviceSn: String, message: String) {
        val mgr = mqttManager
        if (mgr == null || !isConnected) {
            Log.w(TAG, "publish skipped (not connected) sn=$deviceSn len=${message.length}")
            return
        }
        val topic = "zhome/$deviceSn/cmd"
        try {
            mgr.publishString(message, topic, AWSIotMqttQos.QOS0)
            logPayload("→", topic, message)
        } catch (e: Exception) {
            Log.e(TAG, "publish failed $topic", e)
        }
    }

    /** ICE / P2P 信令：与 [publish] 相同 topic，JSON 内用字段 [t] 区分类型。 */
    fun publishSignaling(deviceSn: String, json: String) = publish(deviceSn, json)

    /**
     * Subscribe to `zhome/{deviceSn}/resp/{clientId}` for app-destined payloads.
     */
    fun subscribe(deviceSn: String) {
        val mgr = mqttManager
        if (mgr == null || !isConnected) {
            Log.w(TAG, "subscribe skipped (not connected) sn=$deviceSn")
            return
        }
        val topic = "zhome/$deviceSn/resp/$clientId"
        try {
            mgr.subscribeToTopic(topic, AWSIotMqttQos.QOS0, messageCallback)
            Log.i(TAG, "SUB $topic")
        } catch (e: Exception) {
            Log.e(TAG, "subscribe failed $topic", e)
        }
    }

    private var signalingListener: ((String, String) -> Unit)? = null

    /** Qt awsIot: `mac/clientId` — Base64-encoded body. */
    private var p2pTopic: String? = null
    private var p2pPayloadHandler: ((String) -> Unit)? = null

    private val messageCallback = AWSIotMqttNewMessageCallback { topic, data ->
        val bytes = data ?: ByteArray(0)
        val p2pT = p2pTopic
        if (p2pT != null && topic == p2pT) {
            val decoded = try {
                String(Base64.decode(bytes, Base64.DEFAULT), StandardCharsets.UTF_8)
            } catch (e: Exception) {
                Log.w(TAG, "p2p topic Base64 decode failed, fallback UTF-8", e)
                String(bytes, StandardCharsets.UTF_8)
            }
            logPayload("←(p2p)", topic, decoded)
            p2pPayloadHandler?.invoke(decoded)
                ?: Log.w(TAG, "p2p handler not set")
            return@AWSIotMqttNewMessageCallback
        }
        val text = String(bytes, StandardCharsets.UTF_8)
        logPayload("←", topic, text)
        signalingListener?.invoke(topic, text)
        _messages.tryEmit(text)
    }

    /** libp2p `iot_send` → Qt `pubTopic` = `{mac}/control`. */
    fun publishP2pControl(deviceMac: String, message: String) {
        val mgr = mqttManager
        if (mgr == null || !isConnected) {
            Log.w(TAG, "publishP2pControl skipped (not connected) mac=$deviceMac")
            return
        }
        val topic = "$deviceMac/control"
        val b64 = Base64.encodeToString(
            message.toByteArray(StandardCharsets.UTF_8),
            Base64.NO_WRAP,
        )
        try {
            mgr.publishString(b64, topic, AWSIotMqttQos.QOS1)
            logPayload("→(p2p)", topic, message)
        } catch (e: Exception) {
            Log.e(TAG, "publishP2pControl failed $topic", e)
        }
    }

    /**
     * Qt awsIot::subTopic = `mac + "/" + clientId`.
     */
    /** @return true if subscribed to `{mac}/{clientId}` for libp2p inbound */
    fun subscribeP2pInbound(deviceMac: String, clientId: String, onDecoded: (String) -> Unit): Boolean {
        val mgr = mqttManager
        if (mgr == null || !isConnected) {
            Log.w(TAG, "subscribeP2pInbound skipped (not connected)")
            return false
        }
        p2pTopic?.let { old ->
            try {
                mgr.unsubscribeTopic(old)
                Log.d(TAG, "UNSUB $old")
            } catch (e: Exception) {
                Log.w(TAG, "unsubscribe $old", e)
            }
        }
        val t = "$deviceMac/$clientId"
        p2pTopic = t
        p2pPayloadHandler = onDecoded
        return try {
            mgr.subscribeToTopic(t, AWSIotMqttQos.QOS1, messageCallback)
            Log.i(TAG, "SUB libp2p $t")
            true
        } catch (e: Exception) {
            Log.e(TAG, "subscribeP2pInbound failed", e)
            false
        }
    }

    /**
     * Ensures subscription to resp topic and forwards payloads to [onPayload] (signaling JSON).
     */
    fun subscribeDeviceSignaling(deviceSn: String, onPayload: (topic: String, payload: String) -> Unit) {
        signalingListener = onPayload
        subscribe(deviceSn)
    }

    /**
     * Called by [RemoteLinkCoordinator] when [libp2p_recv_p2p_cmd_from_iot] returns false —
     * meaning the inbound payload is a NAS app command response, not a P2P packet.
     * Matches the Qt [AwsIot] flow: emit recvAppData → [CmdService.onP2pCmdFinished].
     */
    fun routeAppData(payload: String) {
        logPayload("←(app)", "routeAppData", payload)
        val cmdId = try {
            JSONObject(payload).optString(KEY_CMD_SERVICE_ID, "")
        } catch (_: Exception) {
            ""
        }
        if (cmdId.isNotBlank()) {
            pendingRemoteCommands.remove(cmdId)?.complete(payload)
            return
        }
        // NAS may omit cmd_service_id on app JSON; complete the single in-flight waiter (connect path is serial).
        if (pendingRemoteCommands.size == 1) {
            val (onlyId, deferred) = pendingRemoteCommands.entries.first()
            if (pendingRemoteCommands.remove(onlyId) != null && !deferred.isCompleted) {
                Log.i(
                    TAG,
                    "routeAppData: no cmd_service_id; completing sole pending id=$onlyId len=${payload.length}",
                )
                deferred.complete(payload)
            }
            return
        }
        _messages.tryEmit(payload)
    }

    /**
     * Sends a NAS JSON command over the P2P IoT channel (`{mac}/control`, Base64) and
     * suspends until the response arrives on `{mac}/{clientId}` or [timeoutMs] elapses.
     * Mirrors Qt [CmdService::send] + [CmdService::onP2pCmdFinished] for the remote path.
     *
     * @param commandWithId JSON that already contains [KEY_CMD_SERVICE_ID].
     */
    suspend fun postAndWaitCommand(
        mac: String,
        commandWithId: String,
        timeoutMs: Long = Constants.REMOTE_COMMAND_TIMEOUT_MS,
    ): String {
        val cmdId = try {
            JSONObject(commandWithId).optString(KEY_CMD_SERVICE_ID, "")
        } catch (_: Exception) {
            ""
        }
        if (cmdId.isBlank()) {
            publishP2pControl(mac, commandWithId)
            return ""
        }
        val deferred = CompletableDeferred<String>()
        pendingRemoteCommands[cmdId] = deferred
        publishP2pControl(mac, commandWithId)
        return try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
                ?: run {
                    Log.w(TAG, "postAndWaitCommand timeout cmd_service_id=$cmdId mac=$mac")
                    ""
                }
        } finally {
            pendingRemoteCommands.remove(cmdId)
        }
    }

    fun destroy() {
        signalingListener = null
        p2pPayloadHandler = null
        p2pTopic = null
        pendingRemoteCommands.values.forEach { it.cancel() }
        pendingRemoteCommands.clear()
        disconnect()
    }

    companion object {
        private const val KEY_CMD_SERVICE_ID = "cmd_service_id"
    }

    private fun redactForLog(payload: String): String {
        return try {
            val o = JSONObject(payload)
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
            payload.replace(
                Regex("\"(user_passwd|admin_pwd|new_passwd)\"\\s*:\\s*\"[^\"]*\""),
                "\"$1\":\"***\"",
            )
        }
    }

    private fun logPayload(direction: String, topic: String, payload: String) {
        if (!DEBUG_IOT_PAYLOAD) return
        val redacted = redactForLog(payload)
        val preview = redacted
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .let { if (it.length > 400) it.take(400) + " …" else it }
        Log.d(TAG, "MQTT $direction topic=$topic len=${payload.length} payload=$preview")
    }
}
