package com.dinoe.clockwise.bridge

import java.net.NetworkInterface

/** Best-effort local IPv4 addresses for the Settings connection hint. No extra permission needed. */
object BridgeNetwork {

    fun localIpv4Addresses(): List<String> = runCatching {
        NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { networkInterface -> networkInterface.isUp && !networkInterface.isLoopback }
            .flatMap { networkInterface -> networkInterface.inetAddresses.asSequence() }
            .filter { address -> address.isSiteLocalAddress }
            .mapNotNull { address -> address.hostAddress }
            .filter { address -> !address.contains(':') }
            .toList()
    }.getOrDefault(emptyList())
}
