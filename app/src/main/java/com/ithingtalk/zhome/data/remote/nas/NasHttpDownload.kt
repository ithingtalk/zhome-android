package com.ithingtalk.zhome.data.remote.nas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.Request
import java.io.File

object NasHttpDownload {
    private val client get() = NasTrustingSsl.nasCommandClient

    private fun Request.Builder.withOptionalBasicAuth(user: String, pass: String): Request.Builder {
        if (user.isBlank() && pass.isBlank()) return this
        return header("Authorization", Credentials.basic(user, pass))
    }

    suspend fun downloadAuthenticated(
        url: String,
        user: String,
        pass: String,
        dest: File,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                dest.parentFile?.mkdirs()
                val req = Request.Builder()
                    .url(url)
                    .withOptionalBasicAuth(user, pass)
                    .build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("Empty response")
                    val total = body.contentLength().takeIf { it > 0 } ?: 0L
                    if (onProgress != null) onProgress(0L, total)
                    body.byteStream().use { input ->
                        java.io.BufferedOutputStream(java.io.FileOutputStream(dest, false)).use { output ->
                            val buf = ByteArray(64 * 1024)
                            var transferred = 0L
                            var r = input.read(buf)
                            while (r >= 0) {
                                output.write(buf, 0, r)
                                transferred += r
                                if (onProgress != null) onProgress(transferred, total)
                                r = input.read(buf)
                            }
                            output.flush()
                        }
                    }
                }
                Unit
            }
        }

    /**
     * Download with best-effort resume:
     * - If [dest] exists and has bytes, try `Range: bytes=<existing>-` and append when server returns 206.
     * - If server ignores Range (200) or resume fails, restart from scratch (overwrite [dest]).
     *
     * Progress callback reports total=0 when unknown.
     */
    suspend fun downloadAuthenticatedResume(
        url: String,
        user: String,
        pass: String,
        dest: File,
        onProgress: ((transferred: Long, total: Long) -> Unit)? = null,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                dest.parentFile?.mkdirs()
                val existing = dest.takeIf { it.isFile }?.length()?.takeIf { it > 0L } ?: 0L

                fun doRequest(rangeFrom: Long?): okhttp3.Response {
                    val b = Request.Builder()
                        .url(url)
                        .withOptionalBasicAuth(user, pass)
                    if (rangeFrom != null && rangeFrom > 0L) {
                        b.header("Range", "bytes=$rangeFrom-")
                    }
                    return client.newCall(b.build()).execute()
                }

                // 1) Attempt resume when possible.
                if (existing > 0L) {
                    doRequest(existing).use { resp ->
                        if (resp.code == 206) {
                            val body = resp.body ?: error("Empty response")
                            val remaining = body.contentLength().takeIf { it > 0 } ?: 0L
                            val total = if (remaining > 0L) existing + remaining else 0L
                            if (onProgress != null) onProgress(existing, total)
                            body.byteStream().use { input ->
                                java.io.BufferedOutputStream(java.io.FileOutputStream(dest, true)).use { output ->
                                    val buf = ByteArray(64 * 1024)
                                    var transferred = existing
                                    var r = input.read(buf)
                                    while (r >= 0) {
                                        output.write(buf, 0, r)
                                        transferred += r
                                        if (onProgress != null) onProgress(transferred, total)
                                        r = input.read(buf)
                                    }
                                    output.flush()
                                }
                            }
                            return@runCatching Unit
                        }
                        // If server ignores Range (200) or other code, fall through to restart.
                    }
                }

                // 2) Restart from scratch.
                doRequest(null).use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                    val body = resp.body ?: error("Empty response")
                    val total = body.contentLength().takeIf { it > 0 } ?: 0L
                    if (onProgress != null) onProgress(0L, total)
                    body.byteStream().use { input ->
                        java.io.BufferedOutputStream(java.io.FileOutputStream(dest, false)).use { output ->
                            val buf = ByteArray(64 * 1024)
                            var transferred = 0L
                            var r = input.read(buf)
                            while (r >= 0) {
                                output.write(buf, 0, r)
                                transferred += r
                                if (onProgress != null) onProgress(transferred, total)
                                r = input.read(buf)
                            }
                            output.flush()
                        }
                    }
                }

                Unit
            }
        }
}
