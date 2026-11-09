package io.github.jpbaking.smsgateway.service

import java.net.Inet4Address
import java.net.NetworkInterface

/** The phone's non-loopback IPv4 addresses, e.g. its Wi-Fi LAN address. */
fun localIpv4Addresses(): List<String> = runCatching {
    NetworkInterface.getNetworkInterfaces().toList()
        .filter { it.isUp && !it.isLoopback }
        .flatMap { it.inetAddresses.toList() }
        .filterIsInstance<Inet4Address>()
        .mapNotNull { it.hostAddress }
}.getOrDefault(emptyList())
