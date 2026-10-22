package br.com.timasters.app.data.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import androidx.annotation.StringRes
import br.com.timasters.app.R
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.conflate
import java.net.Inet4Address
import java.net.Inet6Address
import javax.inject.Inject
import javax.inject.Singleton

enum class TransportType(@param:StringRes val labelResId: Int) {
    WIFI(R.string.transport_wifi),
    CELLULAR(R.string.transport_cellular),
    ETHERNET(R.string.transport_ethernet),
    VPN(R.string.transport_vpn),
    OTHER(R.string.transport_other),
    NONE(R.string.transport_none)
}

data class ConnectionDetails(
    val transport: TransportType,
    /** Android confirmed this network reaches the internet. */
    val hasInternet: Boolean = false,
    val isMetered: Boolean = false,
    val interfaceName: String? = null,
    val ipv4: List<String> = emptyList(),
    val ipv4PrefixLength: Int? = null,
    val ipv6: List<String> = emptyList(),
    val gateway: String? = null,
    val dnsServers: List<String> = emptyList(),
    val privateDns: String? = null,
    val downstreamKbps: Int? = null,
    val upstreamKbps: Int? = null
)

@Singleton
class ConnectionInfoRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)

    fun current(): ConnectionDetails {
        val network = connectivityManager.activeNetwork ?: return ConnectionDetails(TransportType.NONE)
        return details(
            connectivityManager.getNetworkCapabilities(network),
            connectivityManager.getLinkProperties(network)
        )
    }

    /** Emits again whenever the active network or its addresses change. */
    fun observe(): Flow<ConnectionDetails> = callbackFlow {
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                trySend(current())
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                trySend(current())
            }

            override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                trySend(current())
            }

            override fun onLost(network: Network) {
                trySend(current())
            }
        }
        trySend(current())
        connectivityManager.registerDefaultNetworkCallback(callback)
        awaitClose { connectivityManager.unregisterNetworkCallback(callback) }
    }.conflate()

    private fun details(capabilities: NetworkCapabilities?, link: LinkProperties?): ConnectionDetails {
        val transport = when {
            capabilities == null -> TransportType.NONE
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> TransportType.VPN
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> TransportType.WIFI
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> TransportType.ETHERNET
            capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> TransportType.CELLULAR
            else -> TransportType.OTHER
        }
        val ipv4Addresses = link?.linkAddresses.orEmpty().filter { it.address is Inet4Address }
        return ConnectionDetails(
            transport = transport,
            hasInternet = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true,
            isMetered = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED) == false,
            interfaceName = link?.interfaceName,
            ipv4 = ipv4Addresses.mapNotNull { it.address.hostAddress },
            ipv4PrefixLength = ipv4Addresses.firstOrNull()?.prefixLength,
            ipv6 = link?.linkAddresses.orEmpty()
                .filter { it.address is Inet6Address }
                .mapNotNull { it.address.hostAddress?.substringBefore('%') },
            gateway = link?.routes.orEmpty()
                .firstOrNull { it.isDefaultRoute && it.gateway is Inet4Address }
                ?.gateway?.hostAddress,
            dnsServers = link?.dnsServers.orEmpty().mapNotNull { it.hostAddress?.substringBefore('%') },
            privateDns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) link?.privateDnsServerName else null,
            downstreamKbps = capabilities?.linkDownstreamBandwidthKbps?.takeIf { it > 0 },
            upstreamKbps = capabilities?.linkUpstreamBandwidthKbps?.takeIf { it > 0 }
        )
    }
}
