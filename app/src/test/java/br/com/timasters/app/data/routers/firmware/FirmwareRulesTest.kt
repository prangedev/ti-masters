package br.com.timasters.app.data.routers.firmware

import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FirmwareRulesTest {

    private val json = Json { ignoreUnknownKeys = true }

    /** Trimmed from downloads.openwrt.org/releases/25.12.6/targets/ath79/generic/profiles.json. */
    private val profiles = json.decodeFromString<TargetProfiles>(
        """
        {"version_number":"25.12.6","target":"ath79/generic","profiles":{
          "tplink_archer-c6-v2":{"supported_devices":["tplink,archer-c6-v2"],
            "titles":[{"model":"Archer C6","variant":"v2 (EU/RU/JP)","vendor":"TP-Link"}],
            "file_size_limits":{"image":7995392},
            "images":[{"name":"a-sysupgrade.bin","type":"sysupgrade","sha256":"aa","size":7602455},
                      {"name":"a-factory.bin","type":"factory","sha256":"bb","size":7609292}]},
          "tplink_archer-c6-v2-us":{"supported_devices":["tplink,archer-c6-v2-us","tplink,archer-a6-v2-us"],
            "titles":[{"model":"Archer C6","variant":"v2 (US)","vendor":"TP-Link"}],"images":[]}
        }}
        """.trimIndent()
    )

    @Test
    fun profileForBoard_matchesExactHardwareRevisionOnly() {
        assertEquals("tplink_archer-c6-v2", FirmwareRules.profileForBoard(profiles, "tplink,archer-c6-v2")!!.first)
        assertEquals("tplink_archer-c6-v2-us", FirmwareRules.profileForBoard(profiles, "tplink,archer-a6-v2-us")!!.first)
        assertNull(FirmwareRules.profileForBoard(profiles, "tplink,archer-c6-v3"))
        assertNull(FirmwareRules.profileForBoard(profiles, ""))
    }

    @Test
    fun search_needsEveryWord() {
        val overview = listOf(
            OverviewProfile("tplink_archer-c6-v3", "ramips/mt7621", listOf(DeviceTitle("TP-Link", "Archer C6", "V3"))),
            OverviewProfile("tplink_archer-c60-v1", "ath79/generic", listOf(DeviceTitle("TP-Link", "Archer C60", "v1"))),
            OverviewProfile("glinet_gl-mt3000", "mediatek/filogic", listOf(DeviceTitle(title = "GL.iNet GL-MT3000")))
        )
        assertEquals(2, FirmwareRules.search(overview, "archer c6").size)
        assertEquals(listOf("tplink_archer-c6-v3"), FirmwareRules.search(overview, "tp-link c6 v3").map { it.id })
        assertEquals(listOf("glinet_gl-mt3000"), FirmwareRules.search(overview, "mt3000").map { it.id })
        assertTrue(FirmwareRules.search(overview, "  ").isEmpty())
    }

    @Test
    fun packages_keepUserPackagesAndApplyRemovals() {
        val result = FirmwareRules.packages(
            installed = listOf("luci", "wpad-basic-mbedtls", "tcpdump"),
            presets = listOf(PackagePreset.LUCI_PT_BR, PackagePreset.MESH)
        )
        assertEquals(listOf("luci", "tcpdump", "luci-i18n-base-pt-br", "wpad-mesh-mbedtls", "-wpad-basic-mbedtls"), result)
    }

    @Test
    fun pickImage_neverOffersSysupgradeForFirstInstall() {
        val images = profiles.profiles.getValue("tplink_archer-c6-v2").images
        assertEquals("factory", FirmwareRules.pickImage(images, firstInstall = true)!!.type)
        assertEquals("sysupgrade", FirmwareRules.pickImage(images, firstInstall = false)!!.type)
        assertNull(FirmwareRules.pickImage(images.filter { it.type == "sysupgrade" }, firstInstall = true))
    }

    @Test
    fun fitsInMemory_keepsSafetyMargin() {
        assertTrue(FirmwareRules.fitsInMemory(8_000_000, 20_000_000))
        assertFalse(FirmwareRules.fitsInMemory(8_000_000, 10_000_000))
        assertTrue("unknown memory doesn't block", FirmwareRules.fitsInMemory(8_000_000, null))
    }

    @Test
    fun defaultsScript_quotesValuesSoTheyCantInjectCommands() {
        val script = FirmwareRules.defaultsScript(
            FirstBootSettings(hostname = "Cliente-01", lanIp = "192.168.10.1", wifiName = "Casa'; reboot; '", wifiPassword = "senha forte 1")
        )
        assertTrue(script.startsWith("#!/bin/sh"))
        assertTrue(script.contains("hostname='Cliente-01'"))
        assertTrue(script.contains("timezone='<-03>3'"))
        assertTrue(script.contains("network.lan.ipaddr='192.168.10.1'"))
        assertTrue(script.contains("ssid='Casa'\\''; reboot; '\\'''"))
        assertTrue(script.contains("key='senha forte 1'"))
        assertTrue(script.length < FirmwareRules.MAX_DEFAULTS_LENGTH)
    }

    @Test
    fun defaultsScript_withoutWifiLeavesRadiosAlone() {
        val script = FirmwareRules.defaultsScript(FirstBootSettings())
        assertFalse(script.contains("ssid="))
        assertFalse(script.contains("disabled='0'"))
        assertTrue(script.contains("country='BR'"))
    }

    @Test
    fun validate_flagsBadSettings() {
        assertEquals(emptyList<SettingsProblem>(), FirmwareRules.validate(FirstBootSettings("ok", "192.168.1.1", "Casa", "12345678")))
        assertEquals(
            listOf(SettingsProblem.HOSTNAME, SettingsProblem.LAN_IP, SettingsProblem.WIFI_PASSWORD, SettingsProblem.WIFI_NAME),
            FirmwareRules.validate(FirstBootSettings("nome inválido", "999.1.1.1", "", "curta"))
        )
    }
}
