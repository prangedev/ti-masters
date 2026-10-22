package br.com.timasters.app.core.network

/** Accepts IPv4 addresses and DNS names; rejects anything that could be an option or command. */
object HostValidator {

    private val HOSTNAME = Regex(
        """^(?=.{1,253}$)[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?(\.[A-Za-z0-9]([A-Za-z0-9-]{0,61}[A-Za-z0-9])?)*$"""
    )

    fun normalize(input: String): String = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')

    fun isValid(input: String): Boolean {
        val host = normalize(input)
        if (host.isEmpty()) return false
        val looksNumeric = host.all { it.isDigit() || it == '.' }
        return if (looksNumeric) SubnetUtils.isValidIpv4(host) else HOSTNAME.matches(host)
    }

    /** 10.x, 172.16-31.x and 192.168.x: addresses that only exist inside a local network. */
    fun isPrivateIpv4(ip: String): Boolean {
        val value = SubnetUtils.ipv4ToInt(ip) ?: return false
        val first = (value ushr 24) and 0xFF
        val second = (value ushr 16) and 0xFF
        return first == 10 || (first == 172 && second in 16..31) || (first == 192 && second == 168)
    }
}
