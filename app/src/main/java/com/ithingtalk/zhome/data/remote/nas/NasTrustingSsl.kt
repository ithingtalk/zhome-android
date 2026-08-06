package com.ithingtalk.zhome.data.remote.nas

import okhttp3.OkHttpClient
import java.security.SecureRandom
import java.util.concurrent.TimeUnit
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager

/**
 * OkHttp TLS that accepts NAS self-signed certificates (LAN only — same role as iOS `TrustAllCertsDelegate`).
 * Do not use for arbitrary internet hosts.
 */
object NasTrustingSsl {

    private val trustManager: X509TrustManager = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun checkServerTrusted(chain: Array<java.security.cert.X509Certificate>, authType: String) {}
        override fun getAcceptedIssuers(): Array<java.security.cert.X509Certificate> = arrayOf()
    }

    private val trustAllCerts: Array<TrustManager> = arrayOf(trustManager)

    private val sslContext: SSLContext = SSLContext.getInstance("TLS").apply {
        init(null, trustAllCerts, SecureRandom())
    }

    val sslSocketFactory: SSLSocketFactory = sslContext.socketFactory

    fun newClientBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .sslSocketFactory(sslSocketFactory, trustManager)
            .hostnameVerifier { _, _ -> true }

    /** Long reads (loopback proxy upstream). */
    val upstreamClient: OkHttpClient by lazy {
        newClientBuilder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    /** NAS cmd.cgi / downloads (matches prior NasLocalClient timeouts). */
    val nasCommandClient: OkHttpClient by lazy {
        newClientBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(60, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }

    val nasCommandClientRead5s: OkHttpClient by lazy {
        newClientBuilder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(5, TimeUnit.SECONDS)
            .writeTimeout(60, TimeUnit.SECONDS)
            .build()
    }
}
