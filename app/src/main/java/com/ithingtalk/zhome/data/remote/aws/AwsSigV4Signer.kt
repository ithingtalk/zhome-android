package com.ithingtalk.zhome.data.remote.aws

import com.amazonaws.DefaultRequest
import com.amazonaws.auth.AWS4Signer
import com.amazonaws.auth.BasicSessionCredentials
import com.amazonaws.http.HttpMethodName
import com.ithingtalk.zhome.AwsConfig
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayInputStream
import java.net.URI
import java.nio.charset.StandardCharsets

/**
 * API Gateway **execute-api** SigV4，使用与 IoT 依赖相同的 [AWS4Signer]（aws-android-sdk-core），
 * 避免手写 canonical 与路径编码（含 double-encode 开关）和 Gateway 验签不一致。
 */
object AwsSigV4Signer {

    /**
     * @param pathSuffix e.g. `/idevices/all` (appended after [AwsConfig.apiGatewayInvokeUrl] stage path)
     */
    fun signedPost(
        pathSuffix: String,
        jsonBody: String,
        accessKeyId: String,
        secretAccessKey: String,
        sessionToken: String,
    ): Request {
        val ak = accessKeyId.trim()
        val sk = secretAccessKey.trim()
        val st = sessionToken.trim()

        val bodyBytes = jsonBody.toByteArray(StandardCharsets.UTF_8)
        val baseHttp = AwsConfig.apiGatewayInvokeUrl.trimEnd('/').toHttpUrl()
        val scheme = baseHttp.scheme
        val host = baseHttp.host
        val stagePath = baseHttp.encodedPath.trimEnd('/').removePrefix("/")
        val suffix = pathSuffix.trim('/')
        val resourcePath =
            if (stagePath.isEmpty()) {
                "/$suffix"
            } else {
                "/$stagePath/$suffix"
            }
        val fullUrl = "$scheme://$host$resourcePath".toHttpUrl()

        val awsReq = DefaultRequest<Any>("execute-api")
        awsReq.httpMethod = HttpMethodName.POST
        awsReq.endpoint = URI.create("$scheme://$host")
        awsReq.resourcePath = resourcePath
        awsReq.content = ByteArrayInputStream(bodyBytes)

        // 与 Qt awsDbService 一致：application/json（不由手写 SigV4 参与 content-type 时易与 SDK 验签不一致）
        awsReq.addHeader("Content-Type", "application/json")
        awsReq.addHeader("Accept", "application/json")

        val signer =
            AWS4Signer(false).apply {
                setServiceName("execute-api")
                setRegionName(AwsConfig.region)
            }
        signer.sign(awsReq, BasicSessionCredentials(ak, sk, st))

        val builder =
            Request.Builder()
                .url(fullUrl)
                // Content-Type 仅来自签名后的头，避免与 toRequestBody(mediaType) 重复导致与验签不一致
                .post(bodyBytes.toRequestBody(null))

        @Suppress("UNCHECKED_CAST")
        val signedHeaders = awsReq.headers as Map<String, String>
        for ((name, value) in signedHeaders) {
            if (name.equals("Content-Length", ignoreCase = true)) continue
            builder.addHeader(name, value)
        }
        return builder.build()
    }
}
