package br.com.timasters.app.data.routers

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** What the router's web page says about itself (no login needed). */
data class HttpFingerprint(
    val server: String? = null,
    /** Basic-auth realm; older routers put the model there ("TP-LINK Wireless N Router WR841N"). */
    val realm: String? = null,
    val title: String? = null,
    val firmware: String? = null,
    /** Short readable excerpt of the page, used only for brand matching. */
    val snippet: String = ""
)

/** UPnP device description (Internet Gateway Device), served by most home routers. */
data class UpnpInfo(
    val friendlyName: String? = null,
    val manufacturer: String? = null,
    val modelName: String? = null,
    val modelNumber: String? = null,
    val modelDescription: String? = null,
    val softwareVersion: String? = null
)

/** MikroTik Neighbor Discovery reply: RouterOS answers without login. */
data class MndpInfo(
    val identity: String? = null,
    val version: String? = null,
    val platform: String? = null,
    val board: String? = null,
    val mac: String? = null,
    val ipv4: String? = null
) {
    /** "7.14.2 (stable)" → "7.14.2". */
    val versionNumber: String? get() = version?.trim()?.substringBefore(' ')?.ifBlank { null }
}

object RouterFingerprintParser {

    fun parseHttp(headers: Map<String, String>, body: String): HttpFingerprint {
        val realm = headers["www-authenticate"]?.let { Regex("realm=\"([^\"]*)\"", RegexOption.IGNORE_CASE).find(it)?.groupValues?.get(1) }
        val title = Regex("<title[^>]*>(.*?)</title>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL))
            .find(body)?.groupValues?.get(1)?.let(::cleanText)?.ifBlank { null }
        val text = cleanText(body.replace(Regex("<(script|style)[^>]*>.*?</\\1>", setOf(RegexOption.IGNORE_CASE, RegexOption.DOT_MATCHES_ALL)), " "))
        return HttpFingerprint(
            server = headers["server"]?.trim()?.ifBlank { null },
            realm = realm?.trim()?.ifBlank { null },
            title = title,
            firmware = findFirmware(body),
            snippet = text.take(SNIPPET_LENGTH)
        )
    }

    /**
     * Firmware versions some pages print before login: "Firmware Version: 3.16.9 Build 150",
     * "RouterOS v7.14.2", "fw_ver = '1.0.5'", "SoftwareVersion": "V5R020C10".
     */
    fun findFirmware(body: String): String? {
        val patterns = listOf(
            Regex("RouterOS\\s+v?(\\d+\\.\\d+(?:\\.\\d+)?(?:\\w+)?)", RegexOption.IGNORE_CASE),
            Regex("firmware\\s*(?:version|ver\\.?)?\\s*[:=]\\s*['\"]?\\s*([A-Za-z]?\\d[\\w.\\- ]{1,40}?)\\s*['\"<,;\\n]", RegexOption.IGNORE_CASE),
            Regex("(?:fw_?ver|firmware_?version|soft(?:ware)?_?ver(?:sion)?)\\W{1,4}([A-Za-z]?\\d[\\w.\\-]{1,30})", RegexOption.IGNORE_CASE)
        )
        return patterns.firstNotNullOfOrNull { it.find(body)?.groupValues?.get(1)?.trim()?.ifBlank { null } }
    }

    fun parseUpnp(xml: String): UpnpInfo {
        fun tag(name: String) = Regex("<$name>\\s*([^<]*?)\\s*</$name>", RegexOption.IGNORE_CASE).find(xml)
            ?.groupValues?.get(1)?.let(::decodeEntities)?.ifBlank { null }
        return UpnpInfo(
            friendlyName = tag("friendlyName"),
            manufacturer = tag("manufacturer"),
            modelName = tag("modelName"),
            modelNumber = tag("modelNumber"),
            modelDescription = tag("modelDescription"),
            softwareVersion = tag("softwareVersion") ?: tag("firmwareVersion")
        )
    }

    /** "LOCATION: http://192.168.0.1:1900/igd.xml" from an SSDP reply. */
    fun ssdpLocation(reply: String): String? =
        reply.lineSequence().firstOrNull { it.startsWith("location:", ignoreCase = true) }?.substringAfter(':')?.trim()?.ifBlank { null }

    /**
     * MNDP packet: 4-byte header, then TLVs (type and length as big-endian 16-bit numbers).
     * Types: 1 MAC, 5 identity, 7 version, 8 platform, 12 board, 17 IPv4.
     */
    fun parseMndp(packet: ByteArray): MndpInfo? {
        if (packet.size < 8) return null
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        buffer.position(4)
        var info = MndpInfo()
        var found = false
        while (buffer.remaining() >= 4) {
            val type = buffer.short.toInt() and 0xFFFF
            val length = buffer.short.toInt() and 0xFFFF
            if (length > buffer.remaining()) break
            val value = ByteArray(length).also { buffer.get(it) }
            fun text() = String(value, Charsets.UTF_8).trim().ifBlank { null }
            info = when (type) {
                1 -> info.copy(mac = value.joinToString(":") { "%02X".format(it) }.takeIf { length == 6 })
                5 -> info.copy(identity = text())
                7 -> info.copy(version = text())
                8 -> info.copy(platform = text())
                12 -> info.copy(board = text())
                17 -> info.copy(ipv4 = value.takeIf { length == 4 }?.joinToString(".") { (it.toInt() and 0xFF).toString() })
                else -> info
            }
            found = found || type in setOf(5, 7, 8, 12)
        }
        return info.takeIf { found }
    }

    private fun cleanText(html: String): String =
        decodeEntities(html.replace(Regex("<[^>]+>"), " ")).replace(Regex("\\s+"), " ").trim()

    private fun decodeEntities(text: String): String = text
        .replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&nbsp;", " ")

    private const val SNIPPET_LENGTH = 400
}

object RouterOsVersions {

    /** Negative when [a] is older than [b]. "7.14.2" vs "7.16" → negative. Suffixes like "rc1" count as older. */
    fun compare(a: String, b: String): Int {
        val left = parts(a)
        val right = parts(b)
        for (i in 0 until maxOf(left.size, right.size)) {
            val diff = left.getOrElse(i) { 0 } - right.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        val leftPre = a.any { it.isLetter() }
        val rightPre = b.any { it.isLetter() }
        return when {
            leftPre && !rightPre -> -1
            !leftPre && rightPre -> 1
            else -> 0
        }
    }

    /** The update channel matching the installed major version. */
    fun channelFor(installed: String): String = if (installed.trim().startsWith("6")) "NEWEST6.long-term" else "NEWESTa7.stable"

    /** "7.24.5 1790691687" → "7.24.5". */
    fun parseNewest(body: String): String? = body.trim().substringBefore(' ').takeIf { it.firstOrNull()?.isDigit() == true }

    private fun parts(version: String): List<Int> =
        version.trim().split('.').map { part -> part.takeWhile { it.isDigit() }.toIntOrNull() ?: 0 }
}
