package com.ithingtalk.zhome.jni

import android.util.Log

/**
 * JNI: libjuice 调试入口 + **libip2p**（Juice/lsquic/QUIC 与 Qt 端预置 libip2p 栈一致）。
 */
object NativeBridge {
    init {
        System.loadLibrary("zhome_native")
    }

    // ---- libip2p (libip2p.a + libp2p_export.h) ----

    /** One-time; registers IoT send / app recv callbacks inside native. */
    external fun libp2pInit(iceGatherMode: Int)

    external fun libp2pExit()

    /**
     * Payload after Base64 decode from MQTT topic `{mac}/{clientId}` (same as Qt awsIot).
     * Returns true if the message was a P2P-internal packet (handled by libp2p).
     * Returns false if it is a NAS app-layer response that must be routed to Kotlin.
     */
    external fun libp2pRecvFromIot(decodedUtf8: String): Boolean

    /**
     * 同步 App ICE gather mode 到 libip2p（0=both，1=P2P-only，2=relay-only）。
     * 若与库内当前值不同，会停止所有 P2P 连接；之后上传/下载/视频均使用该全局值。
     */
    external fun libp2pUpdateIceGatherMode(iceGatherMode: Int)

    external fun libp2pIntoDevice(
        homeDir: String,
        cacheDir: String,
        appIotId: String,
        nasId: String,
    )

    external fun libp2pLeaveDevice()

    /** `P2P_HTTP_IP` from libp2p_export.h */
    external fun libp2pHttpProxyHost(): String

    /** `P2P_HTTP_PORT` from libp2p_export.h */
    external fun libp2pHttpProxyPort(): Int

    external fun libp2pDownloadFile(remotePath: String, localPath: String)

    external fun libp2pUploadFile(localPath: String, remotePath: String)

    /** libp2p asks to publish to `{mac}/control` (Kotlin Base64-encodes like Qt). */
    @JvmStatic
    fun onLibp2pIotSend(msg: String) {
        libp2pIotSendHandler?.invoke(msg) ?: Log.w("RemoteLink", "libp2p iot_send: no handler")
    }

    /** File transfer / status JSON from libp2p (optional UI). */
    @JvmStatic
    fun onLibp2pAppMessage(msg: String) {
        libp2pAppMessageHandler?.invoke(msg)
    }

    var libp2pIotSendHandler: ((String) -> Unit)? = null
    /** Non-P2P messages from `{mac}/{clientId}` — NAS command responses routed to IoT service. */
    var libp2pAppDataHandler: ((String) -> Unit)? = null
    /** File transfer status from libp2p (progress, success, error) — used by file download waiters. */
    var libp2pAppMessageHandler: ((String) -> Unit)? = null
}
