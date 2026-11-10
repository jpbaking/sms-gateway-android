package io.github.jpbaking.smsgateway.tunnel

import java.net.Inet6Address
import java.net.InetAddress

/**
 * Looks up Cloudflare's tunnel servers with Android's own DNS and passes them to cloudflared.
 *
 * cloudflared normally finds them with an SRV lookup, but Go always does SRV lookups itself by
 * reading /etc/resolv.conf, which doesn't exist on Android, so the lookup goes to [::1]:53 and
 * fails. Its fallback (DNS over TLS to 1.1.1.1:853) is often blocked by routers and firewalls.
 * These are the hostnames that SRV record points to, on cloudflared's standard port.
 */
object EdgeAddresses {
    private val REGIONS = listOf("region1.v2.argotunnel.com", "region2.v2.argotunnel.com")
    private const val PORT = 7844
    private const val PER_REGION = 8

    /** `--edge` values, or empty if DNS isn't working (cloudflared then tries its own lookup). */
    fun resolve(): List<String> {
        val perRegion = REGIONS.map { host ->
            runCatching { InetAddress.getAllByName(host).toList() }.getOrDefault(emptyList())
        }
        if (perRegion.any { it.isEmpty() }) return emptyList()
        return interleave(perRegion[0], perRegion[1])
    }

    /**
     * cloudflared puts every other `--edge` address in its second region, so alternate the two
     * regions to keep them apart. IPv4 first, since IPv6 often doesn't work on mobile data.
     */
    fun interleave(region1: List<InetAddress>, region2: List<InetAddress>): List<String> {
        fun prepare(list: List<InetAddress>) =
            list.sortedBy { it is Inet6Address }.distinct().take(PER_REGION).map(::format)
        val a = prepare(region1)
        val b = prepare(region2)
        return (0 until maxOf(a.size, b.size)).flatMap { i -> listOfNotNull(a.getOrNull(i), b.getOrNull(i)) }
    }

    private fun format(address: InetAddress): String {
        val host = address.hostAddress ?: ""
        return if (address is Inet6Address) "[${host.substringBefore('%')}]:$PORT" else "$host:$PORT"
    }
}
