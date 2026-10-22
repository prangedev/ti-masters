package br.com.timasters.app.data.tools

import br.com.timasters.app.core.network.SubnetUtils

data class SubnetInfo(
    val address: String,
    val prefix: Int,
    val network: String,
    val broadcast: String,
    val mask: String,
    val wildcard: String,
    val firstHost: String,
    val lastHost: String,
    val usableHosts: Long,
    val totalAddresses: Long,
    val isPrivate: Boolean
) {
    val cidr: String get() = "$network/$prefix"
}

/** One VLSM request ("Câmeras", 50 hosts) and the subnet it received. */
data class VlsmRequest(val name: String, val hosts: Int)
data class VlsmAllocation(val request: VlsmRequest, val subnet: SubnetInfo)

/** IPv4 subnet math. Addresses are handled as unsigned 32-bit values inside Long. */
object SubnetCalculator {

    /** Accepts "192.168.1.10/24", "192.168.1.10 255.255.255.0" or just an address (assumes /24). */
    fun parse(input: String): Pair<String, Int>? {
        val text = input.trim()
        val parts = text.split('/', ' ').filter { it.isNotBlank() }
        val ip = parts.firstOrNull()?.takeIf(SubnetUtils::isValidIpv4) ?: return null
        val second = parts.getOrNull(1) ?: return ip to DEFAULT_PREFIX
        val prefix = second.toIntOrNull()?.takeIf { it in 0..32 } ?: maskToPrefix(second) ?: return null
        return ip to prefix
    }

    fun maskToPrefix(mask: String): Int? {
        val value = SubnetUtils.ipv4ToInt(mask)?.toLong()?.and(ALL) ?: return null
        val prefix = java.lang.Long.bitCount(value)
        return prefix.takeIf { maskOf(it) == value }
    }

    fun calculate(ip: String, prefix: Int): SubnetInfo {
        require(prefix in 0..32)
        val address = SubnetUtils.ipv4ToInt(ip)?.toLong()?.and(ALL) ?: throw IllegalArgumentException("ip")
        val mask = maskOf(prefix)
        val network = address and mask
        val broadcast = network or (mask.inv() and ALL)
        val total = 1L shl (32 - prefix)
        // RFC 3021: /31 links use both addresses; /32 is a single host.
        val (first, last, usable) = when (prefix) {
            32 -> Triple(network, network, 1L)
            31 -> Triple(network, broadcast, 2L)
            else -> Triple(network + 1, broadcast - 1, total - 2)
        }
        return SubnetInfo(
            address = ip,
            prefix = prefix,
            network = str(network),
            broadcast = str(broadcast),
            mask = str(mask),
            wildcard = str(mask.inv() and ALL),
            firstHost = str(first),
            lastHost = str(last),
            usableHosts = usable,
            totalAddresses = total,
            isPrivate = isPrivate(network)
        )
    }

    /** Splits the network into [count] equal subnets (rounded up to a power of two). */
    fun split(ip: String, prefix: Int, count: Int): List<SubnetInfo>? {
        if (count < 2) return null
        val extraBits = 32 - Integer.numberOfLeadingZeros(count - 1)
        val newPrefix = prefix + extraBits
        if (newPrefix > 30) return null
        val base = SubnetUtils.ipv4ToInt(ip)!!.toLong() and ALL and maskOf(prefix)
        val size = 1L shl (32 - newPrefix)
        return (0 until (1 shl extraBits)).map { calculate(str(base + it * size), newPrefix) }
    }

    /**
     * VLSM: gives each request the smallest subnet that fits it, biggest first, packed from the
     * start of the network. Returns null when everything doesn't fit.
     */
    fun vlsm(ip: String, prefix: Int, requests: List<VlsmRequest>): List<VlsmAllocation>? {
        val base = SubnetUtils.ipv4ToInt(ip)?.toLong()?.and(ALL)?.and(maskOf(prefix)) ?: return null
        val end = base + (1L shl (32 - prefix))
        var next = base
        return requests.filter { it.hosts > 0 }.sortedByDescending { it.hosts }.map { request ->
            val bits = 64 - java.lang.Long.numberOfLeadingZeros((request.hosts + 2 - 1).toLong())
            val size = 1L shl bits.coerceAtLeast(2)
            val start = (next + size - 1) / size * size
            if (start + size > end) return null
            next = start + size
            VlsmAllocation(request, calculate(str(start), 32 - java.lang.Long.numberOfTrailingZeros(size)))
        }
    }

    private fun maskOf(prefix: Int): Long = if (prefix == 0) 0 else (ALL shl (32 - prefix)) and ALL

    private fun isPrivate(value: Long): Boolean {
        val a = (value shr 24) and 0xFF
        val b = (value shr 16) and 0xFF
        return a == 10L || (a == 172L && b in 16..31) || (a == 192L && b == 168L) || (a == 100L && b in 64..127)
    }

    private fun str(value: Long): String = SubnetUtils.intToIpv4(value.toInt())

    private const val ALL = 0xFFFFFFFFL
    private const val DEFAULT_PREFIX = 24
}
