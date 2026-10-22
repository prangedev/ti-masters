package br.com.timasters.app.core.network

/** A network as it occupies the spectrum. */
data class SpectrumEntry(val centerMhz: Int, val widthMhz: Int, val rssiDbm: Int)

data class ChannelUsage(val channel: Int, val frequencyMhz: Int, val networks: Int, val score: Int)

/**
 * Suggests the least busy channel. Each overlapping network adds its signal above the noise floor
 * (-100 dBm), so a strong neighbor weighs more than a distant one.
 */
object ChannelAdvisor {

    /** The only non-overlapping 20 MHz channels in 2.4 GHz. */
    val CANDIDATES_2_4 = listOf(1, 6, 11)

    /** 5 GHz channels without DFS (radar check), the safest choice for most routers in Brazil. */
    val CANDIDATES_5 = listOf(36, 40, 44, 48, 149, 153, 157, 161)

    fun usage(band: WifiBand, networks: List<SpectrumEntry>): List<ChannelUsage> {
        val candidates = when (band) {
            WifiBand.GHZ_2_4 -> CANDIDATES_2_4
            WifiBand.GHZ_5 -> CANDIDATES_5
            else -> return emptyList()
        }
        return candidates.map { channel ->
            val frequency = if (band == WifiBand.GHZ_2_4) 2407 + 5 * channel else 5000 + 5 * channel
            val overlapping = networks.filter { overlaps(it, frequency) }
            ChannelUsage(
                channel = channel,
                frequencyMhz = frequency,
                networks = overlapping.size,
                score = overlapping.sumOf { (it.rssiDbm + 100).coerceAtLeast(0) }
            )
        }
    }

    fun recommend(band: WifiBand, networks: List<SpectrumEntry>): ChannelUsage? =
        usage(band, networks).minByOrNull { it.score }

    private fun overlaps(network: SpectrumEntry, channelCenterMhz: Int): Boolean {
        val networkLow = network.centerMhz - network.widthMhz / 2
        val networkHigh = network.centerMhz + network.widthMhz / 2
        return networkLow < channelCenterMhz + 10 && networkHigh > channelCenterMhz - 10
    }
}
