package com.pocketos.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import java.net.Inet4Address

/** Returns an address only for the currently active Wi-Fi network. */
object WifiLanAddress {
    fun activeIpv4(context: Context): String? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val network = connectivity.activeNetwork ?: return null
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return null
        if (!capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) return null

        val properties = connectivity.getLinkProperties(network) ?: return null
        return properties.linkAddresses.asSequence()
            .map { it.address }
            .filterIsInstance<Inet4Address>()
            .firstOrNull { address ->
                !address.isAnyLocalAddress && !address.isLoopbackAddress && !address.isLinkLocalAddress
            }
            ?.hostAddress
            ?.takeIf(HostIpv4Address::isValid)
    }
}
