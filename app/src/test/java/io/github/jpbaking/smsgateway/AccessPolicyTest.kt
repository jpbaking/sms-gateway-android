package io.github.jpbaking.smsgateway

import io.github.jpbaking.smsgateway.net.AccessPolicy
import io.github.jpbaking.smsgateway.net.AddressRanges
import io.github.jpbaking.smsgateway.net.ListenMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

class AccessPolicyTest {
    private fun ip(text: String): InetAddress = AddressRanges.parse(text) ?: error("not an address: $text")

    private val wifi = ip("192.168.1.20")
    private val mobile = ip("10.44.3.7")
    private val mobileV6 = ip("2001:db8:1::7")
    private val cellular = setOf(mobile, mobileV6)

    private fun policy(mode: ListenMode, tailscale: Boolean = false) =
        AccessPolicy(mode, tailscale, isCellular = { it in cellular })

    @Test
    fun localRangesAreRecognised() {
        listOf("127.0.0.1", "127.8.8.8", "::1", "10.0.0.1", "172.16.0.1", "172.31.255.254", "192.168.0.1", "169.254.1.1", "fd12:3456::1", "fc00::1", "fe80::1")
            .forEach { assertTrue(it, AddressRanges.isLocal(ip(it))) }
        listOf("8.8.8.8", "172.15.0.1", "172.32.0.1", "192.169.0.1", "100.64.0.1", "11.0.0.1", "2001:db8::1", "fec0::1", "2606:4700::1111")
            .forEach { assertFalse(it, AddressRanges.isLocal(ip(it))) }
    }

    @Test
    fun cgnatRangeIsExactlyOneHundredSixtyFourSlashTen() {
        listOf("100.64.0.1", "100.100.100.100", "100.127.255.254").forEach { assertTrue(it, AddressRanges.isCgnat(ip(it))) }
        listOf("100.63.255.255", "100.128.0.1", "10.0.0.1", "fd00::1").forEach { assertFalse(it, AddressRanges.isCgnat(ip(it))) }
    }

    @Test
    fun ipv4WrittenAsIpv6CountsAsTheIpv4Address() {
        // Built from bytes with a scope on purpose: every other factory quietly turns the mapped
        // form into an Inet4Address, and this is the only way to get the 16-byte form to test.
        val mapped = Inet6Address.getByAddress(null, ByteArray(10) + byteArrayOf(-1, -1, 8, 8, 8, 8), 0)
        assertTrue(mapped is Inet6Address)
        assertTrue(AddressRanges.unmap(mapped) is Inet4Address)
        assertEquals("8.8.8.8", AddressRanges.unmap(mapped).hostAddress)
        assertFalse(AddressRanges.isLocal(mapped))
        assertTrue(AddressRanges.isLocal(ip("::ffff:192.168.1.5")))
        assertTrue(AddressRanges.isLoopback(ip("::ffff:127.0.0.1")))
    }

    @Test
    fun parseTakesAddressesOnlyAndNeverLooksNamesUp() {
        assertEquals("192.168.1.5", ip("192.168.1.5").hostAddress)
        assertEquals(ip("fe80::1"), ip("fe80::1%wlan0"))
        assertEquals(ip("::1"), ip("[::1]"))
        listOf(null, "", " ", "example.com", "localhost", "1.2.3", "1.2.3.4.5", "999.1.1.1", "1.2.3.x", "12345")
            .forEach { assertNull(it, AddressRanges.parse(it)) }
    }

    @Test
    fun anywhereLetsEverythingIn() {
        val anywhere = policy(ListenMode.ANYWHERE)
        assertTrue(anywhere.allows(ip("8.8.8.8"), mobile))
        assertTrue(anywhere.allows(null, null))
    }

    @Test
    fun tunnelOnlyLetsOnlyThisPhoneIn() {
        val tunnelOnly = policy(ListenMode.TUNNEL_ONLY)
        assertTrue(tunnelOnly.allows(ip("127.0.0.1"), ip("127.0.0.1")))
        assertTrue(tunnelOnly.allows(ip("::1"), ip("::1")))
        assertFalse(tunnelOnly.allows(ip("192.168.1.5"), wifi))
        assertFalse(tunnelOnly.allows(null, wifi))
    }

    @Test
    fun lanLetsTheLocalNetworkIn() {
        val lan = policy(ListenMode.LAN)
        assertTrue(lan.allows(ip("192.168.1.5"), wifi))
        assertTrue(lan.allows(ip("10.0.0.9"), ip("10.0.0.2")))
        assertTrue(lan.allows(ip("fd00::5"), ip("fd00::2")))
        assertTrue(lan.allows(ip("127.0.0.1"), ip("127.0.0.1")))
        // When the phone's own address isn't known, the caller's address still decides.
        assertTrue(lan.allows(ip("192.168.1.5"), null))
    }

    @Test
    fun lanRefusesPublicAddresses() {
        val lan = policy(ListenMode.LAN)
        assertFalse(lan.allows(ip("8.8.8.8"), wifi))
        assertFalse(lan.allows(ip("2606:4700::1111"), ip("2001:db8:2::9")))
        assertFalse(lan.allows(null, wifi))
    }

    @Test
    fun lanRefusesAnythingThatArrivesOnMobileData() {
        val lan = policy(ListenMode.LAN, tailscale = true)
        // Even from addresses that look private: carriers hand those out too.
        assertFalse(lan.allows(ip("10.44.3.99"), mobile))
        assertFalse(lan.allows(ip("100.70.1.1"), mobile))
        assertFalse(lan.allows(ip("fd00::5"), mobileV6))
        // The same callers are fine when they arrive on Wi-Fi.
        assertTrue(lan.allows(ip("10.44.3.99"), wifi))
    }

    @Test
    fun tailscaleAddressesNeedTheirSwitch() {
        assertFalse(policy(ListenMode.LAN).allows(ip("100.100.1.2"), ip("100.100.1.1")))
        assertTrue(policy(ListenMode.LAN, tailscale = true).allows(ip("100.100.1.2"), ip("100.100.1.1")))
        assertFalse(policy(ListenMode.LAN, tailscale = true).allows(ip("8.8.8.8"), ip("100.100.1.1")))
    }
}
