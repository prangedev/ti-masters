package br.com.timasters.app.data.wifi

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import br.com.timasters.app.core.network.WifiMath
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

data class WifiConnection(
    /** Null when Android hides it (no location permission or location turned off). */
    val ssid: String?,
    val bssid: String?,
    val rssiDbm: Int,
    val linkSpeedMbps: Int?,
    val txLinkSpeedMbps: Int?,
    val rxLinkSpeedMbps: Int?,
    val frequencyMhz: Int,
    val standard: String?
)

data class WifiNetwork(
    /** Empty for hidden networks. */
    val ssid: String,
    val bssid: String,
    val rssiDbm: Int,
    val frequencyMhz: Int,
    /** Center of the whole channel (differs from [frequencyMhz] on 40/80/160 MHz channels). */
    val centerFrequencyMhz: Int,
    val channelWidthMhz: Int,
    val security: String,
    val standard: String?,
    val isConnected: Boolean
)

/** Which requirements for reading Wi-Fi names and nearby networks are missing. */
data class WifiAccess(
    val hasLocationPermission: Boolean,
    val isLocationEnabled: Boolean,
    val isWifiEnabled: Boolean
) {
    val canScan: Boolean get() = hasLocationPermission && isLocationEnabled && isWifiEnabled
}

@Singleton
class WifiRepository @Inject constructor(
    @param:ApplicationContext private val context: Context
) {
    private val wifiManager = context.applicationContext.getSystemService(WifiManager::class.java)
    private val connectivityManager = context.getSystemService(ConnectivityManager::class.java)
    private val locationManager = context.getSystemService(LocationManager::class.java)

    fun access(): WifiAccess = WifiAccess(
        hasLocationPermission = ContextCompat.checkSelfPermission(
            context, Manifest.permission.ACCESS_FINE_LOCATION
        ) == PackageManager.PERMISSION_GRANTED,
        isLocationEnabled = LocationManagerCompat.isLocationEnabled(locationManager),
        isWifiEnabled = wifiManager.isWifiEnabled
    )

    private fun isOnWifi(): Boolean {
        val network = connectivityManager.activeNetwork ?: return false
        return connectivityManager.getNetworkCapabilities(network)
            ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
    }

    /**
     * WifiManager.connectionInfo is deprecated in favour of NetworkCallback, but it is still the
     * only call that returns fresh RSSI on demand on every supported Android version.
     */
    @Suppress("DEPRECATION")
    fun currentConnection(): WifiConnection? {
        if (!isOnWifi()) return null
        val info = wifiManager.connectionInfo ?: return null
        val ssid = info.ssid?.removeSurrounding("\"")?.takeUnless { it.isBlank() || it == WifiManager.UNKNOWN_SSID }
        val bssid = info.bssid?.takeUnless { it == "02:00:00:00:00:00" }
        return WifiConnection(
            ssid = ssid,
            bssid = bssid,
            rssiDbm = info.rssi,
            linkSpeedMbps = info.linkSpeed.takeIf { it > 0 },
            txLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.txLinkSpeedMbps.takeIf { it > 0 } else null,
            rxLinkSpeedMbps = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) info.rxLinkSpeedMbps.takeIf { it > 0 } else null,
            frequencyMhz = info.frequency,
            standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WifiMath.standardName(info.wifiStandard) else null
        )
    }

    /** Signal of the connected access point, refreshed every second (not throttled by Android). */
    fun liveConnection(): Flow<WifiConnection?> = flow {
        while (true) {
            emit(currentConnection())
            delay(LIVE_INTERVAL_MS)
        }
    }.flowOn(Dispatchers.IO)

    /**
     * Nearby networks. Android allows only 4 scans every 2 minutes per app in the foreground,
     * so a new scan is requested every [SCAN_INTERVAL_MS]; results from other apps' scans also arrive.
     */
    @SuppressLint("MissingPermission") // Callers check access().canScan first.
    fun nearbyNetworks(): Flow<List<WifiNetwork>> = callbackFlow {
        fun publish() {
            trySend(readScanResults())
        }
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) = publish()
        }
        ContextCompat.registerReceiver(
            context,
            receiver,
            IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION),
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        publish()
        val scanner = launch {
            while (true) {
                @Suppress("DEPRECATION")
                wifiManager.startScan()
                delay(SCAN_INTERVAL_MS)
            }
        }
        awaitClose {
            scanner.cancel()
            context.unregisterReceiver(receiver)
        }
    }

    @SuppressLint("MissingPermission")
    private fun readScanResults(): List<WifiNetwork> {
        val results = try {
            wifiManager.scanResults
        } catch (_: SecurityException) {
            emptyList()
        }
        val connectedBssid = currentConnection()?.bssid
        return results
            .map { it.toWifiNetwork(connectedBssid) }
            .sortedByDescending { it.rssiDbm }
    }

    private fun ScanResult.toWifiNetwork(connectedBssid: String?): WifiNetwork {
        val name = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            wifiSsid?.toString()?.removeSurrounding("\"")
        } else {
            @Suppress("DEPRECATION")
            SSID
        }
        val width = WifiMath.channelWidthMhz(channelWidth)
        return WifiNetwork(
            ssid = name.orEmpty().takeUnless { it == WifiManager.UNKNOWN_SSID }.orEmpty(),
            bssid = BSSID.orEmpty(),
            rssiDbm = level,
            frequencyMhz = frequency,
            centerFrequencyMhz = if (width > 20 && centerFreq0 > 0) centerFreq0 else frequency,
            channelWidthMhz = width,
            security = WifiMath.securityOf(capabilities.orEmpty()),
            standard = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) WifiMath.standardName(wifiStandard) else null,
            isConnected = connectedBssid != null && connectedBssid.equals(BSSID, ignoreCase = true)
        )
    }

    private companion object {
        const val LIVE_INTERVAL_MS = 1_000L
        const val SCAN_INTERVAL_MS = 30_000L
    }
}
