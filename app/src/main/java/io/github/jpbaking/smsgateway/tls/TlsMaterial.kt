package io.github.jpbaking.smsgateway.tls

import java.io.ByteArrayInputStream
import java.math.BigInteger
import java.net.InetAddress
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.PublicKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.ECGenParameterSpec
import java.security.spec.PKCS8EncodedKeySpec
import java.text.SimpleDateFormat
import java.util.Base64
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/** A server certificate chain (leaf first) and its private key. */
class TlsMaterial(val chain: List<X509Certificate>, val privateKey: PrivateKey) {

    init {
        require(chain.isNotEmpty()) { "No certificate found" }
    }

    val leaf: X509Certificate get() = chain.first()

    /** SHA-256 of the leaf certificate, as colon-separated hex. Clients can pin this. */
    val sha256Fingerprint: String
        get() = MessageDigest.getInstance("SHA-256").digest(leaf.encoded)
            .joinToString(":") { "%02X".format(it) }

    /** The DNS names and IP addresses the leaf certificate is valid for. */
    val names: List<String>
        get() = runCatching { leaf.subjectAlternativeNames?.mapNotNull { it[1] as? String } }
            .getOrNull().orEmpty()

    val isSelfSigned: Boolean get() = chain.size == 1 && leaf.subjectX500Principal == leaf.issuerX500Principal

    fun certificatePem(): String = chain.joinToString("") { pem("CERTIFICATE", it.encoded) }

    /** The private key as unencrypted PKCS#8 PEM. Store it encrypted. */
    fun privateKeyPem(): String = pem("PRIVATE KEY", privateKey.encoded)

    fun sslContext(): SSLContext {
        val password = KEYSTORE_PASSWORD.toCharArray()
        val keyStore = KeyStore.getInstance("PKCS12").apply {
            load(null, null)
            setKeyEntry("gateway", privateKey, password, chain.toTypedArray())
        }
        val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        kmf.init(keyStore, password)
        return SSLContext.getInstance("TLS").apply { init(kmf.keyManagers, null, SecureRandom()) }
    }

    companion object {
        private const val KEYSTORE_PASSWORD = "gateway"
        private const val OID_RSA = "1.2.840.113549.1.1.1"
        private const val OID_EC = "1.2.840.10045.2.1"
        private const val OID_ECDSA_SHA256 = "1.2.840.10045.4.3.2"
        private const val OID_P256 = "1.2.840.10045.3.1.7"

        private val PEM_BLOCK = Regex(
            "-----BEGIN ([A-Z0-9 ]+)-----(.*?)-----END \\1-----",
            RegexOption.DOT_MATCHES_ALL,
        )

        /**
         * Reads a certificate (optionally followed by intermediates) and an unencrypted private key
         * from PEM text. Accepts `PRIVATE KEY` (PKCS#8), `RSA PRIVATE KEY` and `EC PRIVATE KEY`.
         * The certificates and the key can be in any order, in one blob or concatenated.
         */
        fun fromPem(text: String): TlsMaterial {
            val certs = mutableListOf<X509Certificate>()
            val keyBlocks = mutableListOf<Pair<String, ByteArray>>()
            val factory = CertificateFactory.getInstance("X.509")
            val blocks = PEM_BLOCK.findAll(text).toList()
            require(blocks.isNotEmpty()) { "No PEM blocks found. Paste text that starts with -----BEGIN." }
            for (block in blocks) {
                val type = block.groupValues[1]
                val body = block.groupValues[2]
                val headers = body.lines().filter { it.contains(':') }
                if (type == "ENCRYPTED PRIVATE KEY" || headers.any { it.contains("ENCRYPTED") }) {
                    throw IllegalArgumentException(
                        "The private key is password-protected. Remove the password " +
                            "(openssl pkey -in key.pem -out plain.pem) or upload a PKCS#12 file instead.",
                    )
                }
                // Skip RFC 1421 style headers ("Key: value" lines) before the base64.
                val base64 = (body.lines() - headers.toSet()).joinToString("").filterNot(Char::isWhitespace)
                val der = try {
                    Base64.getDecoder().decode(base64)
                } catch (e: IllegalArgumentException) {
                    throw IllegalArgumentException("A $type block isn't valid base64")
                }
                when (type) {
                    "CERTIFICATE" -> certs += try {
                        factory.generateCertificate(ByteArrayInputStream(der)) as X509Certificate
                    } catch (e: Exception) {
                        throw IllegalArgumentException("A certificate couldn't be read: ${e.message}")
                    }
                    "PRIVATE KEY", "RSA PRIVATE KEY", "EC PRIVATE KEY" -> keyBlocks += type to der
                    else -> Unit // e.g. EC PARAMETERS from openssl ecparam
                }
            }
            require(certs.isNotEmpty()) { "No certificate found (-----BEGIN CERTIFICATE-----)" }
            require(keyBlocks.isNotEmpty()) { "No private key found (-----BEGIN PRIVATE KEY-----)" }
            require(keyBlocks.size == 1) { "Found more than one private key; paste only one" }
            val (type, der) = keyBlocks.single()
            val key = when (type) {
                "RSA PRIVATE KEY" -> pkcs8Key(wrapPkcs1(der))
                "EC PRIVATE KEY" -> pkcs8Key(wrapSec1(der, certs))
                else -> pkcs8Key(der)
            }
            return withLeafFirst(certs, key)
        }

        /** Reads the first key entry of a PKCS#12 (.p12 / .pfx) file. */
        fun fromPkcs12(bytes: ByteArray, password: String): TlsMaterial {
            val keyStore = KeyStore.getInstance("PKCS12")
            try {
                keyStore.load(ByteArrayInputStream(bytes), password.toCharArray())
            } catch (e: Exception) {
                val reason = e.message?.takeIf { it.isNotBlank() } ?: e.javaClass.simpleName
                throw IllegalArgumentException(
                    "Couldn't open the PKCS#12 file (wrong password, or an unsupported format): $reason",
                )
            }
            val alias = keyStore.aliases().toList().firstOrNull { keyStore.isKeyEntry(it) }
                ?: throw IllegalArgumentException("The PKCS#12 file has no private key")
            val key = runCatching { keyStore.getKey(alias, password.toCharArray()) }.getOrNull() as? PrivateKey
                ?: runCatching { keyStore.getKey(alias, CharArray(0)) }.getOrNull() as? PrivateKey
                ?: throw IllegalArgumentException("Couldn't read the private key from the PKCS#12 file")
            val chain = keyStore.getCertificateChain(alias)?.filterIsInstance<X509Certificate>().orEmpty()
            require(chain.isNotEmpty()) { "The PKCS#12 file has no certificate for its key" }
            return withLeafFirst(chain, key)
        }

        /**
         * Makes a new ECDSA P-256 key and a self-signed certificate for it, valid for [names]
         * (host names and IP addresses). 825 days is the longest Apple platforms accept.
         */
        fun selfSigned(names: List<String>, validDays: Int = 825, now: Date = Date()): TlsMaterial {
            val cleaned = names.map { it.trim() }.filter { it.isNotEmpty() }.distinct()
            require(cleaned.isNotEmpty()) { "Give at least one host name or IP address" }
            val sanEntries = cleaned.map { name ->
                if (isIpLiteral(name)) {
                    Der.implicit(7, InetAddress.getByName(name).address)
                } else {
                    require(HOST_NAME.matches(name)) { "'$name' isn't a valid host name or IP address" }
                    Der.implicit(2, name.lowercase().toByteArray(Charsets.US_ASCII))
                }
            }

            val keyPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec("secp256r1"), SecureRandom())
            }.generateKeyPair()

            val serial = ByteArray(16).also { SecureRandom().nextBytes(it) }
            val name = Der.sequence(
                Der.set(Der.sequence(Der.oid("2.5.4.3"), Der.utf8("SMS Gateway (${cleaned.first()})"))),
            )
            val notBefore = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
                time = now
                add(Calendar.DAY_OF_YEAR, -1) // tolerate clients whose clocks are a little behind
            }.time
            val notAfter = Date(now.time + validDays * 86_400_000L)
            val signatureAlgorithm = Der.sequence(Der.oid(OID_ECDSA_SHA256))
            val extensions = Der.sequence(
                // basicConstraints: not a CA
                Der.sequence(Der.oid("2.5.29.19"), Der.boolean(true), Der.octetString(Der.sequence())),
                // keyUsage: digitalSignature
                Der.sequence(
                    Der.oid("2.5.29.15"),
                    Der.boolean(true),
                    Der.octetString(Der.tlv(Der.BIT_STRING, byteArrayOf(0x07, 0x80.toByte()))),
                ),
                // extKeyUsage: serverAuth
                Der.sequence(Der.oid("2.5.29.37"), Der.octetString(Der.sequence(Der.oid("1.3.6.1.5.5.7.3.1")))),
                // subjectAltName
                Der.sequence(Der.oid("2.5.29.17"), Der.octetString(Der.sequence(sanEntries))),
                // subjectKeyIdentifier
                Der.sequence(Der.oid("2.5.29.14"), Der.octetString(Der.octetString(keyIdentifier(keyPair.public)))),
            )
            val tbs = Der.sequence(
                Der.explicit(0, Der.integer(2)),
                Der.integer(BigInteger(1, serial).max(BigInteger.ONE)),
                signatureAlgorithm,
                name,
                Der.sequence(time(notBefore), time(notAfter)),
                name,
                keyPair.public.encoded,
                Der.explicit(3, extensions),
            )
            val signature = Signature.getInstance("SHA256withECDSA").run {
                initSign(keyPair.private)
                update(tbs)
                sign()
            }
            val der = Der.sequence(tbs, signatureAlgorithm, Der.bitString(signature))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(der)) as X509Certificate
            return TlsMaterial(listOf(cert), keyPair.private)
        }

        private val HOST_NAME = Regex("^(\\*\\.)?[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?(\\.[A-Za-z0-9]([A-Za-z0-9-]*[A-Za-z0-9])?)*$")
        private val IPV4 = Regex("^\\d{1,3}(\\.\\d{1,3}){3}$")

        /** True for IPv4/IPv6 literals. Never does a DNS lookup. */
        internal fun isIpLiteral(value: String): Boolean =
            (IPV4.matches(value) && value.split('.').all { it.toInt() <= 255 }) ||
                (value.contains(':') && value.all { it.isLetterOrDigit() || it == ':' || it == '.' })

        private fun time(date: Date): ByteArray {
            val utc = Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { time = date }
            // RFC 5280: UTCTime through 2049, GeneralizedTime from 2050.
            val (pattern, tag) = if (utc.get(Calendar.YEAR) < 2050) {
                "yyMMddHHmmss'Z'" to Der.UTC_TIME
            } else {
                "yyyyMMddHHmmss'Z'" to Der.GENERALIZED_TIME
            }
            val format = SimpleDateFormat(pattern, Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            return Der.tlv(tag, format.format(date).toByteArray(Charsets.US_ASCII))
        }

        /** RFC 5280 method 1: SHA-1 of the subjectPublicKey bits. */
        private fun keyIdentifier(publicKey: PublicKey): ByteArray {
            val bits = Der.parse(publicKey.encoded).children()[1].content.drop(1).toByteArray()
            return MessageDigest.getInstance("SHA-1").digest(bits)
        }

        private fun pkcs8Key(der: ByteArray): PrivateKey {
            val algorithmOid = runCatching {
                val algorithm = Der.parse(der).children()[1].children()[0]
                decodeOid(algorithm.content)
            }.getOrNull()
            val algorithm = when (algorithmOid) {
                OID_RSA -> "RSA"
                OID_EC -> "EC"
                null -> throw IllegalArgumentException("The private key couldn't be read")
                else -> throw IllegalArgumentException("Only RSA and ECDSA keys are supported")
            }
            return try {
                KeyFactory.getInstance(algorithm).generatePrivate(PKCS8EncodedKeySpec(der))
            } catch (e: Exception) {
                throw IllegalArgumentException("The private key couldn't be read: ${e.message}")
            }
        }

        /** PKCS#1 RSAPrivateKey -> PKCS#8 PrivateKeyInfo. */
        private fun wrapPkcs1(der: ByteArray): ByteArray = Der.sequence(
            Der.integer(0),
            Der.sequence(Der.oid(OID_RSA), Der.nullValue()),
            Der.octetString(der),
        )

        /** SEC1 ECPrivateKey -> PKCS#8 PrivateKeyInfo. The curve comes from the key, or else the certificate. */
        private fun wrapSec1(der: ByteArray, certs: List<X509Certificate>): ByteArray {
            val curve = runCatching {
                Der.parse(der).children().first { it.tag == 0xa0 }.children().first().encoded
            }.getOrNull() ?: certs.firstNotNullOfOrNull { cert ->
                runCatching {
                    val algorithm = Der.parse(cert.publicKey.encoded).children()[0].children()
                    algorithm[1].encoded.takeIf { decodeOid(algorithm[0].content) == OID_EC }
                }.getOrNull()
            } ?: Der.oid(OID_P256)
            return Der.sequence(
                Der.integer(0),
                Der.sequence(Der.oid(OID_EC), curve),
                Der.octetString(der),
            )
        }

        private fun decodeOid(content: ByteArray): String {
            val parts = mutableListOf<Long>()
            var value = 0L
            for (b in content) {
                value = (value shl 7) or (b.toLong() and 0x7f)
                if (b.toInt() and 0x80 == 0) {
                    if (parts.isEmpty()) {
                        val first = if (value < 80) value / 40 else 2
                        parts += first
                        parts += value - first * 40
                    } else {
                        parts += value
                    }
                    value = 0
                }
            }
            return parts.joinToString(".")
        }

        /** Puts the certificate that belongs to [key] first, so the server presents the right one. */
        private fun withLeafFirst(certs: List<X509Certificate>, key: PrivateKey): TlsMaterial {
            val leaf = certs.firstOrNull { keyMatches(key, it.publicKey) }
                ?: throw IllegalArgumentException("The private key doesn't match the certificate")
            return TlsMaterial(listOf(leaf) + certs.filter { it !== leaf }, key)
        }

        private fun keyMatches(key: PrivateKey, publicKey: PublicKey): Boolean {
            if (!key.algorithm.equals(publicKey.algorithm, ignoreCase = true)) return false
            val algorithm = when (key.algorithm.uppercase()) {
                "RSA" -> "SHA256withRSA"
                "EC" -> "SHA256withECDSA"
                else -> return false
            }
            return runCatching {
                val probe = ByteArray(32).also { SecureRandom().nextBytes(it) }
                val sig = Signature.getInstance(algorithm).run {
                    initSign(key)
                    update(probe)
                    sign()
                }
                Signature.getInstance(algorithm).run {
                    initVerify(publicKey)
                    update(probe)
                    verify(sig)
                }
            }.getOrDefault(false)
        }

        private fun pem(type: String, der: ByteArray): String {
            val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(der)
            return "-----BEGIN $type-----\n$body\n-----END $type-----\n"
        }
    }
}
