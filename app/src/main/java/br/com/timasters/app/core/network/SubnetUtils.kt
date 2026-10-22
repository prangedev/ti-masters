package br.com.timasters.app.core.network

object SubnetUtils {

    /** Larger networks are limited to the /24 around this device to keep scans short. */
    const val MIN_SCAN_PREFIX = 24

    fun ipv4ToInt(ip: String): Int? {
        val parts = ip.trim().split('.')
        if (parts.size != 4) return null
        var result = 0
        for (part in parts) {
            val octet = part.toIntOrNull() ?: return null
            if (octet !in 0..255 || part.length > 3) return null
            result = (result shl 8) or octet
        }
        return result
    }

    fun intToIpv4(value: Int): String =
        listOf(24, 16, 8, 0).joinToString(".") { shift -> ((value ushr shift) and 0xFF).toString() }

    fun isValidIpv4(ip: String): Boolean = ipv4ToInt(ip) != null

    /**
     * Host addresses to probe for [localIp]/[prefixLength], excluding the network and broadcast
     * addresses. Prefixes shorter than [MIN_SCAN_PREFIX] are narrowed to it.
     */
    fun hostsToScan(localIp: String, prefixLength: Int): List<String> {
        val ip = ipv4ToInt(localIp) ?: return emptyList()
        val prefix = prefixLength.coerceIn(MIN_SCAN_PREFIX, 30)
        val mask = -1 shl (32 - prefix)
        val network = ip and mask
        val broadcast = network or mask.inv()
        return ((network + 1) until broadcast).map(::intToIpv4)
    }
}
