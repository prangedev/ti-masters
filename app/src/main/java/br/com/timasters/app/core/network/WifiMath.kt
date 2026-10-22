package br.com.timasters.app.core.network

import androidx.annotation.StringRes
import br.com.timasters.app.R

enum class WifiBand(@param:StringRes val labelResId: Int) {
    GHZ_2_4(R.string.wifi_band_2_4),
    GHZ_5(R.string.wifi_band_5),
    GHZ_6(R.string.wifi_band_6),
    UNKNOWN(R.string.wifi_band_unknown)
}

enum class SignalQuality(@param:StringRes val labelResId: Int) {
    EXCELLENT(R.string.signal_excellent),
    GOOD(R.string.signal_good),
    FAIR(R.string.signal_fair),
    WEAK(R.string.signal_weak),
    VERY_WEAK(R.string.signal_very_weak)
}

object WifiMath {

    fun bandOf(frequencyMhz: Int): WifiBand = when (frequencyMhz) {
        in 2400..2500 -> WifiBand.GHZ_2_4
        in 4900..5900 -> WifiBand.GHZ_5
        in 5925..7125 -> WifiBand.GHZ_6
        else -> WifiBand.UNKNOWN
    }

    /** IEEE channel number for a center frequency, or null when unknown. */
    fun channelOf(frequencyMhz: Int): Int? = when (bandOf(frequencyMhz)) {
        WifiBand.GHZ_2_4 -> if (frequencyMhz == 2484) 14 else (frequencyMhz - 2407) / 5
        WifiBand.GHZ_5 -> (frequencyMhz - 5000) / 5
        WifiBand.GHZ_6 -> (frequencyMhz - 5950) / 5
        WifiBand.UNKNOWN -> null
    }

    /** Usual field thresholds: -50 dBm or better is excellent, below -80 dBm is unusable. */
    fun signalQuality(rssiDbm: Int): SignalQuality = when {
        rssiDbm >= -50 -> SignalQuality.EXCELLENT
        rssiDbm >= -60 -> SignalQuality.GOOD
        rssiDbm >= -70 -> SignalQuality.FAIR
        rssiDbm >= -80 -> SignalQuality.WEAK
        else -> SignalQuality.VERY_WEAK
    }

    /** Maps -100 dBm..-30 dBm to 0..100 %. */
    fun signalPercent(rssiDbm: Int): Int = ((rssiDbm + 100) * 100 / 70).coerceIn(0, 100)

    /** Short security label from ScanResult.capabilities, e.g. "[WPA2-PSK-CCMP][RSN-SAE-CCMP]". */
    fun securityOf(capabilities: String): String = when {
        capabilities.contains("SAE") || capabilities.contains("WPA3") -> "WPA3"
        capabilities.contains("RSN") || capabilities.contains("WPA2") -> "WPA2"
        capabilities.contains("WPA") -> "WPA"
        capabilities.contains("WEP") -> "WEP"
        else -> OPEN_NETWORK
    }

    const val OPEN_NETWORK = "Aberta"

    /** Marketing name for android.net.wifi.ScanResult.WIFI_STANDARD_* values. */
    fun standardName(wifiStandard: Int): String? = when (wifiStandard) {
        4 -> "Wi-Fi 4 (802.11n)"
        5 -> "Wi-Fi 5 (802.11ac)"
        6 -> "Wi-Fi 6 (802.11ax)"
        7 -> "802.11ad"
        8 -> "Wi-Fi 7 (802.11be)"
        1 -> "802.11a/b/g"
        else -> null
    }

    /** android.net.wifi.ScanResult.CHANNEL_WIDTH_* to MHz. */
    fun channelWidthMhz(channelWidth: Int): Int = when (channelWidth) {
        1 -> 40
        2 -> 80
        3, 4 -> 160
        5 -> 320
        else -> 20
    }
}
