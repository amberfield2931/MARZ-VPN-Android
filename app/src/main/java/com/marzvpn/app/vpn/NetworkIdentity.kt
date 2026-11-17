package com.marzvpn.app.vpn

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

/** Which network the phone uses now (handle + type): latency results belong to one network only. */
object NetworkIdentity {
    fun key(context: Context): String {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return "none"
        val n = cm.activeNetwork ?: return "none"
        val caps = cm.getNetworkCapabilities(n)
        val type = when {
            caps == null -> "other"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
            else -> "other"
        }
        return "${n.networkHandle}:$type"
    }

    /** The phone's own network (not the VPN) is online, as Android itself validated it. */
    /** A mobile IMS network is validated even with mobile data off: it carries calls, not internet. */
    fun countsAsInternet(isVpn: Boolean, hasInternet: Boolean, validated: Boolean): Boolean = !isVpn && hasInternet && validated

    fun underlyingOnline(context: Context): Boolean {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return false
        @Suppress("DEPRECATION")
        return cm.allNetworks.any { n ->
            cm.getNetworkCapabilities(n)?.let {
                countsAsInternet(
                    isVpn = it.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
                    hasInternet = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
                    validated = it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
                )
            } == true
        }
    }
}
