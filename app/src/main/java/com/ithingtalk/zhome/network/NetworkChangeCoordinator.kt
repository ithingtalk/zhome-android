package com.ithingtalk.zhome.network

import android.content.Context
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class NetworkChangeEvent(
    val status: NetworkStatus,
    val typeChanged: Boolean,
    val connectedChanged: Boolean,
    /** Wi‑Fi/wired became available (e.g. user turned Wi‑Fi on while on cellular). */
    val localDiscoveryBecameAvailable: Boolean,
) {
    val isDisruptive: Boolean get() = typeChanged || connectedChanged
}

/**
 * App-wide network change bus (aligned with iOS `NotificationCenter.networkDidChange`).
 */
object NetworkChangeCoordinator {

    private val _events = MutableSharedFlow<NetworkChangeEvent>(extraBufferCapacity = 4)
    val events: SharedFlow<NetworkChangeEvent> = _events.asSharedFlow()

    private var unregister: (() -> Unit)? = null
    private var lastType: ConnectionType? = null
    private var lastConnected: Boolean? = null
    private var lastSupportsLocalDiscovery: Boolean? = null

    fun start(context: Context) {
        if (unregister != null) return
        val appCtx = context.applicationContext
        unregister = NetworkMonitor.register(appCtx) { status ->
            val typeChanged = lastType != null && lastType != status.type
            val connectedChanged = lastConnected != null && lastConnected != status.isConnected
            val localDiscoveryBecameAvailable =
                status.supportsLocalDiscovery &&
                    lastSupportsLocalDiscovery == false
            lastType = status.type
            lastConnected = status.isConnected
            lastSupportsLocalDiscovery = status.supportsLocalDiscovery
            if (typeChanged || connectedChanged) {
                _events.tryEmit(
                    NetworkChangeEvent(
                        status = status,
                        typeChanged = typeChanged,
                        connectedChanged = connectedChanged,
                        localDiscoveryBecameAvailable = localDiscoveryBecameAvailable,
                    ),
                )
            }
        }
    }

    fun stop() {
        unregister?.invoke()
        unregister = null
        lastType = null
        lastConnected = null
        lastSupportsLocalDiscovery = null
    }
}
