package io.github.jpbaking.smsgateway.webhook

import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Signs webhook bodies so receivers can check they came from this phone.
 *
 * Header: `X-Gateway-Signature: sha256=<hex HMAC-SHA256 of "<timestamp>.<body>">`, with the
 * timestamp sent in `X-Gateway-Timestamp`. Including the timestamp lets receivers reject replays.
 */
object WebhookSigner {
    fun sign(secret: String, timestamp: Long, body: String): String {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        val digest = mac.doFinal("$timestamp.$body".toByteArray(Charsets.UTF_8))
        return "sha256=" + digest.joinToString("") { "%02x".format(it) }
    }
}
