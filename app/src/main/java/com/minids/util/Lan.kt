package com.minids.util

import java.net.Inet4Address
import java.net.NetworkInterface

object Lan {

    /** 取本机局域网 IPv4，优先私有网段。 */
    fun ipv4(): String? = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.toList()
            ?.filter { it.isUp && !it.isLoopback }
            ?.flatMap { it.inetAddresses.toList() }
            ?.filterIsInstance<Inet4Address>()
            ?.firstOrNull { it.isSiteLocalAddress }
            ?.hostAddress
    }.getOrNull()
}
