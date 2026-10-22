package br.com.timasters.app.core.network

import kotlin.math.abs

/** Summary of round-trip times in milliseconds. */
data class LatencyStats(
    val sent: Int,
    val received: Int,
    val minMs: Double?,
    val avgMs: Double?,
    val maxMs: Double?,
    /** Mean absolute difference between consecutive replies (RFC 3550 style, unsmoothed). */
    val jitterMs: Double?
) {
    val lossPercent: Double get() = if (sent == 0) 0.0 else (sent - received) * 100.0 / sent

    companion object {
        fun from(sent: Int, timesMs: List<Double>): LatencyStats = LatencyStats(
            sent = sent,
            received = timesMs.size,
            minMs = timesMs.minOrNull(),
            avgMs = timesMs.takeIf { it.isNotEmpty() }?.average(),
            maxMs = timesMs.maxOrNull(),
            jitterMs = jitter(timesMs)
        )

        fun jitter(timesMs: List<Double>): Double? =
            if (timesMs.size < 2) null else timesMs.zipWithNext { a, b -> abs(b - a) }.average()

        fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle] else (sorted[middle - 1] + sorted[middle]) / 2
        }

        /** Megabits per second for [bytes] transferred in [nanos]. */
        fun mbps(bytes: Long, nanos: Long): Double =
            if (nanos <= 0) 0.0 else bytes * 8.0 / (nanos / 1_000_000_000.0) / 1_000_000.0
    }
}

/** Parses the output of Android's `ping` binary. */
object PingOutputParser {

    private val REPLY = Regex("""icmp_seq=(\d+).*?time[=<]([\d.]+)\s*ms""")

    /** Round-trip time in ms for a reply line, or null for any other line. */
    fun parseReplyTime(line: String): Double? =
        REPLY.find(line)?.groupValues?.get(2)?.toDoubleOrNull()
}
