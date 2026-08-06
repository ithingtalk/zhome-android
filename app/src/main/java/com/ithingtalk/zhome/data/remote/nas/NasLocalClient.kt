package com.ithingtalk.zhome.data.remote.nas

import android.util.Base64
import android.util.Log
import com.ithingtalk.zhome.Constants
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.File
import java.io.FileOutputStream

/**
 * Local NAS HTTPS API — mirrors zhome-qml [cmdService.cpp] when [GlobalCpp.useLocalLink] is true:
 * POST `https://{ip}/cmd/cgi-bin/cmd.cgi` with `application/x-www-form-urlencoded`,
 * body `command=<base64>` with no URL percent-encoding (same as iOS [NasApiService.sendNasCommand]).
 * HTTP Basic auth uses app account user/password ([QAuthenticator] in Qt).
 * Response body is Base64-encoded UTF-8 JSON (firmware [cmd.cgi] must not append extra bytes).
 */
class NasLocalClient {

    private val tag = "NasLocalClient"

    suspend fun postCommand(
        deviceIp: String,
        commandJson: String,
        httpUser: String,
        httpPass: String,
        readTimeoutSec: Long = 60L,
    ): String = withContext(Dispatchers.IO) {
        val client = if (readTimeoutSec == 5L) NasTrustingSsl.nasCommandClientRead5s else NasTrustingSsl.nasCommandClient
        val url = "https://$deviceIp/cmd/cgi-bin/cmd.cgi"
        val encodedCmd = Base64.encodeToString(
            commandJson.toByteArray(Charsets.UTF_8),
            Base64.NO_WRAP
        )
        if (encodedCmd.contains('%')) {
            Log.w(tag, "LAN command base64 must not be URL-encoded (found '%')")
        }
        val body = buildCommandFormBody(encodedCmd)
        val bodyText = "command=$encodedCmd"
        if (bodyText.contains('%')) {
            Log.w(tag, "LAN POST body must not contain percent-encoding")
        }
        val auth = Credentials.basic(httpUser, httpPass)
        val request = Request.Builder()
            .url(url)
            .post(body)
            .header("Authorization", auth)
            .header("Content-Type", "application/x-www-form-urlencoded")
            .build()

        client.newCall(request).execute().use { response ->
            val rawBytes = response.body?.bytes() ?: ByteArray(0)
            val rawText = rawBytes.toString(Charsets.UTF_8).trim()
            if (!response.isSuccessful) {
                Log.e(tag, "NAS HTTP ${response.code} for $url — $rawText")
                throw IllegalStateException("NAS HTTP ${response.code}: ${response.message}")
            }
            if (rawText.isEmpty()) {
                throw IllegalStateException("Empty NAS response body")
            }
            try {
                decodeNasResponseBody(rawText)
            } catch (e: Exception) {
                Log.e(
                    tag,
                    "Failed to decode NAS response (len=${rawText.length}) preview=${rawText.take(300)}",
                    e,
                )
                throw IllegalStateException("Invalid NAS response (expected Base64 JSON)", e)
            }
        }
    }

    /**
     * POST body for cmd.cgi: literal `command=<base64>` (no URLEncoder / FormBody — NAS decodes literal Base64).
     */
    private fun buildCommandFormBody(base64Command: String): RequestBody {
        val bytes = "command=$base64Command".toByteArray(Charsets.UTF_8)
        return bytes.toRequestBody(FORM_URLENCODED_MEDIA_TYPE)
    }

    /**
     * Download binary via GET + Basic auth — Qt [NasApi::userDbFileUrl] / [LocalFileService] for `file.db`.
     * URL: `https://{ip}/file/download.cgi?file.db`
     */
    suspend fun downloadToFile(
        httpUrl: String,
        destFile: File,
        httpUser: String,
        httpPass: String
    ): Unit = withContext(Dispatchers.IO) {
        val reqBuilder = Request.Builder().url(httpUrl).get()
        if (httpUser.isNotBlank() || httpPass.isNotBlank()) {
            reqBuilder.header("Authorization", Credentials.basic(httpUser, httpPass))
        }
        val request = reqBuilder.build()
        NasTrustingSsl.nasCommandClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val err = response.body?.string() ?: ""
                Log.e(tag, "Download HTTP ${response.code} $httpUrl — $err")
                throw IllegalStateException("Download failed HTTP ${response.code}")
            }
            destFile.parentFile?.mkdirs()
            response.body!!.byteStream().use { input ->
                FileOutputStream(destFile).use { output -> input.copyTo(output) }
            }
        }
    }

    /**
     * LAN cmd.cgi returns Base64(JSON); some builds may return raw JSON (align with iOS decodeNasCommandResponse).
     */
    private fun decodeNasResponseBody(rawText: String): String {
        val trimmed = rawText.trim()
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            return trimmed
        }
        val standard = runCatching {
            String(Base64.decode(trimmed, Base64.NO_WRAP), Charsets.UTF_8).trim()
        }.getOrNull()
        if (!standard.isNullOrBlank() && (standard.startsWith("{") || standard.startsWith("["))) {
            return standard
        }
        val padded = runCatching {
            String(Base64.decode(trimmed, Base64.DEFAULT), Charsets.UTF_8).trim()
        }.getOrNull()
        if (!padded.isNullOrBlank() && (padded.startsWith("{") || padded.startsWith("["))) {
            return padded
        }
        var urlSafe = trimmed.replace('-', '+').replace('_', '/')
        when (urlSafe.length % 4) {
            2 -> urlSafe += "=="
            3 -> urlSafe += "="
        }
        val urlDecoded = runCatching {
            String(Base64.decode(urlSafe, Base64.NO_WRAP), Charsets.UTF_8).trim()
        }.getOrNull()
        if (!urlDecoded.isNullOrBlank()) {
            return urlDecoded
        }
        throw IllegalStateException("Unrecognized NAS response format")
    }

    companion object {
        private val FORM_URLENCODED_MEDIA_TYPE =
            "application/x-www-form-urlencoded".toMediaType()

        /** Matches [NasApi::dbFileDownloadAddress] + [NasApi::userDbFileName]. */
        fun userDbDownloadUrl(deviceIp: String): String =
            "https://$deviceIp/file/download.cgi?${Constants.NAS_USER_DB_FILE}"

        /** Matches Qt [NasApi::shareDbFileUrl] (`download.cgi?../SHARED/shared.db`). */
        fun sharedDbDownloadUrl(deviceIp: String): String =
            "https://$deviceIp/file/download.cgi?${Constants.TAG_SHARED_DB_FILE}"
    }
}
