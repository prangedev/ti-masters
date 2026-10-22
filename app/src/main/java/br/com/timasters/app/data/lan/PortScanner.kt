package br.com.timasters.app.data.lan

import br.com.timasters.app.core.network.HostValidator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.net.InetAddress
import java.util.concurrent.ConcurrentSkipListSet
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class PortScanProgress(
    val host: String,
    val address: String,
    val scanned: Int,
    val total: Int,
    val openPorts: List<Int>,
    val isComplete: Boolean
)

/** TCP connect() scan (raw SYN scans need root, which Android apps do not have). */
@Singleton
class PortScanner @Inject constructor() {

    fun scan(host: String, ports: List<Int>): Flow<PortScanProgress> = channelFlow {
        val name = HostValidator.normalize(host)
        require(HostValidator.isValid(name)) { "invalid host" }
        val address = InetAddress.getByName(name).hostAddress ?: name
        val open = ConcurrentSkipListSet<Int>()
        val scanned = AtomicInteger(0)
        val limiter = Semaphore(PARALLEL_CONNECTIONS)

        fun snapshot(complete: Boolean) =
            PortScanProgress(name, address, scanned.get(), ports.size, open.toList(), complete)

        send(snapshot(complete = false))
        coroutineScope {
            ports.forEach { port ->
                launch(Dispatchers.IO) {
                    limiter.withPermit {
                        if (tcpOpen(address, port, TIMEOUT_MS)) open += port
                        val done = scanned.incrementAndGet()
                        // Updating the screen for every port would be wasteful on large ranges.
                        if (done % PROGRESS_EVERY == 0 || port in open) send(snapshot(complete = false))
                    }
                }
            }
        }
        send(snapshot(complete = true))
    }.flowOn(Dispatchers.IO)

    private companion object {
        const val PARALLEL_CONNECTIONS = 100
        const val TIMEOUT_MS = 800
        const val PROGRESS_EVERY = 20
    }
}
