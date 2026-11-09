package tech.capullo.tubecapullo.util

import java.net.Inet4Address
import java.net.NetworkInterface

object NetworkAddress {
    /**
     * This device's LAN IPv4 (the address a browser or snapclient on the same network reaches us
     * at), or null when there isn't one. Prefers wlan interfaces because that is where LAN
     * listeners live; a cellular address would be routable but useless for the LAN use case.
     */
    fun lanIpv4(): String? = runCatching {
        val candidates = NetworkInterface.getNetworkInterfaces().asSequence()
            .filter { it.isUp && !it.isLoopback }
            .flatMap { nif ->
                nif.inetAddresses.asSequence()
                    .filterIsInstance<Inet4Address>()
                    .filter { it.isSiteLocalAddress }
                    .map { nif.name to it.hostAddress }
            }
            .toList()
        (candidates.firstOrNull { it.first.startsWith("wlan") } ?: candidates.firstOrNull())?.second
    }.getOrNull()
}
