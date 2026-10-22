package br.com.timasters.app.data.lan

import br.com.timasters.app.core.network.SubnetUtils
import br.com.timasters.app.data.network.ConnectionInfoRepository
import br.com.timasters.app.data.network.TransportType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withTimeoutOrNull
import java.io.IOException
import java.net.ConnectException
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

data class LanDevice(
    val ip: String,
    val hostname: String?,
    val isGateway: Boolean,
    val isThisDevice: Boolean,
    val openPorts: List<Int>,
    val latencyMs: Long?
)

data class LanScanProgress(
    /** e.g. "192.168.0.0/24" */
    val subnet: String,
    val scanned: Int,
    val total: Int,
    val devices: List<LanDevice>,
    val isComplete: Boolean
)

/** Thrown when the phone is not on Wi-Fi/Ethernet with an IPv4 address. */
class NoLocalNetworkException : IOException("no local IPv4 network")

/**
 * Finds devices on the local /24 network. Android 10+ blocks the ARP table, so a device is found
 * when it answers ICMP/TCP echo or any of [DISCOVERY_PORTS] (a refused connection also proves the
 * host exists). MAC addresses are therefore not available.
 */
@Singleton
class LanScanner @Inject constructor(
    private val connectionInfo: ConnectionInfoRepository
) {
    fun scan(): Flow<LanScanProgress> = channelFlow {
        val connection = connectionInfo.current()
        val localIp = connection.ipv4.firstOrNull()
        if (connection.transport !in setOf(TransportType.WIFI, TransportType.ETHERNET) || localIp == null) {
            throw NoLocalNetworkException()
        }
        val hosts = SubnetUtils.hostsToScan(localIp, connection.ipv4PrefixLength ?: SubnetUtils.MIN_SCAN_PREFIX)
        val subnet = "${hosts.first().substringBeforeLast('.')}.0/${maxOf(connection.ipv4PrefixLength ?: 24, 24)}"
        val found = ConcurrentHashMap<String, LanDevice>()
        val scanned = AtomicInteger(0)
        val limiter = Semaphore(PARALLEL_HOSTS)

        fun snapshot(complete: Boolean) = LanScanProgress(
            subnet = subnet,
            scanned = scanned.get(),
            total = hosts.size,
            devices = found.values.sortedBy { SubnetUtils.ipv4ToInt(it.ip)?.toUInt() },
            isComplete = complete
        )

        send(snapshot(complete = false))
        coroutineScope {
            hosts.forEach { host ->
                launch(Dispatchers.IO) {
                    limiter.withPermit {
                        val isThisDevice = host == localIp
                        val isGateway = host == connection.gateway
                        val latency = if (isThisDevice) 0L else probe(host)
                        // The router is known from the routing table even if it ignores probes.
                        if (latency != null || isGateway) {
                            found[host] = LanDevice(
                                ip = host,
                                hostname = null,
                                isGateway = isGateway,
                                isThisDevice = isThisDevice,
                                openPorts = emptyList(),
                                latencyMs = latency?.takeUnless { isThisDevice }
                            )
                        }
                        scanned.incrementAndGet()
                        send(snapshot(complete = false))
                    }
                }
            }
        }

        // Second pass on the devices found: name (reverse DNS) and common services.
        coroutineScope {
            found.values.toList().map { device ->
                async(Dispatchers.IO) {
                    val hostname = withTimeoutOrNull(HOSTNAME_TIMEOUT_MS) { reverseDns(device.ip) }
                    val ports = if (device.isThisDevice) emptyList() else openServicePorts(device.ip)
                    found[device.ip] = device.copy(hostname = hostname, openPorts = ports)
                }
            }.awaitAll()
        }
        send(snapshot(complete = true))
    }.flowOn(Dispatchers.IO)

    /** Time in ms of the fastest answer from the host, or null when nothing answered. */
    private suspend fun probe(host: String): Long? = coroutineScope {
        fun timed(check: () -> Boolean): Long? {
            val start = System.nanoTime()
            return if (check()) (System.nanoTime() - start) / 1_000_000 else null
        }
        val attempts = DISCOVERY_PORTS.map { port -> async { timed { tcpAnswers(host, port, PROBE_TIMEOUT_MS) } } } +
            async { timed { runCatching { InetAddress.getByName(host).isReachable(PROBE_TIMEOUT_MS) }.getOrDefault(false) } }
        attempts.awaitAll().filterNotNull().minOrNull()
    }

    private suspend fun openServicePorts(host: String): List<Int> = coroutineScope {
        SERVICE_PORTS.map { port -> async { port to tcpOpen(host, port, SERVICE_TIMEOUT_MS) } }
            .awaitAll()
            .filter { it.second }
            .map { it.first }
    }

    private fun reverseDns(ip: String): String? {
        val name = runCatching { InetAddress.getByName(ip).canonicalHostName }.getOrNull()
        return name?.takeUnless { it == ip }
    }

    private companion object {
        const val PARALLEL_HOSTS = 48
        const val PROBE_TIMEOUT_MS = 600
        const val SERVICE_TIMEOUT_MS = 700
        const val HOSTNAME_TIMEOUT_MS = 2_000L
        val DISCOVERY_PORTS = listOf(80, 443, 22, 445, 139, 53, 8291, 554)
        val SERVICE_PORTS = listOf(21, 22, 23, 53, 80, 139, 443, 445, 554, 631, 3389, 5000, 8080, 8291, 8443, 8728, 9100, 37777)
    }
}

/** True when the host answers on [port], open or refused. */
internal fun tcpAnswers(host: String, port: Int, timeoutMs: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
    true
} catch (_: ConnectException) {
    true
} catch (_: IOException) {
    false
}

/** True only when a TCP connection to [port] is accepted. */
internal fun tcpOpen(host: String, port: Int, timeoutMs: Int): Boolean = try {
    Socket().use { it.connect(InetSocketAddress(host, port), timeoutMs) }
    true
} catch (_: IOException) {
    false
}
