package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.tls.Der
import io.github.jpbaking.smsgateway.tls.TlsFrontend
import io.github.jpbaking.smsgateway.tls.TlsMaterial
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.URI
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.util.Base64
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManagerFactory
import kotlin.concurrent.thread

class TlsTest {

    @Test
    fun selfSignedCertificateHasNamesAndVerifies() {
        val material = TlsMaterial.selfSigned(listOf("localhost", "127.0.0.1", "192.168.1.20"))
        material.leaf.verify(material.leaf.publicKey)
        material.leaf.checkValidity()
        assertTrue(material.isSelfSigned)
        assertEquals(listOf("localhost", "127.0.0.1", "192.168.1.20"), material.names)
        assertEquals(listOf("1.3.6.1.5.5.7.3.1"), material.leaf.extendedKeyUsage)
        assertEquals(-1, material.leaf.basicConstraints)
        assertEquals(32 * 3 - 1, material.sha256Fingerprint.length)
    }

    @Test
    fun selfSignedRejectsBadNames() {
        assertThrows(IllegalArgumentException::class.java) { TlsMaterial.selfSigned(listOf("not a host")) }
        assertThrows(IllegalArgumentException::class.java) { TlsMaterial.selfSigned(emptyList()) }
    }

    @Test
    fun pemRoundTrip() {
        val material = TlsMaterial.selfSigned(listOf("localhost"))
        val parsed = TlsMaterial.fromPem(material.privateKeyPem() + "\n" + material.certificatePem())
        assertEquals(material.sha256Fingerprint, parsed.sha256Fingerprint)
        assertEquals(material.privateKey, parsed.privateKey)
    }

    @Test
    fun readsSec1EcKey() {
        val material = TlsMaterial.selfSigned(listOf("localhost"))
        // The OCTET STRING inside PKCS#8 is the SEC1 ECPrivateKey ("BEGIN EC PRIVATE KEY").
        val sec1 = Der.parse(material.privateKey.encoded).children()[2].content
        val parsed = TlsMaterial.fromPem(material.certificatePem() + pem("EC PRIVATE KEY", sec1))
        assertEquals(material.privateKey, parsed.privateKey)
    }

    @Test
    fun readsPkcs1RsaKeyAndRejectsMismatch() {
        val ec = TlsMaterial.selfSigned(listOf("localhost"))
        val rsa = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val pkcs1 = Der.parse(rsa.private.encoded).children()[2].content
        val error = assertThrows(IllegalArgumentException::class.java) {
            TlsMaterial.fromPem(ec.certificatePem() + pem("RSA PRIVATE KEY", pkcs1))
        }
        assertTrue(error.message!!.contains("doesn't match"))
    }

    @Test
    fun rejectsMismatchedEcKeyAndEncryptedKeys() {
        val a = TlsMaterial.selfSigned(listOf("localhost"))
        val b = TlsMaterial.selfSigned(listOf("localhost"))
        assertThrows(IllegalArgumentException::class.java) { TlsMaterial.fromPem(a.certificatePem() + b.privateKeyPem()) }
        val encrypted = a.certificatePem() + "-----BEGIN ENCRYPTED PRIVATE KEY-----\nAAAA\n-----END ENCRYPTED PRIVATE KEY-----\n"
        val error = assertThrows(IllegalArgumentException::class.java) { TlsMaterial.fromPem(encrypted) }
        assertTrue(error.message!!.contains("password"))
        assertThrows(IllegalArgumentException::class.java) { TlsMaterial.fromPem(a.certificatePem()) }
        assertThrows(IllegalArgumentException::class.java) { TlsMaterial.fromPem("hello") }
    }

    @Test
    fun pkcs12RoundTrip() {
        val material = TlsMaterial.selfSigned(listOf("localhost"))
        val bytes = ByteArrayOutputStream().also { out ->
            KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setKeyEntry("server", material.privateKey, "secret".toCharArray(), material.chain.toTypedArray())
            }.store(out, "secret".toCharArray())
        }.toByteArray()
        assertEquals(material.sha256Fingerprint, TlsMaterial.fromPkcs12(bytes, "secret").sha256Fingerprint)
        assertThrows(IllegalArgumentException::class.java) { TlsMaterial.fromPkcs12(bytes, "wrong") }
    }

    @Test
    fun frontendServesHttpsToBackend() {
        val material = TlsMaterial.selfSigned(listOf("127.0.0.1"))
        val backend = ServerSocket(0, 10, InetAddress.getByName("127.0.0.1"))
        thread(isDaemon = true) {
            while (!backend.isClosed) {
                val socket = runCatching { backend.accept() }.getOrNull() ?: break
                thread(isDaemon = true) {
                    socket.use {
                        val reader = it.getInputStream().bufferedReader()
                        val requestLine = reader.readLine()
                        while (reader.readLine()?.isNotEmpty() == true) Unit
                        val body = "you asked for $requestLine"
                        it.getOutputStream().write(
                            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body".toByteArray(),
                        )
                    }
                }
            }
        }
        val frontend = TlsFrontend(material.sslContext(), port = 0, backendPort = backend.localPort)
        frontend.start()
        try {
            val trust = KeyStore.getInstance("PKCS12").apply {
                load(null, null)
                setCertificateEntry("gateway", material.leaf)
            }
            val tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm()).apply { init(trust) }
            val client = SSLContext.getInstance("TLS").apply { init(null, tmf.trustManagers, null) }
            val connection = URI("https://127.0.0.1:${frontend.localPort}/health").toURL()
                .openConnection(java.net.Proxy.NO_PROXY) as HttpsURLConnection
            connection.sslSocketFactory = client.socketFactory
            assertEquals(200, connection.responseCode)
            assertEquals("you asked for GET /health HTTP/1.1", connection.inputStream.bufferedReader().readText())
        } finally {
            frontend.stop()
            backend.close()
        }
        assertNotEquals(0, material.leaf.serialNumber.signum())
    }

    private fun pem(type: String, der: ByteArray): String =
        "-----BEGIN $type-----\n" + Base64.getMimeEncoder().encodeToString(der) + "\n-----END $type-----\n"
}
