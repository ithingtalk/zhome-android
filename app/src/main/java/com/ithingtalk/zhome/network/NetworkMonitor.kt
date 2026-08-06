package com.ithingtalk.zhome.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

enum class ConnectionType {
    Wifi,
    Cellular,
    Other,
    None,
}

data class NetworkStatus(
    val isConnected: Boolean,
    val type: ConnectionType,
) {
    /** Android phones: LAN discovery only on Wi‑Fi (no Ethernet). */
    val supportsLocalDiscovery: Boolean
        get() = isConnected && type == ConnectionType.Wifi

    val supportsCloudSync: Boolean
        get() = isConnected
}

object NetworkMonitor {
    fun supportsLocalDiscovery(context: Context): Boolean =
        currentStatus(context).supportsLocalDiscovery

    fun supportsCloudSync(context: Context): Boolean =
        currentStatus(context).supportsCloudSync

    private fun typeOf(cap: NetworkCapabilities?): ConnectionType {
        if (cap == null) return ConnectionType.None
        return when {
            cap.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> ConnectionType.Wifi
            cap.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> ConnectionType.Cellular
            cap.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> ConnectionType.Other
            cap.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> ConnectionType.Other
            else -> ConnectionType.Other
        }
    }

    fun currentStatus(context: Context): NetworkStatus {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return NetworkStatus(isConnected = false, type = ConnectionType.None)
        val active = cm.activeNetwork ?: return NetworkStatus(isConnected = false, type = ConnectionType.None)
        val cap = cm.getNetworkCapabilities(active)
        val connected = cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return NetworkStatus(isConnected = connected, type = if (connected) typeOf(cap) else ConnectionType.None)
    }

    /**
     * Registers a connectivity callback and returns an unregister function.
     * Callback is invoked on the binder thread; callers should hop to main if needed.
     */
    fun register(context: Context, onChange: (NetworkStatus) -> Unit): () -> Unit {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            ?: return {}
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = onChange(statusFor(cm, network))
            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) =
                onChange(statusFor(cm, network))
            override fun onLost(network: Network) = onChange(currentStatus(context))
        }
        val req = NetworkRequest.Builder().build()
        cm.registerNetworkCallback(req, cb)
        // Emit initial state.
        onChange(currentStatus(context))
        return { runCatching { cm.unregisterNetworkCallback(cb) } }
    }

    private fun statusFor(cm: ConnectivityManager, network: Network): NetworkStatus {
        val cap = cm.getNetworkCapabilities(network)
        val connected = cap?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        return NetworkStatus(isConnected = connected, type = if (connected) typeOf(cap) else ConnectionType.None)
    }
}

