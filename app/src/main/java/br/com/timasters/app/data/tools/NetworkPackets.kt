package br.com.timasters.app.data.tools

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.SecureRandom

// region DNS

enum class DnsType(val code: Int) { A(1), NS(2), CNAME(5), MX(15), TXT(16), AAAA(28) }

data class DnsRecord(val type: DnsType, val value: String, val ttl: Long)

/** rcode 0 = ok, 3 = NXDOMAIN (domain doesn't exist), 2 = server failure, 5 = refused. */
data class DnsAnswer(val rcode: Int, val records: List<DnsRecord>)

/** Minimal DNS wire format (RFC 1035) for lookups and server benchmarks over UDP. */
object DnsMessage {

    fun query(id: Int, name: String, type: DnsType): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteBuffer.allocate(12).putShort(id.toShort()).putShort(0x0100).putShort(1).putShort(0).putShort(0).putShort(0).array())
        name.trim().trimEnd('.').split('.').filter { it.isNotEmpty() }.forEach { label ->
            val bytes = label.toByteArray(Charsets.UTF_8)
            require(bytes.size in 1..63) { "label" }
            out.write(bytes.size)
            out.write(bytes)
        }
        out.write(0)
        out.write(ByteBuffer.allocate(4).putShort(type.code.toShort()).putShort(1).array())
        return out.toByteArray()
    }

    fun idOf(packet: ByteArray): Int = ((packet[0].toInt() and 0xFF) shl 8) or (packet[1].toInt() and 0xFF)

    fun parse(packet: ByteArray): DnsAnswer {
        val buffer = ByteBuffer.wrap(packet)
        buffer.position(2)
        val flags = buffer.short.toInt() and 0xFFFF
        val questions = buffer.short.toInt() and 0xFFFF
        val answers = buffer.short.toInt() and 0xFFFF
        buffer.position(12)
        repeat(questions) {
            readName(packet, buffer)
            buffer.position(buffer.position() + 4)
        }
        val records = mutableListOf<DnsRecord>()
        repeat(answers) {
            readName(packet, buffer)
            val type = buffer.short.toInt() and 0xFFFF
            buffer.short // class
            val ttl = buffer.int.toLong() and 0xFFFFFFFFL
            val length = buffer.short.toInt() and 0xFFFF
            val start = buffer.position()
            val known = DnsType.entries.firstOrNull { it.code == type }
            val value = when (known) {
                DnsType.A -> (0 until 4).joinToString(".") { (packet[start + it].toInt() and 0xFF).toString() }
                DnsType.AAAA -> (0 until 8).joinToString(":") {
                    Integer.toHexString(((packet[start + it * 2].toInt() and 0xFF) shl 8) or (packet[start + it * 2 + 1].toInt() and 0xFF))
                }
                DnsType.CNAME, DnsType.NS -> readName(packet, ByteBuffer.wrap(packet).position(start) as ByteBuffer)
                DnsType.MX -> {
                    val preference = ((packet[start].toInt() and 0xFF) shl 8) or (packet[start + 1].toInt() and 0xFF)
                    "$preference " + readName(packet, ByteBuffer.wrap(packet).position(start + 2) as ByteBuffer)
                }
                DnsType.TXT -> buildString {
                    var index = start
                    while (index < start + length) {
                        val size = packet[index].toInt() and 0xFF
                        append(String(packet, index + 1, size, Charsets.UTF_8))
                        index += size + 1
                    }
                }
                null -> null
            }
            if (known != null && value != null) records += DnsRecord(known, value, ttl)
            buffer.position(start + length)
        }
        return DnsAnswer(flags and 0x0F, records)
    }

    /** Reads a (possibly compressed) domain name and advances [buffer] past it. */
    private fun readName(packet: ByteArray, buffer: ByteBuffer): String {
        val labels = mutableListOf<String>()
        var position = buffer.position()
        var jumped = false
        var hops = 0
        while (true) {
            val length = packet[position].toInt() and 0xFF
            when {
                length == 0 -> {
                    position++
                    break
                }
                length and 0xC0 == 0xC0 -> {
                    val pointer = ((length and 0x3F) shl 8) or (packet[position + 1].toInt() and 0xFF)
                    if (!jumped) buffer.position(position + 2)
                    jumped = true
                    position = pointer
                    if (++hops > 20) break // malformed loop
                }
                else -> {
                    labels += String(packet, position + 1, length, Charsets.UTF_8)
                    position += length + 1
                }
            }
        }
        if (!jumped) buffer.position(position)
        return labels.joinToString(".")
    }
}

// endregion

// region Traceroute

sealed interface TraceLine {
    /** A router on the way answered "time to live exceeded". */
    data class Hop(val address: String) : TraceLine

    /** The destination itself answered. */
    data class Reached(val address: String, val timeMs: Double) : TraceLine

    data object Silent : TraceLine
}

/** Reads one `ping -c 1 -t <ttl>` run (iputils output on Android). */
object TracerouteParser {
    private val exceeded = Regex("""From ([0-9a-fA-F.:]+?)(?: \([^)]*\))?:? .*(?:Time to live exceeded|Hop limit)""", RegexOption.IGNORE_CASE)
    private val reply = Regex("""bytes from ([0-9a-fA-F.:]+?)(?: \([^)]*\))?:? .*time[=<]([\d.]+) ?ms""")

    fun parse(output: String): TraceLine {
        output.lineSequence().forEach { line ->
            reply.find(line)?.let { return TraceLine.Reached(it.groupValues[1], it.groupValues[2].toDouble()) }
            exceeded.find(line)?.let { return TraceLine.Hop(it.groupValues[1]) }
        }
        return TraceLine.Silent
    }
}

// endregion

// region Wake-on-LAN

object WakeOnLanPacket {
    /** "AA:BB:CC:DD:EE:FF", "aa-bb-cc-dd-ee-ff" or "aabbccddeeff" → 6 bytes. */
    fun parseMac(input: String): ByteArray? {
        val hex = input.trim().replace(":", "").replace("-", "").replace(".", "")
        if (!Regex("^[0-9a-fA-F]{12}$").matches(hex)) return null
        return ByteArray(6) { hex.substring(it * 2, it * 2 + 2).toInt(16).toByte() }
    }

    /** 6 × 0xFF followed by the MAC repeated 16 times. */
    fun build(mac: ByteArray): ByteArray {
        require(mac.size == 6)
        return ByteArray(6) { 0xFF.toByte() } + ByteArray(16 * 6) { mac[it % 6] }
    }
}

// endregion

// region Passwords and Wi-Fi QR

data class PasswordOptions(
    val length: Int = 16,
    val lowercase: Boolean = true,
    val uppercase: Boolean = true,
    val digits: Boolean = true,
    val symbols: Boolean = true,
    /** Leave out characters that are easy to confuse when read to a client: 0 O o 1 l I. */
    val avoidAmbiguous: Boolean = true
)

object PasswordTools {
    private const val LOWER = "abcdefghijklmnopqrstuvwxyz"
    private const val UPPER = "ABCDEFGHIJKLMNOPQRSTUVWXYZ"
    private const val DIGITS = "0123456789"
    private const val SYMBOLS = "!@#$%&*-_=+?"
    private const val AMBIGUOUS = "0Oo1lI"

    fun generate(options: PasswordOptions, random: SecureRandom = SecureRandom()): String {
        val sets = listOfNotNull(
            LOWER.takeIf { options.lowercase },
            UPPER.takeIf { options.uppercase },
            DIGITS.takeIf { options.digits },
            SYMBOLS.takeIf { options.symbols }
        ).map { set -> if (options.avoidAmbiguous) set.filterNot { it in AMBIGUOUS } else set }
        require(sets.isNotEmpty()) { "choose at least one character set" }
        val length = options.length.coerceIn(sets.size.coerceAtLeast(4), 64)
        val all = sets.joinToString("")
        // At least one of each chosen set, the rest from all, then shuffled.
        val chars = (sets.map { it[random.nextInt(it.length)] } + List(length - sets.size) { all[random.nextInt(all.length)] }).toMutableList()
        for (i in chars.indices.reversed()) {
            val j = random.nextInt(i + 1)
            chars[i] = chars[j].also { chars[j] = chars[i] }
        }
        return chars.joinToString("")
    }

    /** Rough strength in bits: length × log2(alphabet). 60+ is strong for Wi-Fi. */
    fun entropyBits(password: String): Double {
        var alphabet = 0
        if (password.any { it.isLowerCase() }) alphabet += 26
        if (password.any { it.isUpperCase() }) alphabet += 26
        if (password.any { it.isDigit() }) alphabet += 10
        if (password.any { !it.isLetterOrDigit() }) alphabet += 32
        return if (alphabet == 0) 0.0 else password.length * kotlin.math.ln(alphabet.toDouble()) / kotlin.math.ln(2.0)
    }

    /** Text of the standard Wi-Fi QR code (Android/iOS cameras join the network when scanning it). */
    fun wifiQr(ssid: String, password: String, hidden: Boolean = false): String {
        fun escape(value: String) = value.replace("\\", "\\\\").replace(";", "\\;").replace(",", "\\,").replace(":", "\\:").replace("\"", "\\\"")
        val type = if (password.isEmpty()) "nopass" else "WPA"
        val pass = if (password.isEmpty()) "" else "P:${escape(password)};"
        return "WIFI:T:$type;S:${escape(ssid)};$pass" + (if (hidden) "H:true;" else "") + ";"
    }
}

// endregion
