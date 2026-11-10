package io.github.jpbaking.smsgateway.companion

import io.github.jpbaking.smsgateway.companion.api.GatewayUrl
import io.github.jpbaking.smsgateway.companion.api.Tls
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class GatewayUrlTest {
    @Test
    fun `cleans up typed URLs`() {
        assertEquals("https://sms.example.com", GatewayUrl.normalize(" https://sms.example.com/ "))
        assertEquals("https://sms.example.com", GatewayUrl.normalize("https://sms.example.com/api/v1/"))
        assertEquals("https://abc.trycloudflare.com", GatewayUrl.normalize("abc.trycloudflare.com"))
        assertEquals("http://192.168.1.50:8080", GatewayUrl.normalize("192.168.1.50:8080"))
        assertEquals("https://192.168.1.50:8443", GatewayUrl.normalize("https://192.168.1.50:8443"))
        assertEquals("http://10.0.0.2:8080", GatewayUrl.normalize("10.0.0.2:8080"))
    }

    @Test
    fun `rejects what can't be a gateway URL`() {
        assertNull(GatewayUrl.normalize(""))
        assertNull(GatewayUrl.normalize("ftp://example.com"))
        assertNull(GatewayUrl.normalize("https://"))
    }

    @Test
    fun `fingerprints are accepted in any common form`() {
        val hex = "ab".repeat(32)
        val expected = List(32) { "AB" }.joinToString(":")
        assertEquals(expected, Tls.normalizeFingerprint(hex))
        assertEquals(expected, Tls.normalizeFingerprint(expected.lowercase()))
        assertEquals(expected, Tls.normalizeFingerprint(expected.replace(":", " ")))
        assertNull(Tls.normalizeFingerprint("AB:CD"))
        assertNull(Tls.normalizeFingerprint("zz".repeat(32)))
    }

    @Test
    fun `fingerprint format matches the gateway's`() {
        assertEquals("00:0F:A0", Tls.format(byteArrayOf(0, 15, -96)))
    }
}
