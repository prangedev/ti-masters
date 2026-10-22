package br.com.timasters.app.data.tools

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.security.SecureRandom

class SubnetCalculatorTest {

    @Test
    fun calculate_classicSlash24AndSlash26() {
        val c = SubnetCalculator.calculate("192.168.1.130", 24)
        assertEquals("192.168.1.0", c.network)
        assertEquals("192.168.1.255", c.broadcast)
        assertEquals("255.255.255.0", c.mask)
        assertEquals("0.0.0.255", c.wildcard)
        assertEquals("192.168.1.1", c.firstHost)
        assertEquals("192.168.1.254", c.lastHost)
        assertEquals(254, c.usableHosts)
        assertTrue(c.isPrivate)

        val d = SubnetCalculator.calculate("10.0.0.77", 26)
        assertEquals("10.0.0.64/26", d.cidr)
        assertEquals("10.0.0.127", d.broadcast)
        assertEquals(62, d.usableHosts)
    }

    @Test
    fun calculate_edgePrefixes() {
        assertEquals(2, SubnetCalculator.calculate("200.1.1.1", 31).usableHosts)
        assertEquals("200.1.1.1", SubnetCalculator.calculate("200.1.1.1", 32).firstHost)
        assertFalse(SubnetCalculator.calculate("200.1.1.1", 24).isPrivate)
        assertTrue("CGNAT range", SubnetCalculator.calculate("100.64.10.1", 10).isPrivate)
        assertEquals(4294967296L, SubnetCalculator.calculate("8.8.8.8", 0).totalAddresses)
    }

    @Test
    fun parse_acceptsCidrMaskOrBareAddress() {
        assertEquals("192.168.0.10" to 22, SubnetCalculator.parse("192.168.0.10/22"))
        assertEquals("192.168.0.10" to 22, SubnetCalculator.parse("192.168.0.10 255.255.252.0"))
        assertEquals("192.168.0.10" to 24, SubnetCalculator.parse("192.168.0.10"))
        assertNull(SubnetCalculator.parse("192.168.0.10 255.0.255.0"))
        assertNull(SubnetCalculator.parse("abc"))
    }

    @Test
    fun split_roundsUpToPowerOfTwo() {
        val parts = SubnetCalculator.split("192.168.10.0", 24, 3)!!
        assertEquals(4, parts.size)
        assertEquals(listOf("192.168.10.0/26", "192.168.10.64/26", "192.168.10.128/26", "192.168.10.192/26"), parts.map { it.cidr })
        assertNull(SubnetCalculator.split("192.168.10.0", 29, 4))
    }

    @Test
    fun vlsm_allocatesBiggestFirstAndDetectsOverflow() {
        val result = SubnetCalculator.vlsm(
            "192.168.0.0", 24,
            listOf(VlsmRequest("Escritório", 50), VlsmRequest("Câmeras", 100), VlsmRequest("Link", 2))
        )!!
        assertEquals(listOf("Câmeras", "Escritório", "Link"), result.map { it.request.name })
        assertEquals(listOf("192.168.0.0/25", "192.168.0.128/26", "192.168.0.192/30"), result.map { it.subnet.cidr })
        assertNull(SubnetCalculator.vlsm("192.168.0.0", 24, listOf(VlsmRequest("A", 200), VlsmRequest("B", 100))))
    }
}

class FieldCalculatorsTest {

    @Test
    fun fiberBudget_typicalFtthDrop() {
        // 5 km at 1490 nm, 1:8 + 1:8 splitters, 4 connectors, 2 splices, +3 dBm OLT.
        val result = FiberBudget.calculate(
            FiberBudgetInput(txDbm = 3.0, lengthKm = 5.0, connectors = 4, splices = 2, splitters = listOf(Splitter.S1X8, Splitter.S1X8))
        )
        assertEquals(1.25, result.fiberLoss, 0.001)
        assertEquals(2.0, result.connectorsLoss, 0.001)
        assertEquals(0.2, result.splicesLoss, 0.001)
        assertEquals(21.0, result.splittersLoss, 0.001)
        assertEquals(-21.45, result.expectedRxDbm, 0.001)
        assertEquals(5.55, result.marginDb, 0.001)
        assertEquals(BudgetStatus.OK, result.status)
    }

    @Test
    fun fiberBudget_flagsLimitFailAndOverload() {
        // 16 km (4.0) + 1:64 (20.5) + 6 connectors (3.0) = 27.5 dB → -24.5 dBm, margin 2.5 dB.
        assertEquals(BudgetStatus.AT_LIMIT, FiberBudget.calculate(FiberBudgetInput(lengthKm = 16.0, splitters = listOf(Splitter.S1X64), connectors = 6)).status)
        // 20 km (5.0) + 1:64 + 1:2 (24.2) + 6 connectors (3.0) = 32.2 dB → -29.2 dBm, below -27.
        assertEquals(BudgetStatus.FAIL, FiberBudget.calculate(FiberBudgetInput(lengthKm = 20.0, splitters = listOf(Splitter.S1X64, Splitter.S1X2), connectors = 6)).status)
        assertEquals(BudgetStatus.OVERLOAD, FiberBudget.calculate(FiberBudgetInput(lengthKm = 0.5, connectors = 1)).status)
    }

    @Test
    fun radioLink_5GhzTenKilometers() {
        val result = RadioLink.calculate(RadioLinkInput(5.8, 10.0, txPowerDbm = 20.0, txAntennaDbi = 23.0, rxAntennaDbi = 23.0, rxSensitivityDbm = -80.0))
        assertEquals(127.71, result.freeSpaceLossDb, 0.05)
        assertEquals(-61.71, result.expectedRssiDbm, 0.05)
        assertEquals(18.29, result.fadeMarginDb, 0.05)
        assertEquals(11.37, result.fresnelRadiusM, 0.05)
        assertEquals(1.47, result.earthBulgeM, 0.02)
        assertEquals(result.fresnelClearanceM + result.earthBulgeM, result.minClearanceM, 0.0001)
    }

    @Test
    fun cctv_eightCameras1080pH265ThirtyDays() {
        val result = CctvStorage.calculate(CctvInput(cameras = 8, bitrateMbps = 2.0, days = 30))
        // 16 Mbps × 86400 s / 8 = 172.8 GB/day → 5.184 TB in 30 days.
        assertEquals(172.8, result.dailyGb, 0.01)
        assertEquals(5.184, result.requiredTb, 0.001)
        assertEquals(6, result.suggestedDiskTb)
        assertEquals(30.0 * 0.93 * 4 / 5.184, CctvStorage.daysFor(4.0, CctvInput(8, 2.0)), 0.01)
        assertNull(CctvStorage.calculate(CctvInput(cameras = 64, bitrateMbps = 8.0, days = 90)).suggestedDiskTb)
    }

    @Test
    fun poe_budgetAndStandard() {
        val result = PoeBudget.calculate(120.0, listOf(PoeDevice("Câmera IP", 8, 6.5), PoeDevice("AP", 2, 12.0)))
        assertEquals(76.0, result.totalWatts, 0.001)
        assertTrue(result.fits)
        assertTrue(result.comfortable)
        assertFalse(PoeBudget.calculate(60.0, listOf(PoeDevice("PTZ", 2, 25.0))).comfortable)
        assertEquals(PoeStandard.AF, PoeBudget.standardFor(6.5))
        assertEquals(PoeStandard.AT, PoeBudget.standardFor(20.0))
        assertNull(PoeBudget.standardFor(100.0))
    }

    @Test
    fun ups_runtime() {
        // 2 × 12 V 7 Ah, 85% inverter, 80% usable → 114.24 Wh; 60 W load → 114 min.
        val result = UpsRuntime.calculate(UpsInput(loadWatts = 60.0, batteryAh = 7.0, batteries = 2, upsCapacityVa = 600.0))
        assertEquals(114.24, result.runtimeMinutes, 0.01)
        assertEquals(16.67, result.loadPercent!!, 0.01)
        assertTrue(UpsRuntime.calculate(UpsInput(loadWatts = 500.0, batteryAh = 7.0, upsCapacityVa = 600.0)).overloaded)
    }

    @Test
    fun converters() {
        assertEquals(1.0, Converters.dbmToMw(0.0), 1e-9)
        assertEquals(100.0, Converters.dbmToMw(20.0), 1e-9)
        assertEquals(-30.0, Converters.mwToDbm(0.001), 1e-9)
        assertEquals(12.5, Converters.mbpsToMegabytesPerSecond(100.0), 1e-9)
        assertEquals(80.0, Converters.transferSeconds(1.0, 100.0), 1e-9)
    }
}

class NetworkPacketsTest {

    @Test
    fun dns_queryHasHeaderQuestionAndType() {
        val packet = DnsMessage.query(0x1234, "www.example.com", DnsType.MX)
        assertEquals(0x1234, DnsMessage.idOf(packet))
        assertEquals(0x01, packet[2].toInt()) // recursion desired
        val name = byteArrayOf(3) + "www".toByteArray() + byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0)
        assertArrayEquals(name, packet.copyOfRange(12, 12 + name.size))
        assertEquals(15, ByteBuffer.wrap(packet, 12 + name.size, 2).short.toInt())
    }

    @Test
    fun dns_parsesCompressedAnswers() {
        // Response for example.com: CNAME (compressed) + A + MX.
        val question = byteArrayOf(7) + "example".toByteArray() + byteArrayOf(3) + "com".toByteArray() + byteArrayOf(0, 0, 1, 0, 1)
        fun answer(type: Int, rdata: ByteArray) =
            byteArrayOf(0xC0.toByte(), 12) + ByteBuffer.allocate(10).putShort(type.toShort()).putShort(1).putInt(300).putShort(rdata.size.toShort()).array() + rdata
        val cname = byteArrayOf(3) + "www".toByteArray() + byteArrayOf(0xC0.toByte(), 12)
        val a = byteArrayOf(93.toByte(), 184.toByte(), 216.toByte(), 34)
        val mx = byteArrayOf(0, 10, 4) + "mail".toByteArray() + byteArrayOf(0xC0.toByte(), 12)
        val header = ByteBuffer.allocate(12).putShort(7).putShort(0x8180.toShort()).putShort(1).putShort(3).putShort(0).putShort(0).array()
        val packet = header + question + answer(5, cname) + answer(1, a) + answer(15, mx)

        val result = DnsMessage.parse(packet)

        assertEquals(0, result.rcode)
        assertEquals(listOf("www.example.com", "93.184.216.34", "10 mail.example.com"), result.records.map { it.value })
        assertEquals(300L, result.records.first().ttl)
    }

    @Test
    fun dns_reportsNxdomain() {
        val header = ByteBuffer.allocate(12).putShort(1).putShort(0x8183.toShort()).putShort(0).putShort(0).putShort(0).putShort(0).array()
        assertEquals(3, DnsMessage.parse(header).rcode)
    }

    @Test
    fun traceroute_parsesIputilsOutput() {
        val hop = "PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data.\nFrom 192.168.0.1 icmp_seq=1 Time to live exceeded\n"
        assertEquals(TraceLine.Hop("192.168.0.1"), TracerouteParser.parse(hop))
        val reached = "64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=14.2 ms"
        assertEquals(TraceLine.Reached("8.8.8.8", 14.2), TracerouteParser.parse(reached))
        val named = "From 100.64.0.1 (100.64.0.1): icmp_seq=1 Time to live exceeded"
        assertEquals(TraceLine.Hop("100.64.0.1"), TracerouteParser.parse(named))
        assertEquals(TraceLine.Silent, TracerouteParser.parse("1 packets transmitted, 0 received, 100% packet loss"))
        assertEquals(TraceLine.Reached("2001:4860:4860::8888", 20.0), TracerouteParser.parse("64 bytes from 2001:4860:4860::8888: icmp_seq=1 ttl=117 time=20.0 ms"))
    }

    @Test
    fun wakeOnLan_packet() {
        val mac = WakeOnLanPacket.parseMac("AA-bb-CC:dd.EE:ff")!!
        val packet = WakeOnLanPacket.build(mac)
        assertEquals(102, packet.size)
        assertTrue(packet.take(6).all { it == 0xFF.toByte() })
        assertArrayEquals(mac, packet.copyOfRange(96, 102))
        assertNull(WakeOnLanPacket.parseMac("AA:BB:CC:DD:EE"))
        assertNull(WakeOnLanPacket.parseMac("GG:BB:CC:DD:EE:FF"))
    }

    @Test
    fun passwords_respectOptions() {
        val random = SecureRandom()
        repeat(50) {
            val password = PasswordTools.generate(PasswordOptions(length = 12), random)
            assertEquals(12, password.length)
            assertTrue(password.any { it.isLowerCase() } && password.any { it.isUpperCase() } && password.any { it.isDigit() } && password.any { !it.isLetterOrDigit() })
            assertFalse(password.any { it in "0Oo1lI" })
        }
        val digits = PasswordTools.generate(PasswordOptions(length = 8, lowercase = false, uppercase = false, symbols = false, avoidAmbiguous = false))
        assertTrue(digits.all { it.isDigit() })
        assertTrue(PasswordTools.entropyBits("Ab3!Ab3!Ab3!Ab3!") > 90)
    }

    @Test
    fun wifiQr_escapesSpecialCharacters() {
        assertEquals("WIFI:T:WPA;S:Casa;P:senha123;;", PasswordTools.wifiQr("Casa", "senha123"))
        assertEquals("WIFI:T:WPA;S:Loja\\;Centro;P:a\\:b\\\\c;H:true;;", PasswordTools.wifiQr("Loja;Centro", "a:b\\c", hidden = true))
        assertEquals("WIFI:T:nopass;S:Visitantes;;", PasswordTools.wifiQr("Visitantes", ""))
    }
}
