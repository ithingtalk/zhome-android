package com.ithingtalk.zhome.data.remote.nas

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import okio.BufferedSink
import okio.buffer
import java.io.File

object NasHttpUpload {
    private val client get() = NasTrustingSsl.nasCommandClient
    private val octetStream = "application/octet-stream".toMediaType()

    suspend fun putAuthenticated(
        url: String,
        user: String,
        pass: String,
        file: File,
        onProgress: ((sent: Long, total: Long) -> Unit)? = null,
    ): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                val total = file.length().coerceAtLeast(0L)
                val body = if (onProgress != null) {
                    ProgressRequestBody(file.asRequestBody(octetStream), total, onProgress)
                } else {
                    file.asRequestBody(octetStream)
                }
                val rb = Request.Builder()
                    .url(url)
                    .put(body)
                if (user.isNotBlank() || pass.isNotBlank()) {
                    rb.header("Authorization", Credentials.basic(user, pass))
                }
                val req = rb.build()
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) error("HTTP ${resp.code}")
                }
                Unit
            }
        }

    private class ProgressRequestBody(
        private val delegate: RequestBody,
        private val total: Long,
        private val onProgress: (sent: Long, total: Long) -> Unit,
    ) : RequestBody() {
        override fun contentType() = delegate.contentType()
        override fun contentLength() = delegate.contentLength()
        override fun writeTo(sink: BufferedSink) {
            val countingSink = object : okio.ForwardingSink(sink) {
                var sent = 0L
                override fun write(source: okio.Buffer, byteCount: Long) {
                    super.write(source, byteCount)
                    sent += byteCount
                    if (total > 0L) onProgress(sent, total)
                }
            }
            val buffered = countingSink.buffer()
            delegate.writeTo(buffered)
            buffered.flush()
        }
    }
}

