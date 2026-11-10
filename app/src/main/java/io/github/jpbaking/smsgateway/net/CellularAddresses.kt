package io.github.jpbaking.smsgateway.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap

/**
 * Knows which of the phone's own IP addresses are on mobile data, so the server can refuse
 * connections that arrive there.
 *
 * It asks Android which networks are cellular instead of guessing from interface names
 * (`rmnet0`, `ccmni0`, ...), which differ between phone makers.
 */
class CellularAddresses(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val byNetwork = ConcurrentHashMap<Network, Set<InetAddress>>()

    @Volatile
    private var registered = false

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
            byNetwork[network] = addressesOf(linkProperties)
        }

        override fun onLost(network: Network) {
            byNetwork.remove(network)
        }
    }

    /** Starts following the phone's mobile data networks. Safe to call more than once. */
    @Synchronized
    fun start() {
        if (registered) return
        // What's connected right now, so there is no gap before the first callback arrives.
        runCatching {
            @Suppress("DEPRECATION")
            for (network in connectivity.allNetworks) {
                val cellular = connectivity.getNetworkCapabilities(network)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
                if (cellular) connectivity.getLinkProperties(network)?.let { byNetwork[network] = addressesOf(it) }
            }
        }
        val request = NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR).build()
        runCatching {
            connectivity.registerNetworkCallback(request, callback)
            registered = true
        }
    }

    @Synchronized
    fun stop() {
        if (!registered) return
        runCatching { connectivity.unregisterNetworkCallback(callback) }
        registered = false
        byNetwork.clear()
    }

    /** True if [address] is one of this phone's addresses on a mobile data network. */
    fun contains(address: InetAddress): Boolean {
        val plain = normalize(address)
        return byNetwork.values.any { plain in it }
    }

    private fun addressesOf(linkProperties: LinkProperties): Set<InetAddress> =
        linkProperties.linkAddresses.map { normalize(it.address) }.toSet()

    /** Drops any IPv6 zone, so the same address always compares equal. */
    private fun normalize(address: InetAddress): InetAddress =
        runCatching { InetAddress.getByAddress(address.address) }.getOrDefault(address)
}
