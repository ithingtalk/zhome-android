package com.ithingtalk.zhome.network

import android.content.Context
import android.net.wifi.WifiManager
import android.util.Base64
import android.util.Log
import com.ithingtalk.zhome.Constants
import kotlinx.coroutines.*
import java.net.*

data class DiscoveredDevice(
    val mac: String,
    val sn: String,
    val name: String,
    val cfg: String,
    val ip: String
)

/**
 * UDP broadcast to discover NAS devices on the local network,
 * then read TCP response. Mirrors Qt's SearchLocalIdevice.
 */
class LocalDiscovery(private val context: Context) {

    private val TAG = "LocalDiscovery"
    private val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager

    /**
     * @param onDeviceFound Invoked on the IO dispatcher as soon as each unique device replies (incremental UI).
     */
    suspend fun search(
        timeoutMs: Long = 1500,
        onDeviceFound: suspend (DiscoveredDevice) -> Unit = {}
    ): List<DiscoveredDevice> = withContext(Dispatchers.IO) {
        val found = mutableListOf<DiscoveredDevice>()
        var multicastLock: WifiManager.MulticastLock? = null
        var tcpServer: ServerSocket? = null
        var udp: DatagramSocket? = null
        
        try {
            Log.d(TAG, "Starting search (timeout: $timeoutMs ms)...")
            
            // Acquire MulticastLock - crucial for receiving UDP on many Android devices
            multicastLock = wifiManager.createMulticastLock("zhome_discovery").apply {
                setReferenceCounted(false)
                acquire()
            }

            // Fixed TCP port — matches Qt SearchLocalIdevice (tcpPort 10001); NAS connects here after UDP broadcast
            tcpServer = ServerSocket().apply {
                reuseAddress = true
                bind(InetSocketAddress(Constants.LOCAL_TCP_DISCOVERY_PORT))
                soTimeout = timeoutMs.toInt()
            }
            Log.d(TAG, "TCP server listening on ${Constants.LOCAL_TCP_DISCOVERY_PORT} (Qt-compatible)")

            // Prepare UDP broadcast: Base64("T-NAS?") like Qt sendBroadcast()
            udp = DatagramSocket().apply { broadcast = true }
            val plainCmd = Constants.BROADCAST_SEARCH
            val data = Base64.encodeToString(plainCmd.toByteArray(Charsets.UTF_8), Base64.NO_WRAP).toByteArray(Charsets.UTF_8)
            
            // Try to send to 255.255.255.255 and also specific interface broadcast addresses
            val targets = mutableSetOf<InetAddress>()
            targets.add(InetAddress.getByName("255.255.255.255"))
            
            try {
                val interfaces = NetworkInterface.getNetworkInterfaces()
                while (interfaces.hasMoreElements()) {
                    val iface = interfaces.nextElement()
                    if (iface.isLoopback || !iface.isUp) continue
                    for (ifaceAddr in iface.interfaceAddresses) {
                        ifaceAddr.broadcast?.let { targets.add(it) }
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to enumerate network interfaces", e)
            }

            Log.d(TAG, "Sending broadcast to ${targets.size} targets (B64 of '$plainCmd') on UDP ${Constants.LOCAL_UDP_BROADCAST_PORT}")
            
            for (target in targets) {
                try {
                    val packet = DatagramPacket(data, data.size, target, Constants.LOCAL_UDP_BROADCAST_PORT)
                    udp.send(packet)
                    Log.v(TAG, "Sent UDP packet to ${target.hostAddress}")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to send to ${target.hostAddress}", e)
                }
            }

            // Accept TCP connections until timeout
            val deadline = System.currentTimeMillis() + timeoutMs
            while (isActive && System.currentTimeMillis() < deadline) {
                val remaining = (deadline - System.currentTimeMillis()).toInt()
                if (remaining <= 100) break
                
                try {
                    tcpServer.soTimeout = remaining
                    val socket = tcpServer.accept()
                    val remoteIp = socket.inetAddress.hostAddress ?: ""
                    Log.d(TAG, "Received connection from NAS at $remoteIp")
                    
                    val response = socket.getInputStream().bufferedReader().readLine() ?: ""
                    Log.i(TAG, "NAS Response: '$response' from $remoteIp")
                    socket.close()
                    
                    parseDevice(response, remoteIp)?.let { device ->
                        if (found.none { it.mac == device.mac }) {
                            found.add(device)
                            Log.d(TAG, "Parsed device: ${device.name} [${device.mac}]")
                            onDeviceFound(device)
                        }
                    }
                } catch (e: SocketTimeoutException) {
                    break
                } catch (e: Exception) {
                    if (isActive) Log.w(TAG, "Error accepting NAS connection", e)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Discovery process failed", e)
        } finally {
            try { udp?.close() } catch (_: Exception) {}
            try { tcpServer?.close() } catch (_: Exception) {}
            try {
                if (multicastLock?.isHeld == true) multicastLock.release()
            } catch (_: Exception) {}
            Log.d(TAG, "Search complete. Found ${found.size} devices.")
        }
        found
    }

    private fun parseDevice(line: String, fallbackIp: String): DiscoveredDevice? {
        val raw = line.trim()
        if (raw.isBlank()) return null
        // Qt readClient(): QByteArray::fromBase64(data) then split '/'
        val decoded = try {
            String(Base64.decode(raw, Base64.DEFAULT), Charsets.UTF_8).trim()
        } catch (e: Exception) {
            Log.w(TAG, "Base64 decode failed, trying plain line", e)
            raw
        }
        val parts = decoded.split("/")
        if (parts.size < 5) {
            Log.w(TAG, "Invalid device info (need mac/sn/name/cfg/ip): '$decoded'")
            return null
        }
        val ipField = parts[4].trim()
        return DiscoveredDevice(
            mac = parts[0],
            sn = parts[1],
            name = parts[2],
            cfg = parts[3],
            ip = ipField.ifBlank { fallbackIp }
        )
    }
}
