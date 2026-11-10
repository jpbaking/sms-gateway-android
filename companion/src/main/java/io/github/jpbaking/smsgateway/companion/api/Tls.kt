package io.github.jpbaking.smsgateway.companion.api

import android.annotation.SuppressLint
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.cert.CertificateException
import java.security.cert.X509Certificate
import javax.net.ssl.HostnameVerifier
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSession
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager
import okhttp3.OkHttpClient

/**
 * HTTPS trust for the gateway. Certificates the system trusts (a tunnel's, Let's Encrypt) always
 * work. A self-signed gateway certificate works once its SHA-256 fingerprint is pinned in
 * Settings: then exactly that certificate is accepted, whatever host name or IP it's reached by,
 * and nothing else that the system doesn't trust.
 */
object Tls {
    /** The same format the gateway shows under Settings → Server → Certificate. */
    fun fingerprint(cert: X509Certificate): String = format(MessageDigest.getInstance("SHA-256").digest(cert.encoded))

    fun format(bytes: ByteArray): String = bytes.joinToString(":") { "%02X".format(it) }

    /** Accepts a fingerprint with or without colons or spaces, any case. Null if it isn't SHA-256. */
    fun normalizeFingerprint(input: String): String? {
        val hex = input.filter { !it.isWhitespace() && it != ':' }.uppercase()
        if (hex.length != 64 || hex.any { it !in '0'..'9' && it !in 'A'..'F' }) return null
        return hex.chunked(2).joinToString(":")
    }

    private val systemTrust: X509TrustManager by lazy {
        val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        tmf.init(null as KeyStore?)
        tmf.trustManagers.filterIsInstance<X509TrustManager>().first()
    }

    fun apply(builder: OkHttpClient.Builder, pinnedSha256: String?): OkHttpClient.Builder {
        if (pinnedSha256 == null) return builder
        val trust = PinnedTrustManager(systemTrust, pinnedSha256)
        val context = SSLContext.getInstance("TLS").apply { init(null, arrayOf(trust), SecureRandom()) }
        val defaultVerifier = HttpsURLConnection.getDefaultHostnameVerifier()
        val verifier = HostnameVerifier { host, session ->
            leafMatches(session, pinnedSha256) || defaultVerifier.verify(host, session)
        }
        return builder.sslSocketFactory(context.socketFactory, trust).hostnameVerifier(verifier)
    }

    private fun leafMatches(session: SSLSession, pin: String): Boolean =
        runCatching { (session.peerCertificates.firstOrNull() as? X509Certificate)?.let { fingerprint(it) == pin } }
            .getOrNull() == true

    private class PinnedTrustManager(
        private val system: X509TrustManager,
        private val pin: String,
    ) : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) =
            system.checkClientTrusted(chain, authType)

        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
            val leaf = chain?.firstOrNull() ?: throw CertificateException("No server certificate")
            if (fingerprint(leaf) == pin) return
            system.checkServerTrusted(chain, authType)
        }

        override fun getAcceptedIssuers(): Array<X509Certificate> = system.acceptedIssuers
    }

    /**
     * Accepts anything. Used only to *read* the certificate a gateway presents, so its fingerprint
     * can be shown for the user to compare and pin. Never used for API calls.
     */
    @SuppressLint("CustomX509TrustManager", "TrustAllX509TrustManager")
    internal object InspectOnly : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }
}
