package br.com.timasters.app.core.network

object Ports {

    const val MAX_PORTS_PER_SCAN = 10_000

    /** TCP services a field technician usually meets, with a short pt-BR name. */
    val COMMON_SERVICES: Map<Int, String> = linkedMapOf(
        21 to "FTP",
        22 to "SSH",
        23 to "Telnet",
        25 to "SMTP",
        53 to "DNS",
        80 to "HTTP",
        110 to "POP3",
        135 to "RPC Windows",
        139 to "NetBIOS",
        143 to "IMAP",
        443 to "HTTPS",
        445 to "SMB (compartilhamento)",
        554 to "RTSP (câmeras)",
        631 to "IPP (impressora)",
        993 to "IMAPS",
        995 to "POP3S",
        1723 to "VPN PPTP",
        1883 to "MQTT",
        3306 to "MySQL",
        3389 to "Área de Trabalho Remota",
        5000 to "UPnP / NAS",
        5060 to "SIP (VoIP)",
        5432 to "PostgreSQL",
        5900 to "VNC",
        8080 to "HTTP alternativo",
        8291 to "MikroTik Winbox",
        8443 to "HTTPS alternativo",
        8728 to "MikroTik API",
        8729 to "MikroTik API-SSL",
        9100 to "Impressora (RAW)",
        37777 to "DVR Intelbras/Dahua"
    )

    fun serviceName(port: Int): String? = COMMON_SERVICES[port]

    /**
     * Parses "22, 80, 8000-8010" into a sorted list. Returns null when the text is invalid,
     * a port is outside 1..65535, or more than [MAX_PORTS_PER_SCAN] ports are requested.
     */
    fun parse(spec: String): List<Int>? {
        val result = sortedSetOf<Int>()
        val tokens = spec.split(',', ';', ' ').map { it.trim() }.filter { it.isNotEmpty() }
        if (tokens.isEmpty()) return null
        for (token in tokens) {
            val bounds = token.split('-').map { it.trim().toIntOrNull() ?: return null }
            val range = when (bounds.size) {
                1 -> bounds[0]..bounds[0]
                2 -> bounds[0]..bounds[1]
                else -> return null
            }
            if (range.first !in 1..65535 || range.last !in 1..65535 || range.first > range.last) return null
            if (range.last - range.first + 1 > MAX_PORTS_PER_SCAN) return null
            result.addAll(range)
            if (result.size > MAX_PORTS_PER_SCAN) return null
        }
        return result.toList()
    }
}
