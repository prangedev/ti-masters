package br.com.timasters.app.data.routers

import android.content.Context
import android.net.wifi.WifiManager
import br.com.timasters.app.core.network.HostValidator
import br.com.timasters.app.data.network.ConnectionInfoRepository
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URI
import javax.inject.Inject
import javax.inject.Singleton

/** Everything found about the router of the current network. */
data class RouterDetection(
    val gateway: String?,
    val http: HttpFingerprint? = null,
    val upnp: UpnpInfo? = null,
    val mikrotik: MndpInfo? = null,
    val match: RouterMatch? = null,
    /** Best guess of the firmware / RouterOS version, when the router reveals it. */
    val firmware: String? = null,
    /** Readable model name from UPnP or the page, when the catalog has no exact model. */
    val modelHint: String? = null
) {
    val foundAnything: Boolean get() = http != null || upnp != null || mikrotik != null
}

sealed interface LatestRouterOs {
    data class Known(val installed: String, val latest: String) : LatestRouterOs {
        val isOutdated: Boolean get() = RouterOsVersions.compare(installed, latest) < 0
    }

    data object Unavailable : LatestRouterOs
}

/**
 * Identifies the router without logging in:
 * 1. its web page (title, server header, realm, sometimes the firmware version);
 * 2. UPnP (SSDP search, then the device description XML);
 * 3. MNDP, MikroTik's discovery protocol, which reveals the RouterOS version and board.
 * The page is read with a plain socket so the app doesn't need to allow clear-text HTTP everywhere;
 * only private LAN addresses are contacted.
 */
@Singleton
class RouterProbe @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val connectionInfo: ConnectionInfoRepository,
    private val catalog: RouterCatalogRepository,
    private val okHttpClient: OkHttpClient
) {
    suspend fun detect(): RouterDetection = withContext(Dispatchers.IO) {
        val gateway = connectionInfo.current().gateway?.takeIf { HostValidator.isPrivateIpv4(it) }
        val lock = multicastLock()
        try {
            coroutineScope {
                val http = async { gateway?.let { runCatching { readPage(it) }.getOrNull() } }
                val upnp = async { runCatching { discoverUpnp(gateway) }.getOrNull() }
                val mndp = async { runCatching { discoverMikroTik(gateway) }.getOrNull() }
                build(gateway, http.await(), upnp.await(), mndp.await())
            }
        } finally {
            lock?.release()
        }
    }

    /** Newest RouterOS of the same major line (needs internet). */
    suspend fun latestRouterOs(installed: String): LatestRouterOs = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder().url("https://upgrade.mikrotik.com/routeros/${RouterOsVersions.channelFor(installed)}").build()
            okHttpClient.newCall(request).execute().use { response ->
                RouterOsVersions.parseNewest(response.body.string())?.let { LatestRouterOs.Known(installed, it) }
            }
        }.getOrNull() ?: LatestRouterOs.Unavailable
    }

    private suspend fun build(gateway: String?, http: HttpFingerprint?, upnp: UpnpInfo?, mndp: MndpInfo?): RouterDetection {
        val texts = listOfNotNull(
            mndp?.let { "mikrotik routeros ${it.board.orEmpty()} ${it.platform.orEmpty()}" },
            upnp?.manufacturer, upnp?.modelName, upnp?.modelNumber, upnp?.friendlyName, upnp?.modelDescription,
            http?.realm, http?.title, http?.server, http?.snippet
        )
        val match = RouterMatcher.match(catalog.brands(), texts)
        val modelHint = mndp?.board
            ?: listOfNotNull(upnp?.modelName, upnp?.modelNumber).distinct().joinToString(" ").ifBlank { null }
            ?: http?.realm
            ?: http?.title
        return RouterDetection(
            gateway = gateway,
            http = http,
            upnp = upnp,
            mikrotik = mndp,
            match = match,
            firmware = mndp?.versionNumber ?: upnp?.softwareVersion ?: http?.firmware,
            modelHint = modelHint
        )
    }

    // region HTTP

    private fun readPage(host: String): HttpFingerprint? {
        var path = "/"
        repeat(MAX_REDIRECTS + 1) {
            val response = LanHttp.get(host, 80, path) ?: return null
            val location = response.headers["location"]
            if (response.status in 300..399 && location != null) {
                val next = runCatching { URI("http://$host$path").resolve(location) }.getOrNull()
                // Follow only plain-HTTP redirects inside the router itself.
                if (next == null || next.host != host || next.scheme != "http") {
                    return RouterFingerprintParser.parseHttp(response.headers, response.body)
                }
                path = next.rawPath.ifBlank { "/" } + (next.rawQuery?.let { "?$it" } ?: "")
            } else {
                return RouterFingerprintParser.parseHttp(response.headers, response.body)
            }
        }
        return null
    }

    // endregion

    // region UPnP

    private fun discoverUpnp(gateway: String?): UpnpInfo? {
        val locations = mutableListOf<String>()
        DatagramSocket().use { socket ->
            socket.soTimeout = 400
            val group = InetAddress.getByName("239.255.255.250")
            listOf("urn:schemas-upnp-org:device:InternetGatewayDevice:1", "upnp:rootdevice").forEach { target ->
                val message = "M-SEARCH * HTTP/1.1\r\nHOST: 239.255.255.250:1900\r\nMAN: \"ssdp:discover\"\r\nMX: 2\r\nST: $target\r\n\r\n"
                val bytes = message.toByteArray()
                socket.send(DatagramPacket(bytes, bytes.size, group, 1900))
            }
            val deadline = System.currentTimeMillis() + SSDP_WAIT_MS
            val buffer = ByteArray(2048)
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                // Only the gateway: other UPnP devices (TVs, printers) are not the router.
                if (gateway != null && packet.address.hostAddress != gateway) continue
                RouterFingerprintParser.ssdpLocation(String(packet.data, 0, packet.length))?.let { if (it !in locations) locations += it }
            }
        }
        return locations.firstNotNullOfOrNull { location ->
            val uri = runCatching { URI(location) }.getOrNull() ?: return@firstNotNullOfOrNull null
            val host = uri.host ?: return@firstNotNullOfOrNull null
            if (!HostValidator.isPrivateIpv4(host) || uri.scheme != "http") return@firstNotNullOfOrNull null
            val port = if (uri.port > 0) uri.port else 80
            val response = LanHttp.get(host, port, uri.rawPath.ifBlank { "/" }) ?: return@firstNotNullOfOrNull null
            RouterFingerprintParser.parseUpnp(response.body).takeIf { it.manufacturer != null || it.modelName != null }
        }
    }

    // endregion

    // region MikroTik (MNDP)

    private fun discoverMikroTik(gateway: String?): MndpInfo? {
        val socket = DatagramSocket(null).apply {
            reuseAddress = true
            broadcast = true
            soTimeout = 400
            bind(InetSocketAddress(MNDP_PORT))
        }
        socket.use {
            // An empty 4-byte packet asks every RouterOS device on the network to announce itself.
            val request = ByteArray(4)
            it.send(DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), MNDP_PORT))
            val deadline = System.currentTimeMillis() + MNDP_WAIT_MS
            val buffer = ByteArray(1500)
            var any: MndpInfo? = null
            while (System.currentTimeMillis() < deadline) {
                val packet = DatagramPacket(buffer, buffer.size)
                try {
                    it.receive(packet)
                } catch (_: SocketTimeoutException) {
                    continue
                }
                val info = RouterFingerprintParser.parseMndp(packet.data.copyOf(packet.length)) ?: continue
                val from = packet.address.hostAddress
                // Prefer the device that is the gateway; otherwise keep the first MikroTik heard.
                if (from == gateway || info.ipv4 == gateway) return info
                if (any == null) any = info
            }
            return any
        }
    }

    // endregion

    private fun multicastLock(): WifiManager.MulticastLock? = runCatching {
        context.applicationContext.getSystemService(WifiManager::class.java)
            ?.createMulticastLock("ti-masters-router-probe")
            ?.apply {
                setReferenceCounted(false)
                acquire()
            }
    }.getOrNull()

    private companion object {
        const val MAX_REDIRECTS = 2
        const val SSDP_WAIT_MS = 2_500L
        const val MNDP_PORT = 5678
        const val MNDP_WAIT_MS = 2_500L
    }
}

/** Minimal HTTP/1.0 GET over a socket, for router pages on the local network only. */
internal object LanHttp {

    data class Response(val status: Int, val headers: Map<String, String>, val body: String)

    fun get(host: String, port: Int, path: String): Response? {
        if (!HostValidator.isPrivateIpv4(host)) return null
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), TIMEOUT_MS)
            socket.soTimeout = TIMEOUT_MS
            val request = "GET $path HTTP/1.0\r\nHost: $host\r\nUser-Agent: TIMasters-Android\r\nAccept: text/html,*/*\r\nConnection: close\r\n\r\n"
            socket.getOutputStream().apply {
                write(request.toByteArray())
                flush()
            }
            val raw = ByteArrayOutputStream()
            val input = socket.getInputStream()
            val buffer = ByteArray(8192)
            try {
                while (raw.size() < MAX_BYTES) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    raw.write(buffer, 0, read)
                }
            } catch (_: SocketTimeoutException) {
                // Some routers keep the connection open; use what arrived.
            }
            return parse(raw.toByteArray())
        }
    }

    fun parse(bytes: ByteArray): Response? {
        val text = String(bytes, Charsets.ISO_8859_1)
        val split = text.indexOf("\r\n\r\n").takeIf { it >= 0 } ?: text.indexOf("\n\n").takeIf { it >= 0 } ?: return null
        val head = text.substring(0, split).lines()
        val status = head.firstOrNull()?.split(' ')?.getOrNull(1)?.toIntOrNull() ?: return null
        val headers = head.drop(1).mapNotNull { line ->
            val colon = line.indexOf(':')
            if (colon <= 0) null else line.substring(0, colon).trim().lowercase() to line.substring(colon + 1).trim()
        }.toMap()
        val bodyBytes = bytes.copyOfRange(minOf(bytes.size, split + if (text.startsWith("\r\n", split)) 4 else 2), bytes.size)
        val charset = headers["content-type"]?.let { Regex("charset=([\\w-]+)", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
            ?.let { runCatching { charset(it) }.getOrNull() } ?: Charsets.UTF_8
        return Response(status, headers, String(bodyBytes, charset))
    }

    private const val TIMEOUT_MS = 3_000
    private const val MAX_BYTES = 256 * 1024
}
