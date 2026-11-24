package com.roomradar.android.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.wifi.WifiManager
import com.roomradar.core.model.NetworkSnapshot
import java.net.Inet4Address

class AndroidNetworkInfoProvider(
    private val context: Context,
) {
    private val connectivity =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifi =
        context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager

    @SuppressLint("MissingPermission")
    fun current(): NetworkSnapshot? {
        val network = connectivity.activeNetwork ?: return null
        val links: LinkProperties = connectivity.getLinkProperties(network) ?: return null
        val ipv4 = links.linkAddresses.firstOrNull { it.address is Inet4Address }
        val ownIp = ipv4?.address?.hostAddress
        val prefix = ipv4?.prefixLength?.toInt()
        val gateway = links.routes
            .firstOrNull { it.isDefaultRoute }
            ?.gateway
            ?.let { it as? Inet4Address }
            ?.hostAddress

        var ssid: String? = null
        var bssid: String? = null
        var identityVisible = false

        @Suppress("DEPRECATION")
        wifi?.connectionInfo?.let { info ->
            val rawSsid = info.ssid?.removeSurrounding("\"")
            if (!rawSsid.isNullOrBlank() &&
                rawSsid != "<unknown ssid>" &&
                !rawSsid.startsWith("0x")
            ) {
                ssid = rawSsid
                bssid = info.bssid
                identityVisible = true
            }
        }

        return NetworkSnapshot(
            ssid = ssid,
            bssid = bssid,
            ownIpv4 = ownIp,
            prefixLength = prefix,
            gatewayIpv4 = gateway,
            identityVisible = identityVisible,
        )
    }
}
