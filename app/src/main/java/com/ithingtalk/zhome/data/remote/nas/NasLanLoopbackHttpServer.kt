package com.ithingtalk.zhome.data.remote.nas

import android.util.Log
import okhttp3.Credentials
import okhttp3.Request
import okhttp3.Response as OkHttpResponse
import java.io.BufferedOutputStream
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.text.Charsets

/**
 * Loopback HTTP on 127.0.0.1:[LISTEN_PORT] for LAN NAS HTTPS playback — VLC/ExoPlayer use plain HTTP;
 * upstream uses [NasTrustingSsl.upstreamClient] with optional Basic auth (aligned with iOS `NasLanLoopbackHttpServer`).
 */
object NasLanLoopbackHttpServer {

    const val LISTEN_PORT: Int = 6000
    private const val TAG = "LAN-LOOPBACK"
    private const val PATH_PREFIX = "/zhome-lan/"
    private const val MAX_HEADER_BYTES = 64 * 1024

    private data class Registration(
        val upstreamUrl: String,
        val basicUsername: String?,
        val basicPassword: String?,
    )

    private val registrations = ConcurrentHashMap<UUID, Registration>()
    private val lock = Any()
    @Volatile
    private var serverSocket: ServerSocket? = null
    private val acceptRunning = AtomicBoolean(false)

    fun prepareListenerAtLaunch() {
        synchronized(lock) {
            if (serverSocket != null && !serverSocket!!.isClosed) return
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), LISTEN_PORT))
                serverSocket = ss
                Log.i(TAG, "bound 127.0.0.1:$LISTEN_PORT")
            } catch (e: Exception) {
                Log.e(TAG, "could not bind 127.0.0.1:$LISTEN_PORT", e)
                serverSocket = null
                return
            }
        }
        if (acceptRunning.compareAndSet(false, true)) {
            Thread({ acceptLoop() }, "zhome-lan-loopback").apply {
                isDaemon = true
                start()
            }
        }
    }

    private fun acceptLoop() {
        while (!Thread.currentThread().isInterrupted) {
            val ss = synchronized(lock) { serverSocket } ?: break
            if (ss.isClosed) break
            try {
                val client = ss.accept()
                if (!client.inetAddress.isLoopbackAddress) {
                    client.close()
                    continue
                }
                Thread({ handleClient(client) }, "zhome-lan-conn").apply {
                    isDaemon = true
                    start()
                }
            } catch (_: InterruptedException) {
                break
            } catch (e: Exception) {
                if (!ss.isClosed) Log.w(TAG, "accept: ${e.message}")
            }
        }
        acceptRunning.set(false)
    }

    /**
     * Registers upstream HTTPS URL and returns playback `http://127.0.0.1:...` URL and session id for [unregister].
     */
    fun register(upstreamUrl: String, basicUsername: String?, basicPassword: String?): Pair<String, UUID>? {
        prepareListenerAtLaunch()
        synchronized(lock) {
            if (serverSocket == null || serverSocket!!.isClosed) return null
        }
        val id = UUID.randomUUID()
        registrations[id] = Registration(upstreamUrl.trim(), basicUsername?.trim(), basicPassword?.trim())
        val url = "http://127.0.0.1:$LISTEN_PORT$PATH_PREFIX$id"
        return url to id
    }

    fun unregister(sessionId: UUID) {
        registrations.remove(sessionId)
    }

    private fun handleClient(socket: Socket) {
        try {
            socket.soTimeout = 120_000
            val input = socket.getInputStream()
            val headerBytes = readUntilDoubleCrLf(input) ?: return
            val headerText = headerBytes.toString(Charsets.UTF_8)
            val lines = headerText.split("\r\n")
            val first = lines.firstOrNull() ?: return
            val parts = first.split(' ')
            if (parts.size < 2) return
            val method = parts[0].uppercase()
            val rawPath = parts[1]
            val pathOnly = rawPath.substringBefore('?')
            if (!pathOnly.startsWith(PATH_PREFIX)) {
                writeTextResponse(socket, 404, "Not Found")
                return
            }
            val idStr = pathOnly.removePrefix(PATH_PREFIX)
            val sessionId = runCatching { UUID.fromString(idStr) }.getOrNull()
            val reg = sessionId?.let { registrations[it] }
            if (sessionId == null || reg == null) {
                writeTextResponse(socket, 404, "Unknown session")
                return
            }
            var rangeStart = 0L
            for (line in lines.drop(1)) {
                val lower = line.lowercase()
                if (lower.startsWith("range:")) {
                    val idx = line.indexOf(':')
                    if (idx >= 0) {
                        val v = line.substring(idx + 1).trim().lowercase()
                        val bytesIdx = v.indexOf("bytes=")
                        if (bytesIdx >= 0) {
                            val tail = v.substring(bytesIdx + "bytes=".length)
                            val num = tail.substringBefore('-').trim()
                            rangeStart = num.toLongOrNull() ?: 0L
                        }
                    }
                }
            }
            when (method) {
                "HEAD" -> performHead(socket, reg, rangeStart)
                "GET" -> performGet(socket, reg, rangeStart)
                else -> writeTextResponse(socket, 405, "Method Not Allowed")
            }
        } catch (e: Exception) {
            Log.w(TAG, "handleClient: ${e.message}")
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun readUntilDoubleCrLf(input: java.io.InputStream): ByteArray? {
        val buf = ArrayList<Byte>(512)
        var prev = 0.toByte()
        var prev2 = 0.toByte()
        var prev3 = 0.toByte()
        while (buf.size < MAX_HEADER_BYTES) {
            val b = input.read()
            if (b < 0) return null
            val bb = b.toByte()
            buf.add(bb)
            if (buf.size >= 4 && prev3 == 13.toByte() && prev2 == 10.toByte() && prev == 13.toByte() && bb == 10.toByte()) {
                return buf.toByteArray()
            }
            prev3 = prev2
            prev2 = prev
            prev = bb
        }
        return null
    }

    private fun applyBasicAuth(builder: Request.Builder, reg: Registration) {
        val u = reg.basicUsername.orEmpty()
        val p = reg.basicPassword.orEmpty()
        if (u.isEmpty() && p.isEmpty()) return
        builder.header("Authorization", Credentials.basic(u, p))
    }

    private fun performHead(socket: Socket, reg: Registration, rangeStart: Long) {
        val req = Request.Builder()
            .url(reg.upstreamUrl)
            .head()
            .apply { applyBasicAuth(this, reg) }
            .build()
        val resp = try {
            NasTrustingSsl.upstreamClient.newCall(req).execute()
        } catch (e: Exception) {
            Log.w(TAG, "HEAD upstream: ${e.message}")
            writeTextResponse(socket, 502, "Bad Gateway")
            return
        }
        resp.use { r ->
            val total = contentTotalBytes(r, rangeStart)
            if (total <= 0L) {
                writeTextResponse(socket, 502, "Bad Gateway")
                return@use
            }
            val cl = total - rangeStart
            val header = "HTTP/1.1 200 OK\r\n" +
                "Content-Type: application/octet-stream\r\n" +
                "Accept-Ranges: bytes\r\n" +
                "Content-Length: $cl\r\n" +
                "Connection: close\r\n" +
                "\r\n"
            socket.getOutputStream().write(header.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
        }
    }

    private fun contentTotalBytes(resp: OkHttpResponse, rangeStart: Long): Long {
        val http = resp
        if (http.code == 200) {
            val cl = http.header("Content-Length")?.toLongOrNull()
            if (cl != null && cl > 0) return cl
            val len = http.body?.contentLength() ?: -1L
            if (len > 0) return len
        }
        val cr = http.header("Content-Range") ?: return -1L
        val slash = cr.lastIndexOf('/')
        if (slash < 0 || slash >= cr.length - 1) return -1L
        return cr.substring(slash + 1).toLongOrNull() ?: -1L
    }

    private fun performGet(socket: Socket, reg: Registration, rangeStart: Long) {
        val req = Request.Builder()
            .url(reg.upstreamUrl)
            .get()
            .header("Range", "bytes=$rangeStart-")
            .apply { applyBasicAuth(this, reg) }
            .build()
        val resp = try {
            NasTrustingSsl.upstreamClient.newCall(req).execute()
        } catch (e: Exception) {
            Log.w(TAG, "GET upstream: ${e.message}")
            writeTextResponse(socket, 502, "Bad Gateway")
            return
        }
        resp.use { r ->
            if (!r.isSuccessful || r.body == null) {
                writeTextResponse(socket, 502, "Bad Gateway")
                return@use
            }
            val body = r.body!!
            val code = r.code
            val statusLine = when (code) {
                206 -> "HTTP/1.1 206 Partial Content"
                200 -> "HTTP/1.1 200 OK"
                else -> "HTTP/1.1 $code ${r.message.ifBlank { "OK" }}"
            }
            val out = BufferedOutputStream(socket.getOutputStream())
            val w = BufferedWriter(OutputStreamWriter(out, Charsets.UTF_8))
            w.write("$statusLine\r\n")
            var wroteContentLength = false
            var wroteContentRange = false
            for (i in 0 until r.headers.size) {
                val name = r.headers.name(i)
                val lower = name.lowercase()
                if (lower == "connection" || lower == "transfer-encoding") continue
                if (lower == "content-length") wroteContentLength = true
                if (lower == "content-range") wroteContentRange = true
                w.write(name)
                w.write(": ")
                w.write(r.headers.value(i))
                w.write("\r\n")
            }
            if (!wroteContentLength && body.contentLength() >= 0) {
                w.write("Content-Length: ${body.contentLength()}\r\n")
            }
            if (!wroteContentRange && code == 206) {
                val cl = body.contentLength().takeIf { it >= 0 } ?: 0L
                val total = contentTotalBytes(r, rangeStart)
                val end = if (total > 0) total - 1 else rangeStart + cl - 1
                val crTotal = if (total > 0) total else rangeStart + cl
                w.write("Content-Range: bytes $rangeStart-$end/$crTotal\r\n")
            }
            w.write("Connection: close\r\n")
            w.write("\r\n")
            w.flush()
            body.byteStream().use { ins -> ins.copyTo(out) }
            out.flush()
        }
    }

    private fun writeTextResponse(socket: Socket, status: Int, body: String) {
        val reason = when (status) {
            400 -> "Bad Request"
            404 -> "Not Found"
            405 -> "Method Not Allowed"
            else -> "Error"
        }
        val text = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/plain; charset=utf-8\r\n" +
            "Content-Length: ${body.toByteArray(Charsets.UTF_8).size}\r\n" +
            "Connection: close\r\n" +
            "\r\n" +
            body
        socket.getOutputStream().write(text.toByteArray(Charsets.UTF_8))
        socket.getOutputStream().flush()
    }
}
