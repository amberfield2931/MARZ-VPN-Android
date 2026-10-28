package io.nekohasekai.sfa.bg

import android.net.Network
import android.os.Build
import io.nekohasekai.libbox.InterfaceUpdateListener
import io.nekohasekai.sfa.Application
import java.net.NetworkInterface

object DefaultNetworkMonitor {

    var defaultNetwork: Network? = null
    private var listener: InterfaceUpdateListener? = null

    suspend fun start() {
        DefaultNetworkListener.start(this) {
            defaultNetwork = it
            checkDefaultInterfaceUpdate(it)
        }
        defaultNetwork = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Application.connectivity.activeNetwork
        } else {
            DefaultNetworkListener.get()
        }
    }

    suspend fun stop() {
        DefaultNetworkListener.stop(this)
    }

    suspend fun require(): Network {
        val network = defaultNetwork
        if (network != null) {
            return network
        }
        return DefaultNetworkListener.get()
    }

    fun setListener(listener: InterfaceUpdateListener?) {
        this.listener = listener
        checkDefaultInterfaceUpdate(defaultNetwork)
    }

    private var lastLogged: String? = null

    /** Journal: the phone's own default network as the engine sees it (only when it changes; no addresses). */
    private fun journal(network: Network?) {
        val text = if (network == null) "none" else runCatching {
            val lp = Application.connectivity.getLinkProperties(network)
            val caps = Application.connectivity.getNetworkCapabilities(network)
            val transport = when {
                caps == null -> "?"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
                else -> "other"
            }
            val v4 = lp?.linkAddresses?.count { it.address is java.net.Inet4Address } ?: 0
            val v6 = lp?.linkAddresses?.count { it.address is java.net.Inet6Address } ?: 0
            val validated = caps?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
            "id=$network $transport iface=${lp?.interfaceName} addrs=v4:$v4,v6:$v6 dns=${lp?.dnsServers?.size ?: 0} validated=$validated"
        }.getOrDefault("?")
        if (text != lastLogged) {
            lastLogged = text
            com.marzvpn.app.diag.DiagJournal.log("net", "default network", text)
        }
    }

    private fun checkDefaultInterfaceUpdate(newNetwork: Network?) {
        journal(newNetwork)
        val listener = listener ?: return
        if (newNetwork != null) {
            for (times in 0 until 10) {
                val linkProperties = Application.connectivity.getLinkProperties(newNetwork)
                if (linkProperties == null) {
                    Thread.sleep(100)
                    continue
                }
                var interfaceIndex: Int
                try {
                    interfaceIndex = NetworkInterface.getByName(linkProperties.interfaceName).index
                } catch (e: Exception) {
                    Thread.sleep(100)
                    continue
                }
                listener.updateDefaultInterface(linkProperties.interfaceName, interfaceIndex, false, false)
                return
            }
        } else {
            listener.updateDefaultInterface("", -1, false, false)
        }
    }
}
