package com.ithingtalk.zhome

import android.content.Context
import android.util.Log
import org.json.JSONObject

object AwsConfig {
    private const val TAG = "AwsConfig"
    private const val FILE_NAME = "awsconfig.json"

    data class Config(
        val apiGatewayInvokeUrl: String,
        val userPoolId: String,
        val userPoolClientId: String,
        val identityPoolId: String,
        val iotHost: String,
        val region: String,
    )

    @Volatile
    private var current: Config? = null

    val apiGatewayInvokeUrl: String
        get() = requireCurrent().apiGatewayInvokeUrl
    val userPoolId: String
        get() = requireCurrent().userPoolId
    val userPoolClientId: String
        get() = requireCurrent().userPoolClientId
    val identityPoolId: String
        get() = requireCurrent().identityPoolId
    val iotHost: String
        get() = requireCurrent().iotHost
    val region: String
        get() = requireCurrent().region

    fun init(context: Context) {
        if (current != null) return
        val loaded = try {
            load(context)
        } catch (t: Throwable) {
            throw IllegalStateException("Failed to load AWS config from assets/$FILE_NAME", t)
        }
        current = loaded
        logLoaded(loaded)
    }

    private fun requireCurrent(): Config {
        return current ?: error("AwsConfig is not initialized. Call AwsConfig.init(context) first.")
    }

    private fun load(context: Context): Config {
        val text = context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
        val json = JSONObject(text)
        return Config(
            apiGatewayInvokeUrl = required(json, "AwsApiGatewayInvokeUrl"),
            userPoolId = required(json, "UserPoolId"),
            userPoolClientId = required(json, "UserPoolClientId"),
            identityPoolId = required(json, "IdentityPoolId"),
            iotHost = required(json, "AwsIotHost"),
            region = required(json, "AwsRegion"),
        )
    }

    private fun required(json: JSONObject, key: String): String {
        val value = json.optString(key, "").trim()
        check(value.isNotEmpty()) { "Missing or empty key '$key' in assets/$FILE_NAME" }
        return value
    }

    private fun logLoaded(config: Config) {
        Log.i(
            TAG,
            "Loaded $FILE_NAME region=${config.region} iotHost=${config.iotHost} " +
                "apiGateway=${config.apiGatewayInvokeUrl} userPoolId=${config.userPoolId} " +
                "identityPoolId=${config.identityPoolId} userPoolClientId=${mask(config.userPoolClientId)}",
        )
    }

    private fun mask(value: String): String {
        if (value.length <= 6) return "***"
        return value.take(3) + "***" + value.takeLast(3)
    }
}
