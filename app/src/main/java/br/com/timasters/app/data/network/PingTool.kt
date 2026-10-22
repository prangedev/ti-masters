package br.com.timasters.app.data.network

import br.com.timasters.app.core.network.HostValidator
import br.com.timasters.app.core.network.PingOutputParser
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.FlowCollector
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import javax.inject.Inject
import javax.inject.Singleton

enum class PingMethod { ICMP, TCP }

sealed interface PingEvent {
    data class Resolved(val host: String, val address: String) : PingEvent
    data class Reply(val sequence: Int, val timeMs: Double, val method: PingMethod) : PingEvent
    data class Lost(val sequence: Int, val method: PingMethod) : PingEvent

    /** ICMP got no answer (blocked by firewall or not allowed on this phone); retrying over TCP. */
    data class FallingBackToTcp(val port: Int) : PingEvent
}

@Singleton
class PingTool @Inject constructor() {

    /**
     * Pings [host] with the system `ping` binary (ICMP). If ICMP is unavailable or gets no reply,
     * measures TCP connection time to port 443 instead. Throws [java.net.UnknownHostException].
     */
    fun ping(host: String, count: Int = DEFAULT_COUNT): Flow<PingEvent> = flow {
        val name = HostValidator.normalize(host)
        require(HostValidator.isValid(name)) { "invalid host" }
        val address = InetAddress.getByName(name).hostAddress ?: throw IOException("no address")
        emit(PingEvent.Resolved(name, address))

        val icmpReplies = runIcmp(address, count)
        if (icmpReplies == 0) {
            emit(PingEvent.FallingBackToTcp(TCP_PORT))
            runTcp(address, count)
        }
    }.flowOn(Dispatchers.IO)

    /** Returns how many replies arrived (0 also when the binary is missing or not permitted). */
    private suspend fun FlowCollector<PingEvent>.runIcmp(address: String, count: Int): Int {
        val process = try {
            // Arguments are passed directly (no shell), and the address was already resolved.
            ProcessBuilder("/system/bin/ping", "-c", count.toString(), "-W", "2", "-w", (count * 2 + 2).toString(), address)
                .redirectErrorStream(true)
                .start()
        } catch (_: IOException) {
            return 0
        }
        var replies = 0
        var lastSequence = 0
        try {
            process.inputStream.bufferedReader().useLines { lines ->
                for (line in lines) {
                    if (!currentCoroutineContext().isActive) break
                    val time = PingOutputParser.parseReplyTime(line) ?: continue
                    val sequence = Regex("""icmp_seq=(\d+)""").find(line)?.groupValues?.get(1)?.toIntOrNull()
                        ?: (lastSequence + 1)
                    for (missing in (lastSequence + 1) until sequence) emit(PingEvent.Lost(missing, PingMethod.ICMP))
                    lastSequence = sequence
                    replies++
                    emit(PingEvent.Reply(sequence, time, PingMethod.ICMP))
                }
            }
        } finally {
            process.destroy()
        }
        if (replies > 0) {
            for (missing in (lastSequence + 1)..count) emit(PingEvent.Lost(missing, PingMethod.ICMP))
        }
        return replies
    }

    private suspend fun FlowCollector<PingEvent>.runTcp(address: String, count: Int) {
        for (sequence in 1..count) {
            val start = System.nanoTime()
            val reached = try {
                Socket().use { it.connect(InetSocketAddress(address, TCP_PORT), TIMEOUT_MS) }
                true
            } catch (_: ConnectException) {
                // A refusal is still an answer from the host: the round trip is valid.
                true
            } catch (_: SocketTimeoutException) {
                false
            } catch (_: IOException) {
                false
            }
            val elapsedMs = (System.nanoTime() - start) / 1_000_000.0
            emit(if (reached) PingEvent.Reply(sequence, elapsedMs, PingMethod.TCP) else PingEvent.Lost(sequence, PingMethod.TCP))
            if (sequence < count) delay(INTERVAL_MS)
        }
    }

    companion object {
        const val DEFAULT_COUNT = 10
        const val TCP_PORT = 443
        private const val TIMEOUT_MS = 2_000
        private const val INTERVAL_MS = 500L
    }
}
