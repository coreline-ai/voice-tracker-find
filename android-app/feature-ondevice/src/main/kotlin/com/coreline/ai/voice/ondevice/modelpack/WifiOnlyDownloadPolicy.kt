package com.coreline.ai.voice.ondevice.modelpack

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Model artifacts are the only on-device feature traffic. They must never be
 * fetched over cellular, even if Android marks cellular as unmetered.
 */
internal object WifiOnlyDownloadPolicy {
    fun validatedWifi(context: Context): Network? {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return connectivity.allNetworks.firstOrNull { network ->
            isValidatedWifi(connectivity, network)
        }
    }

    fun isStillValidatedWifi(context: Context, network: Network): Boolean {
        val connectivity = context.getSystemService(ConnectivityManager::class.java)
        return isValidatedWifi(connectivity, network)
    }

    internal fun isValidatedWifi(
        connectivity: ConnectivityManager,
        network: Network,
    ): Boolean = connectivity.getNetworkCapabilities(network)?.let { capabilities ->
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    } == true
}
