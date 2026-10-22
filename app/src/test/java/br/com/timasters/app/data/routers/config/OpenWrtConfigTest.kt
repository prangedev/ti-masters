package br.com.timasters.app.data.routers.config

import br.com.timasters.app.R
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plans, applies and undoes OpenWrt tasks against in-memory UCI with staging and confirm. */
class OpenWrtConfigTest {

    private fun s(value: String) = JsonPrimitive(value)
    private fun l(vararg values: String) = JsonArray(values.map(::JsonPrimitive))

    /** OpenWrt 23.05 defaults on a DSA router. */
    private fun defaultConfig(): Map<String, Map<String, Map<String, JsonElement>>> = mapOf(
        "network" to mapOf(
            "loopback" to mapOf(".type" to s("interface"), "device" to s("lo"), "proto" to s("static")),
            "cfg030f15" to mapOf(".type" to s("device"), "name" to s("br-lan"), "type" to s("bridge"), "ports" to l("lan1", "lan2")),
            "lan" to mapOf(".type" to s("interface"), "device" to s("br-lan"), "proto" to s("static"), "ipaddr" to s("192.168.1.1"), "netmask" to s("255.255.255.0")),
            "wan" to mapOf(".type" to s("interface"), "device" to s("wan"), "proto" to s("dhcp")),
            "wan6" to mapOf(".type" to s("interface"), "device" to s("wan"), "proto" to s("dhcpv6"))
        ),
        "dhcp" to mapOf(
            "lan" to mapOf(".type" to s("dhcp"), "interface" to s("lan"), "start" to s("100"), "limit" to s("150"), "leasetime" to s("12h"))
        ),
        "wireless" to mapOf(
            "radio0" to mapOf(".type" to s("wifi-device"), "type" to s("mac80211"), "channel" to s("36"), "disabled" to s("1")),
            "default_radio0" to mapOf(".type" to s("wifi-iface"), "device" to s("radio0"), "mode" to s("ap"), "ssid" to s("OpenWrt"))
        )
    )

    private suspend fun plan(uci: FakeUci, task: ConfigTask) =
        OpenWrtPlanner.plan(task, OpenWrtPlanner.configs(task).associateWith { uci.get(it) })

    private fun executor(uci: FakeUci) = OpenWrtExecutor(uci, confirmAttempts = 3, confirmDelayMs = 0)

    @Test
    fun pppoe_withVlan_setsDeviceAndUndoRemovesNewOptions() = runTest {
        val uci = FakeUci(defaultConfig())
        val before = uci.live

        val plan = plan(uci, ConfigTask.Pppoe("wan", 10, "cliente", "segredo"))
        val result = executor(uci).apply(plan) as ApplyResult.Success

        val wan = uci.live.getValue("network").getValue("wan")
        assertEquals(s("pppoe"), wan["proto"])
        assertEquals(s("wan.10"), wan["device"])
        assertEquals(1, uci.applied)
        assertTrue(executor(uci).undo(result.applied) is ApplyResult.Success)
        assertEquals(before, uci.live)
        assertNull(uci.live.getValue("network").getValue("wan")["username"])
    }

    @Test
    fun dhcp_refusesToChangeLanAddress() = runTest {
        val plan = plan(FakeUci(defaultConfig()), ConfigTask.Dhcp("lan", "192.168.50.1", 24, "192.168.50.100", "192.168.50.200", "1.1.1.1", 12))

        assertTrue(plan.isEmpty)
        assertEquals(R.string.config_warn_openwrt_lan_ip, plan.warnings.single().resId)
        assertEquals(listOf("192.168.1.1/24"), plan.warnings.single().args)
    }

    @Test
    fun dhcp_convertsRangeToStartAndLimit() = runTest {
        val uci = FakeUci(defaultConfig())
        val plan = plan(uci, ConfigTask.Dhcp("lan", "192.168.1.1", 24, "192.168.1.50", "192.168.1.99", "1.1.1.1,8.8.8.8", 24))

        executor(uci).apply(plan) as ApplyResult.Success

        val dhcp = uci.live.getValue("dhcp").getValue("lan")
        assertEquals(s("50"), dhcp["start"])
        assertEquals(s("50"), dhcp["limit"])
        assertEquals(s("24h"), dhcp["leasetime"])
        assertEquals(l("6,1.1.1.1,8.8.8.8"), dhcp["dhcp_option"])
    }

    @Test
    fun bridge_apModeJoinsWanToLanAndDisablesDhcp() = runTest {
        val uci = FakeUci(defaultConfig())
        val before = uci.live
        val plan = plan(uci, ConfigTask.Bridge("br-lan", listOf("wan"), "192.168.0.1"))

        val result = executor(uci).apply(plan) as ApplyResult.Success

        val network = uci.live.getValue("network")
        assertEquals(l("lan1", "lan2", "wan"), network.getValue("cfg030f15")["ports"])
        assertEquals(s("none"), network.getValue("wan")["proto"])
        assertEquals(s("none"), network.getValue("wan6")["proto"])
        assertEquals(s("192.168.0.1"), network.getValue("lan")["gateway"])
        assertEquals(s("1"), uci.live.getValue("dhcp").getValue("lan")["ignore"])
        executor(uci).undo(result.applied)
        assertEquals(before, uci.live)
    }

    @Test
    fun mesh_addsMeshInterfaceAndEnablesRadio() = runTest {
        val uci = FakeUci(defaultConfig())
        val before = uci.live
        val plan = plan(uci, ConfigTask.Mesh("malha-cliente", "senhaforte123", "radio0", "lan"))

        val result = executor(uci).apply(plan) as ApplyResult.Success

        val wireless = uci.live.getValue("wireless")
        val mesh = wireless.values.single { it["mode"] == s("mesh") }
        assertEquals(s("malha-cliente"), mesh["mesh_id"])
        assertEquals(s("sae"), mesh["encryption"])
        assertEquals(s("0"), wireless.getValue("radio0")["disabled"])
        executor(uci).undo(result.applied)
        assertEquals(before, uci.live)
    }

    @Test
    fun confirmTimeout_reportsAutomaticRollback() = runTest {
        val uci = FakeUci(defaultConfig()).apply { confirmWorks = false }
        val before = uci.live
        val plan = plan(uci, ConfigTask.Pppoe("wan", null, "u", "p"))

        val result = executor(uci).apply(plan) as ApplyResult.Failed

        assertEquals(OpenWrtExecutor.CONFIRM_TIMEOUT, result.message)
        assertTrue(result.rolledBack)
        assertEquals(before, uci.live)
    }

    @Test
    fun configurator_readsOpenWrtInventory() = runTest {
        val session = RouterConfigurator(kotlinx.serialization.json.Json).connectOpenWrt(FakeUci(defaultConfig()))

        assertEquals(RouterPlatform.OPENWRT, session.info.platform)
        assertEquals("wan", session.wanPort)
        assertEquals("192.168.1.1" to 24, session.lanAddress)
        assertEquals(listOf("wan", "lan1", "lan2"), session.info.ports)
        assertEquals(listOf("radio0"), session.info.radios)
        assertTrue(session.info.supportsBridge)
    }
}
