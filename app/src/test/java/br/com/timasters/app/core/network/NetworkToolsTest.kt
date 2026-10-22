package br.com.timasters.app.core.network

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkToolsTest {

    @Test
    fun wifiChannels_forEachBand() {
        assertEquals(1, WifiMath.channelOf(2412))
        assertEquals(6, WifiMath.channelOf(2437))
        assertEquals(14, WifiMath.channelOf(2484))
        assertEquals(36, WifiMath.channelOf(5180))
        assertEquals(149, WifiMath.channelOf(5745))
        assertEquals(1, WifiMath.channelOf(5955))
        assertNull(WifiMath.channelOf(900))
        assertEquals(WifiBand.GHZ_5, WifiMath.bandOf(5500))
        assertEquals(WifiBand.GHZ_6, WifiMath.bandOf(6115))
    }

    @Test
    fun signalQualityAndPercent() {
        assertEquals(SignalQuality.EXCELLENT, WifiMath.signalQuality(-45))
        assertEquals(SignalQuality.GOOD, WifiMath.signalQuality(-60))
        assertEquals(SignalQuality.FAIR, WifiMath.signalQuality(-68))
        assertEquals(SignalQuality.WEAK, WifiMath.signalQuality(-79))
        assertEquals(SignalQuality.VERY_WEAK, WifiMath.signalQuality(-90))
        assertEquals(100, WifiMath.signalPercent(-20))
        assertEquals(50, WifiMath.signalPercent(-65))
        assertEquals(0, WifiMath.signalPercent(-110))
    }

    @Test
    fun subnet_listsHostsAndNarrowsLargeNetworks() {
        val hosts = SubnetUtils.hostsToScan("192.168.0.57", 24)
        assertEquals(254, hosts.size)
        assertEquals("192.168.0.1", hosts.first())
        assertEquals("192.168.0.254", hosts.last())

        val narrowed = SubnetUtils.hostsToScan("10.20.30.40", 16)
        assertEquals("10.20.30.1", narrowed.first())
        assertEquals(254, narrowed.size)

        assertEquals(listOf("192.168.1.1", "192.168.1.2"), SubnetUtils.hostsToScan("192.168.1.2", 30))
        assertTrue(SubnetUtils.hostsToScan("999.1.1.1", 24).isEmpty())
        assertFalse(SubnetUtils.isValidIpv4("192.168.1"))
    }

    @Test
    fun ports_parseListsAndRanges() {
        assertEquals(listOf(22, 80, 443, 8000, 8001, 8002), Ports.parse("443, 22,80 8000-8002"))
        assertNull(Ports.parse(""))
        assertNull(Ports.parse("0"))
        assertNull(Ports.parse("70000"))
        assertNull(Ports.parse("100-10"))
        assertNull(Ports.parse("abc"))
        assertNull(Ports.parse("1-65535"))
        assertEquals("MikroTik Winbox", Ports.serviceName(8291))
    }

    @Test
    fun latencyStats_lossJitterAndThroughput() {
        val stats = LatencyStats.from(sent = 5, timesMs = listOf(10.0, 14.0, 12.0, 16.0))
        assertEquals(20.0, stats.lossPercent, 0.001)
        assertEquals(10.0, stats.minMs!!, 0.001)
        assertEquals(13.0, stats.avgMs!!, 0.001)
        assertEquals(16.0, stats.maxMs!!, 0.001)
        assertEquals(10.0 / 3, stats.jitterMs!!, 0.001)
        assertEquals(12.0, LatencyStats.median(listOf(15.0, 10.0, 12.0))!!, 0.001)
        assertEquals(100.0, LatencyStats.mbps(bytes = 12_500_000, nanos = 1_000_000_000), 0.001)
    }

    @Test
    fun pingParser_readsAndroidOutput() {
        assertEquals(
            12.3,
            PingOutputParser.parseReplyTime("64 bytes from 8.8.8.8: icmp_seq=1 ttl=117 time=12.3 ms")!!,
            0.001
        )
        assertNull(PingOutputParser.parseReplyTime("PING 8.8.8.8 (8.8.8.8) 56(84) bytes of data."))
    }
}

class HostAndSecurityTest {

    @Test
    fun hostValidator_acceptsHostsAndRejectsInjection() {
        assertTrue(HostValidator.isValid("8.8.8.8"))
        assertTrue(HostValidator.isValid("google.com.br"))
        assertTrue(HostValidator.isValid(" https://router.local/ "))
        assertFalse(HostValidator.isValid("256.1.1.1"))
        assertFalse(HostValidator.isValid("-c 1000 8.8.8.8"))
        assertFalse(HostValidator.isValid("8.8.8.8; rm -rf /"))
        assertFalse(HostValidator.isValid(""))
    }

    @Test
    fun wifiSecurityAndWidth() {
        assertEquals("WPA3", WifiMath.securityOf("[RSN-SAE-CCMP][ESS]"))
        assertEquals("WPA2", WifiMath.securityOf("[WPA2-PSK-CCMP][ESS]"))
        assertEquals(WifiMath.OPEN_NETWORK, WifiMath.securityOf("[ESS]"))
        assertEquals(80, WifiMath.channelWidthMhz(2))
        assertEquals("Wi-Fi 6 (802.11ax)", WifiMath.standardName(6))
    }
}

class ChannelAdvisorTest {

    @Test
    fun recommends_leastBusyChannel_in24GHz() {
        val networks = listOf(
            SpectrumEntry(centerMhz = 2412, widthMhz = 20, rssiDbm = -45),
            SpectrumEntry(centerMhz = 2437, widthMhz = 20, rssiDbm = -55),
            SpectrumEntry(centerMhz = 2462, widthMhz = 20, rssiDbm = -85)
        )
        assertEquals(11, ChannelAdvisor.recommend(WifiBand.GHZ_2_4, networks)?.channel)
    }

    @Test
    fun wideChannel_countsOnEveryChannelItCovers() {
        // 80 MHz centered on 5210 covers channels 36-48.
        val networks = listOf(SpectrumEntry(centerMhz = 5210, widthMhz = 80, rssiDbm = -40))
        val usage = ChannelAdvisor.usage(WifiBand.GHZ_5, networks).associate { it.channel to it.networks }
        assertEquals(1, usage[36])
        assertEquals(1, usage[48])
        assertEquals(0, usage[149])
        assertEquals(149, ChannelAdvisor.recommend(WifiBand.GHZ_5, networks)?.channel)
    }
}

class CloudflareTraceTest {

    @Test
    fun parseTrace_readsKeyValueLines() {
        val trace = br.com.timasters.app.data.network.CloudflareSpeedApi.parseTrace(
            "fl=586f9\nip=203.0.113.7\ncolo=GRU\nloc=BR\nuag=curl/8"
        )
        assertEquals("203.0.113.7", trace["ip"])
        assertEquals("GRU", trace["colo"])
        assertEquals("BR", trace["loc"])
    }
}
