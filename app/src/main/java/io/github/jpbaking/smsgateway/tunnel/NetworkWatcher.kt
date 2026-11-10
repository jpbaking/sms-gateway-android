package io.github.jpbaking.smsgateway.tunnel

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Tracks the phone's default network (Wi-Fi, mobile data, ...). The value changes when the
 * network drops (null), comes back, or the phone switches from one network to another.
 */
class NetworkWatcher(context: Context) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val _network = MutableStateFlow<Network?>(connectivity.activeNetwork)
    val network: StateFlow<Network?> = _network.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            _network.value = network
        }

        override fun onLost(network: Network) {
            if (_network.value == network) _network.value = null
        }
    }

    fun start() = connectivity.registerDefaultNetworkCallback(callback)

    fun stop() {
        runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
}
