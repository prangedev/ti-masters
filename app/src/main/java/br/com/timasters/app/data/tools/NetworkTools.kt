package br.com.timasters.app.data.tools

import br.com.timasters.app.core.network.HostValidator
import br.com.timasters.app.core.network.SubnetUtils
import br.com.timasters.app.data.network.ConnectionInfoRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509TrustManager

data class TraceHop(val ttl: Int, val address: String?, val timeMs: Double?, val hostname: String?, val isDestination: Boolean)

data class DnsLookupResult(val server: String, val timeMs: Long, val answer: DnsAnswer)

data class DnsServerScore(val name: String, val address: String, val medianMs: Long?, val failures: Int)

data class CertificateInfo(
    val subject: String,
    val issuer: String,
    val notAfterMillis: Long,
    val daysLeft: Long,
    val alternativeNames: List<String>,
    /** False when the phone does not trust it (self-signed, expired, wrong name, missing chain). */
    val trusted: Boolean,
    val tlsVersion: String?
)

data class SiteCheckResult(
    val url: String,
    val status: Int,
    val timeMs: Long,
    val redirects: List<String>,
    val server: String?,
    val certificate: CertificateInfo?
)

/** Built-in network tests: traceroute, DNS, HTTPS/certificate check, Wake-on-LAN and MTU. */
@Singleton
class NetworkTools @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val connectionInfo: ConnectionInfoRepository
) {

    // region Traceroute

    /** One probe per hop with the system ping and increasing TTL; stops at the destination. */
    fun traceroute(host: String, maxHops: Int = 30): Flow<TraceHop> = flow {
        val target = resolve(host)
        for (ttl in 1..maxHops) {
            val started = System.nanoTime()
            val output = runPing("-n", "-c", "1", "-W", "2", "-t", ttl.toString(), target)
            val elapsed = (System.nanoTime() - started) / 1e6
            val hop = when (val line = TracerouteParser.parse(output)) {
                is TraceLine.Reached -> TraceHop(ttl, line.address, line.timeMs, reverse(line.address), true)
                // ping doesn't print a time for routers; the wall clock is a fair approximation.
                is TraceLine.Hop -> TraceHop(ttl, line.address, elapsed, reverse(line.address), false)
                TraceLine.Silent -> TraceHop(ttl, null, null, null, false)
            }
            emit(hop)
            if (hop.isDestination) break
        }
    }.flowOn(Dispatchers.IO)

    // endregion

    // region DNS

    suspend fun dnsLookup(name: String, type: DnsType, server: String?): DnsLookupResult = withContext(Dispatchers.IO) {
        val dns = server?.takeIf { it.isNotBlank() } ?: systemDns().firstOrNull() ?: PUBLIC_DNS.first().second
        val started = System.nanoTime()
        val answer = query(dns, name, type)
        DnsLookupResult(dns, (System.nanoTime() - started) / 1_000_000, answer)
    }

    /** Median response time of each server over a few popular domains (3 rounds). */
    suspend fun dnsBenchmark(): List<DnsServerScore> = withContext(Dispatchers.IO) {
        val servers = systemDns().map { "Provedor / roteador" to it } + PUBLIC_DNS
        coroutineScope {
            servers.distinctBy { it.second }.map { (name, address) ->
                async {
                    val times = mutableListOf<Long>()
                    var failures = 0
                    repeat(3) { round ->
                        BENCHMARK_DOMAINS.forEach { domain ->
                            val started = System.nanoTime()
                            // "Domain doesn't exist" (rcode 3) is still a correct, timed answer; only
                            // silence, server failure (2) or refusal (5) count against the server.
                            val rcode = runCatching { query(address, if (round == 0) domain else "www.$domain", DnsType.A) }.getOrNull()?.rcode
                            val ok = rcode == 0 || rcode == 3
                            if (ok) times += (System.nanoTime() - started) / 1_000_000 else failures++
                        }
                    }
                    DnsServerScore(name, address, times.sorted().getOrNull(times.size / 2), failures)
                }
            }.awaitAll()
        }.sortedWith(compareBy(nullsLast()) { it.medianMs })
    }

    private fun query(server: String, name: String, type: DnsType): DnsAnswer {
        val id = SecureRandom().nextInt(0xFFFF)
        val packet = DnsMessage.query(id, name, type)
        DatagramSocket().use { socket ->
            socket.soTimeout = DNS_TIMEOUT_MS
            socket.send(DatagramPacket(packet, packet.size, InetSocketAddress(server, 53)))
            val buffer = ByteArray(4096)
            while (true) {
                val response = DatagramPacket(buffer, buffer.size)
                socket.receive(response) // SocketTimeoutException when the server doesn't answer
                val data = buffer.copyOf(response.length)
                if (DnsMessage.idOf(data) == id) return DnsMessage.parse(data)
            }
        }
    }

    private fun systemDns(): List<String> = connectionInfo.current().dnsServers.filter { SubnetUtils.isValidIpv4(it) }

    // endregion

    // region HTTPS and certificates

    /** Follows up to 5 redirects to show the whole chain; HTTPS only (clear-text is disabled app-wide). */
    suspend fun checkSite(input: String): SiteCheckResult = withContext(Dispatchers.IO) {
        val client = okHttpClient.newBuilder().followRedirects(false).followSslRedirects(false)
            .callTimeout(15, TimeUnit.SECONDS).build()
        var url = normalizeUrl(input)
        val redirects = mutableListOf<String>()
        val started = System.nanoTime()
        repeat(MAX_REDIRECTS + 1) {
            client.newCall(Request.Builder().url(url).get().build()).execute().use { response ->
                val next = response.header("Location")?.let { response.request.url.resolve(it) }?.toString()?.replaceFirst("http://", "https://")
                if (response.isRedirect && next != null && redirects.size < MAX_REDIRECTS) {
                    redirects += url
                    url = next
                } else {
                    val handshake = response.handshake
                    val certificate = (handshake?.peerCertificates?.firstOrNull() as? X509Certificate)
                        ?.let { describe(it, trusted = true, tlsVersion = handshake.tlsVersion.javaName) }
                    return@withContext SiteCheckResult(url, response.code, (System.nanoTime() - started) / 1_000_000, redirects, response.header("Server"), certificate)
                }
            }
        }
        throw IOException("too many redirects")
    }

    /** Certificate of any TLS service (router HTTPS, mail 993/465, RDP gateway…), trusted or not. */
    suspend fun checkCertificate(host: String, port: Int): CertificateInfo = withContext(Dispatchers.IO) {
        val name = HostValidator.normalize(host)
        require(HostValidator.isValid(name) && port in 1..65535)
        try {
            handshake(name, port, null)
        } catch (_: SSLHandshakeException) {
            // Not trusted by the phone: read it anyway (nothing is sent) and report it as untrusted.
            handshake(name, port, ReadOnlyTrustManager)
        }
    }

    private fun handshake(host: String, port: Int, trustManager: X509TrustManager?): CertificateInfo {
        val context = SSLContext.getInstance("TLS").apply { init(null, trustManager?.let { arrayOf(it) }, SecureRandom()) }
        (context.socketFactory.createSocket() as SSLSocket).use { socket ->
            socket.connect(InetSocketAddress(host, port), TLS_TIMEOUT_MS)
            socket.soTimeout = TLS_TIMEOUT_MS
            socket.sslParameters = socket.sslParameters.apply {
                if (!SubnetUtils.isValidIpv4(host)) serverNames = listOf(javax.net.ssl.SNIHostName(host))
                endpointIdentificationAlgorithm = if (trustManager == null) "HTTPS" else null
            }
            socket.startHandshake()
            val certificate = socket.session.peerCertificates.first() as X509Certificate
            return describe(certificate, trusted = trustManager == null, tlsVersion = socket.session.protocol)
        }
    }

    private fun describe(certificate: X509Certificate, trusted: Boolean, tlsVersion: String?): CertificateInfo {
        fun commonName(dn: String) = Regex("(?:^|,)\\s*CN=([^,]+)").find(dn)?.groupValues?.get(1) ?: dn
        fun organization(dn: String) = Regex("(?:^|,)\\s*O=([^,]+)").find(dn)?.groupValues?.get(1)
        val notAfter = certificate.notAfter.time
        val names = runCatching { certificate.subjectAlternativeNames?.mapNotNull { it.getOrNull(1) as? String } }.getOrNull().orEmpty()
        val issuer = certificate.issuerX500Principal.name
        return CertificateInfo(
            subject = commonName(certificate.subjectX500Principal.name),
            issuer = listOfNotNull(organization(issuer), commonName(issuer)).distinct().joinToString(" · "),
            notAfterMillis = notAfter,
            daysLeft = TimeUnit.MILLISECONDS.toDays(notAfter - System.currentTimeMillis()),
            alternativeNames = names.take(10),
            trusted = trusted,
            tlsVersion = tlsVersion
        )
    }

    /** Only used to read a certificate the phone rejected; the connection is closed right after. */
    @Suppress("CustomX509TrustManager", "TrustAllX509TrustManager")
    private object ReadOnlyTrustManager : X509TrustManager {
        override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) = Unit
        override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
    }

    // endregion

    // region Wake-on-LAN

    /** Directed broadcast of the current network (e.g. 192.168.0.255), or null without Wi-Fi/cable. */
    fun localBroadcast(): String? {
        val details = connectionInfo.current()
        val ip = details.ipv4.firstOrNull() ?: return null
        val prefix = details.ipv4PrefixLength ?: 24
        return SubnetCalculator.calculate(ip, prefix).broadcast
    }

    /** Sends the magic packet 3 times to ports 9 and 7. */
    suspend fun wakeOnLan(mac: ByteArray, broadcast: String) = withContext(Dispatchers.IO) {
        require(SubnetUtils.isValidIpv4(broadcast))
        val packet = WakeOnLanPacket.build(mac)
        DatagramSocket().use { socket ->
            socket.broadcast = true
            val address = InetAddress.getByName(broadcast)
            repeat(3) { listOf(9, 7).forEach { port -> socket.send(DatagramPacket(packet, packet.size, address, port)) } }
        }
    }

    // endregion

    // region MTU

    /**
     * Largest packet that crosses the path without fragmentation (ping with "don't fragment"),
     * found by binary search. Returns the MTU (payload + 28 bytes of IP/ICMP headers), or null when
     * even small packets get no answer (ICMP blocked).
     */
    suspend fun discoverMtu(host: String): Int? = withContext(Dispatchers.IO) {
        val target = resolve(host)
        suspend fun passes(payload: Int) = TracerouteParser.parse(runPing("-n", "-c", "1", "-W", "2", "-M", "do", "-s", payload.toString(), target)) is TraceLine.Reached
        if (!passes(MIN_PAYLOAD)) return@withContext null
        var low = MIN_PAYLOAD
        var high = MAX_PAYLOAD
        while (low < high) {
            val middle = (low + high + 1) / 2
            if (passes(middle)) low = middle else high = middle - 1
        }
        low + 28
    }

    // endregion

    private fun resolve(host: String): String {
        val name = HostValidator.normalize(host)
        require(HostValidator.isValid(name)) { "invalid host" }
        return InetAddress.getByName(name).hostAddress ?: throw IOException("no address")
    }

    private suspend fun reverse(address: String): String? = withTimeoutOrNull(1_500) {
        withContext(Dispatchers.IO) {
            runCatching { InetAddress.getByName(address).canonicalHostName }.getOrNull()?.takeIf { it != address }
        }
    }

    /** Runs /system/bin/ping with fixed arguments (no shell); the target is an already-resolved address. */
    private fun runPing(vararg args: String): String = try {
        val process = ProcessBuilder(listOf("/system/bin/ping") + args).redirectErrorStream(true).start()
        try {
            process.inputStream.bufferedReader().readText()
        } finally {
            process.destroy()
        }
    } catch (_: IOException) {
        ""
    }

    private fun normalizeUrl(input: String): String {
        val text = input.trim().removePrefix("http://").removePrefix("https://")
        require(text.isNotEmpty())
        return "https://$text"
    }

    companion object {
        val PUBLIC_DNS = listOf(
            "Cloudflare" to "1.1.1.1",
            "Google" to "8.8.8.8",
            "Quad9" to "9.9.9.9",
            "OpenDNS" to "208.67.222.222"
        )
        private val BENCHMARK_DOMAINS = listOf("google.com", "youtube.com", "whatsapp.net", "globo.com", "gov.br")
        private const val DNS_TIMEOUT_MS = 2_000
        private const val TLS_TIMEOUT_MS = 8_000
        private const val MAX_REDIRECTS = 5
        private const val MIN_PAYLOAD = 1_000
        private const val MAX_PAYLOAD = 1_472
    }
}
