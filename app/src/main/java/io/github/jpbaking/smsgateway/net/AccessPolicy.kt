package io.github.jpbaking.smsgateway.net

import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress

/** Who may connect to the server. */
enum class ListenMode(val label: String, val detail: String) {
    /** Listens on this phone only, so only the Cloudflare Tunnel (and apps on the phone) connect. */
    TUNNEL_ONLY("Tunnel only", "Only the Cloudflare Tunnel reaches the server. Nothing on Wi-Fi can."),

    /** Devices on the local network, never over mobile data. */
    LAN("Local network", "Devices on the same Wi-Fi, Ethernet or VPN. Never over mobile data."),

    /** No checks at all: whatever can route to the phone. */
    ANYWHERE("Anywhere", "No checks. If the phone has a public address, that means the internet."),
}

/** Tells kinds of IP address apart. Pure functions, no lookups. */
object AddressRanges {

    /** `::ffff:a.b.c.d` (an IPv4 address written as IPv6) as the IPv4 address it stands for. */
    fun unmap(address: InetAddress): InetAddress {
        if (address !is Inet6Address) return address
        val b = address.address
        val mapped = (0 until 10).all { b[it] == 0.toByte() } && b[10] == 0xff.toByte() && b[11] == 0xff.toByte()
        return if (mapped) InetAddress.getByAddress(b.copyOfRange(12, 16)) else address
    }

    fun isLoopback(address: InetAddress): Boolean = unmap(address).isLoopbackAddress

    /**
     * Loopback, RFC 1918 private IPv4 (10/8, 172.16/12, 192.168/16), IPv4 link-local (169.254/16),
     * IPv6 unique local (fc00::/7) and IPv6 link-local (fe80::/10).
     */
    fun isLocal(address: InetAddress): Boolean {
        val a = unmap(address)
        if (a.isLoopbackAddress) return true
        val b = a.address
        return when (a) {
            is Inet4Address -> {
                val b0 = b[0].toInt() and 0xff
                val b1 = b[1].toInt() and 0xff
                b0 == 10 || (b0 == 172 && b1 in 16..31) || (b0 == 192 && b1 == 168) || (b0 == 169 && b1 == 254)
            }
            is Inet6Address -> {
                val b0 = b[0].toInt() and 0xff
                val b1 = b[1].toInt() and 0xff
                (b0 and 0xfe) == 0xfc || (b0 == 0xfe && (b1 and 0xc0) == 0x80)
            }
            else -> false
        }
    }

    /** 100.64.0.0/10: what Tailscale gives its devices, and also what carriers use behind their NAT. */
    fun isCgnat(address: InetAddress): Boolean {
        val a = unmap(address)
        if (a !is Inet4Address) return false
        val b = a.address
        return (b[0].toInt() and 0xff) == 100 && (b[1].toInt() and 0xc0) == 0x40
    }

    /**
     * Parses an IP address written as text (a trailing `%zone` is ignored). Returns null for
     * anything else, and never does a DNS lookup.
     */
    fun parse(text: String?): InetAddress? {
        val literal = text?.trim()?.removePrefix("[")?.substringBefore('%')?.removeSuffix("]").orEmpty()
        if (literal.isEmpty()) return null
        val v4 = literal.split('.').let { parts ->
            parts.size == 4 && parts.all { p -> p.length in 1..3 && p.all { it in '0'..'9' } && p.toInt() <= 255 }
        }
        val v6 = literal.contains(':') &&
            literal.all { it in '0'..'9' || it in 'a'..'f' || it in 'A'..'F' || it == ':' || it == '.' }
        if (!v4 && !v6) return null
        return runCatching { InetAddress.getByName(literal) }.getOrNull()
    }
}

/**
 * Decides whether a connection is let in, from the address it came from ([allows]' `remote`) and
 * the phone's own address it arrived at (`local`). Checked before TLS and before the API key.
 *
 * @param isCellular whether one of the phone's own addresses belongs to a mobile data network.
 */
class AccessPolicy(
    val mode: ListenMode,
    private val allowTailscale: Boolean = false,
    private val isCellular: (InetAddress) -> Boolean = { false },
) {
    fun allows(remote: InetAddress?, local: InetAddress?): Boolean {
        if (mode == ListenMode.ANYWHERE) return true
        // An address that can't be read can't be trusted.
        remote ?: return false
        // This phone itself: the Cloudflare Tunnel, and the HTTPS listener passing requests on.
        if (AddressRanges.isLoopback(remote)) return true
        if (mode == ListenMode.TUNNEL_ONLY) return false

        // Carriers hand out private-looking addresses too, so anything arriving on mobile data is
        // refused whatever it claims to come from.
        if (local != null && isCellular(AddressRanges.unmap(local))) return false
        return AddressRanges.isLocal(remote) || (allowTailscale && AddressRanges.isCgnat(remote))
    }

    companion object {
        /** Lets everything in. For tests and callers that don't filter. */
        val OPEN = AccessPolicy(ListenMode.ANYWHERE)
    }
}
