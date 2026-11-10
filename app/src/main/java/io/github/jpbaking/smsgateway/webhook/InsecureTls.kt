package io.github.jpbaking.smsgateway.webhook

import android.annotation.SuppressLint
import java.security.SecureRandom
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.X509TrustManager

/**
 * Accepts any server certificate and host name. Used only for webhooks registered with
 * `"insecure": true`, so deliveries to self-signed dev/test servers work. The connection is still
 * encrypted, but anyone on the network path could impersonate the server.
 */
internal object InsecureTls {
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    private val trustAll = object : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    val socketFactory: SSLSocketFactory by lazy {
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustAll), SecureRandom()) }.socketFactory
    }

    @SuppressLint("BadHostnameVerifier")
    val hostnameVerifier = HostnameVerifier { _, _ -> true }

    fun apply(conn: HttpsURLConnection) {
        conn.sslSocketFactory = socketFactory
        conn.hostnameVerifier = hostnameVerifier
    }
}
