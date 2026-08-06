package com.ithingtalk.zhome.data.remote.aws

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.json.JSONArray
import org.json.JSONObject

/**
 * API Gateway 设备注册（awsdb / Qt [AwsDbService]）：**必须使用** Cognito Identity 换发的临时 AK/SK/ST 做 SigV4（service `execute-api`），
 * 路径 `/idevices/all`、`/idevices`、`/idevices/me`。与 [AwsIotService] 共用 [AuthRepository.getAwsCredentials] 写入的同一套会话，不用 User Pool JWT 填 Authorization。
 */
class AwsApiService {

    private val client = OkHttpClient()
    private val TAG = "AwsApi"

    private fun requireCreds(
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String,
    ) {
        require(accessKeyId.isNotBlank() && secretAccessKey.isNotBlank() && sessionToken.isNotBlank()) {
            "Missing AWS session credentials for API Gateway (refresh after sign-in)"
        }
    }

    suspend fun listDevices(
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String,
    ): List<CloudDevice> = withContext(Dispatchers.IO) {
        requireCreds(accessKeyId, secretAccessKey, sessionToken)
        val req =
            AwsSigV4Signer.signedPost(
                pathSuffix = "/idevices/all",
                jsonBody = "{}",
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
            )
        client.newCall(req).execute().use { resp ->
            val text = resp.body?.string() ?: ""
            if (!resp.isSuccessful) {
                Log.e(TAG, "listDevices HTTP ${resp.code} body=${text.take(500)}")
                error("API Gateway list devices failed: ${resp.code}")
            }
            Log.i(TAG, "listDevices OK: ${parseDeviceList(text).size} device(s) from cloud")
            parseDeviceList(text)
        }
    }

    suspend fun addDevice(
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String,
        mac: String,
        sn: String,
        name: String,
    ): Boolean = withContext(Dispatchers.IO) {
        requireCreds(accessKeyId, secretAccessKey, sessionToken)
        val body =
            JSONObject()
                .put("deviceId", mac)
                .put("deviceSn", sn)
                .put("deviceName", name)
                .put("deviceType", "IDEVICE")
                .toString()
        val req =
            AwsSigV4Signer.signedPost(
                pathSuffix = "/idevices",
                jsonBody = body,
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
            )
        client.newCall(req).execute().use { resp ->
            val ok = resp.isSuccessful
            if (!ok) {
                Log.e(TAG, "addDevice HTTP ${resp.code} ${resp.body?.string()?.take(300)}")
            }
            ok
        }
    }

    suspend fun deleteDevice(
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String,
        mac: String,
    ): Boolean = withContext(Dispatchers.IO) {
        requireCreds(accessKeyId, secretAccessKey, sessionToken)
        val body = JSONObject().put("deviceId", mac).toString()
        val req =
            AwsSigV4Signer.signedPost(
                pathSuffix = "/idevices/me",
                jsonBody = body,
                accessKeyId = accessKeyId,
                secretAccessKey = secretAccessKey,
                sessionToken = sessionToken,
            )
        client.newCall(req).execute().use { resp ->
            val ok = resp.isSuccessful
            if (!ok) {
                Log.e(TAG, "deleteDevice HTTP ${resp.code} ${resp.body?.string()?.take(300)}")
            }
            ok
        }
    }

    /** Qt returns a JSON array of objects with deviceId, deviceSn, deviceName, online */
    private fun parseDeviceList(jsonStr: String): List<CloudDevice> {
        val list = mutableListOf<CloudDevice>()
        try {
            when {
                jsonStr.trimStart().startsWith("[") -> {
                    val arr = JSONArray(jsonStr)
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list +=
                            CloudDevice(
                                mac = obj.optString("deviceId"),
                                sn = obj.optString("deviceSn"),
                                name = obj.optString("deviceName"),
                                online = obj.optString("online"),
                            )
                    }
                }
                jsonStr.trimStart().startsWith("{") -> {
                    val root = JSONObject(jsonStr)
                    val arr = root.optJSONArray("devices") ?: return list
                    for (i in 0 until arr.length()) {
                        val obj = arr.getJSONObject(i)
                        list +=
                            CloudDevice(
                                mac = obj.optString("mac", obj.optString("deviceId")),
                                sn = obj.optString("sn", obj.optString("deviceSn")),
                                name = obj.optString("name", obj.optString("deviceName")),
                                online = obj.optString("online"),
                            )
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "parseDeviceList failed: ${jsonStr.take(200)}", e)
        }
        return list
    }
}

data class CloudDevice(
    val mac: String,
    val sn: String = "",
    val name: String = "",
    val online: String = "",
)
