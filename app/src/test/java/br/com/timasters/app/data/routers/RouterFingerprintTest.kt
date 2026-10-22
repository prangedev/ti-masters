package br.com.timasters.app.data.routers

import br.com.timasters.app.core.network.HostValidator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class RouterFingerprintTest {

    @Test
    fun parseHttp_readsRealmTitleServerAndFirmware() {
        val headers = mapOf(
            "server" to "Router Webserver",
            "www-authenticate" to "Basic realm=\"TP-LINK Wireless N Router WR841N\""
        )
        val body = """
            <html><head><title> Archer &amp; C6 </title><script>var x = "ignored";</script></head>
            <body>Firmware Version: 3.16.9 Build 150<br></body></html>
        """.trimIndent()

        val result = RouterFingerprintParser.parseHttp(headers, body)

        assertEquals("Router Webserver", result.server)
        assertEquals("TP-LINK Wireless N Router WR841N", result.realm)
        assertEquals("Archer & C6", result.title)
        assertEquals("3.16.9 Build 150", result.firmware)
        assertFalse(result.snippet.contains("ignored"))
    }

    @Test
    fun findFirmware_understandsRouterOsAndScriptVariables() {
        assertEquals("7.14.2", RouterFingerprintParser.findFirmware("<h1>RouterOS v7.14.2</h1>"))
        assertEquals("1.0.5", RouterFingerprintParser.findFirmware("var fw_ver = '1.0.5';"))
        assertNull(RouterFingerprintParser.findFirmware("<html>Login</html>"))
    }

    @Test
    fun parseUpnp_readsDeviceDescription() {
        val xml = """
            <root><device>
              <friendlyName>Roteador Sala</friendlyName>
              <manufacturer>Intelbras</manufacturer>
              <modelName>Twibi Giga</modelName>
              <modelNumber>1.0</modelNumber>
              <softwareVersion>2.3.4</softwareVersion>
            </device></root>
        """.trimIndent()

        val info = RouterFingerprintParser.parseUpnp(xml)

        assertEquals("Roteador Sala", info.friendlyName)
        assertEquals("Intelbras", info.manufacturer)
        assertEquals("Twibi Giga", info.modelName)
        assertEquals("2.3.4", info.softwareVersion)
    }

    @Test
    fun ssdpLocation_isCaseInsensitive() {
        val reply = "HTTP/1.1 200 OK\r\nCACHE-CONTROL: max-age=120\r\nLocation: http://192.168.0.1:1900/igd.xml\r\n\r\n"
        assertEquals("http://192.168.0.1:1900/igd.xml", RouterFingerprintParser.ssdpLocation(reply))
        assertNull(RouterFingerprintParser.ssdpLocation("HTTP/1.1 200 OK\r\n\r\n"))
    }

    @Test
    fun parseMndp_readsIdentityVersionBoardAndAddress() {
        val packet = mndpPacket(
            1 to byteArrayOf(0x4C, 0x5E, 0x0C, 0x01, 0x02, 0x03),
            5 to "MikroTik-Escritorio".toByteArray(),
            7 to "7.14.2 (stable)".toByteArray(),
            8 to "MikroTik".toByteArray(),
            12 to "hEX".toByteArray(),
            17 to byteArrayOf(192.toByte(), 168.toByte(), 88, 1)
        )

        val info = RouterFingerprintParser.parseMndp(packet)!!

        assertEquals("4C:5E:0C:01:02:03", info.mac)
        assertEquals("MikroTik-Escritorio", info.identity)
        assertEquals("7.14.2", info.versionNumber)
        assertEquals("hEX", info.board)
        assertEquals("192.168.88.1", info.ipv4)
    }

    @Test
    fun parseMndp_rejectsEmptyOrTruncatedPackets() {
        assertNull(RouterFingerprintParser.parseMndp(ByteArray(4)))
        // Only a MAC: not enough to call it a RouterOS announcement.
        assertNull(RouterFingerprintParser.parseMndp(mndpPacket(1 to ByteArray(6))))
        // Declared length larger than the packet.
        val truncated = mndpPacket(5 to "abc".toByteArray()).copyOf(9)
        assertNull(RouterFingerprintParser.parseMndp(truncated))
    }

    @Test
    fun routerOsVersions_compareAndChannel() {
        assertTrue(RouterOsVersions.compare("7.14.2", "7.16") < 0)
        assertTrue(RouterOsVersions.compare("7.16", "7.16") == 0)
        assertTrue(RouterOsVersions.compare("7.16rc1", "7.16") < 0)
        assertTrue(RouterOsVersions.compare("6.49.10", "6.49.8") > 0)
        assertEquals("NEWEST6.long-term", RouterOsVersions.channelFor("6.49.10"))
        assertEquals("NEWESTa7.stable", RouterOsVersions.channelFor("7.14.2"))
        assertEquals("7.20.1", RouterOsVersions.parseNewest("7.20.1 1790691687\n"))
        assertNull(RouterOsVersions.parseNewest("<html>error</html>"))
    }

    @Test
    fun lanHttp_parsesStatusHeadersAndBody() {
        val raw = "HTTP/1.1 302 Found\r\nLocation: /login.html\r\nContent-Type: text/html; charset=utf-8\r\n\r\nolá".toByteArray()

        val response = LanHttp.parse(raw)!!

        assertEquals(302, response.status)
        assertEquals("/login.html", response.headers["location"])
        assertEquals("olá", response.body)
        assertNull(LanHttp.parse("garbage".toByteArray()))
    }

    @Test
    fun privateIpv4_onlyAcceptsLocalRanges() {
        assertTrue(HostValidator.isPrivateIpv4("192.168.0.1"))
        assertTrue(HostValidator.isPrivateIpv4("10.0.0.1"))
        assertTrue(HostValidator.isPrivateIpv4("172.20.1.1"))
        assertFalse(HostValidator.isPrivateIpv4("172.32.0.1"))
        assertFalse(HostValidator.isPrivateIpv4("8.8.8.8"))
        assertFalse(HostValidator.isPrivateIpv4("router.local"))
    }

    private fun mndpPacket(vararg tlvs: Pair<Int, ByteArray>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write(ByteArray(4))
        tlvs.forEach { (type, value) ->
            out.write(ByteBuffer.allocate(4).putShort(type.toShort()).putShort(value.size.toShort()).array())
            out.write(value)
        }
        return out.toByteArray()
    }
}
