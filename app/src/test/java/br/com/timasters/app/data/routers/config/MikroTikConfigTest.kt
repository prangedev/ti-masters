package br.com.timasters.app.data.routers.config

import br.com.timasters.app.R
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Plans, applies and undoes MikroTik tasks against an in-memory RouterOS. */
class MikroTikConfigTest {

    /** RouterOS default configuration: ether1 = WAN by DHCP, NAT on the WAN list, LAN bridge 192.168.88.1/24. */
    private fun defaultRouter() = FakeRouterOs(
        mapOf(
            "interface" to listOf(
                mapOf("name" to "ether1", "type" to "ether"),
                mapOf("name" to "ether2", "type" to "ether"),
                mapOf("name" to "ether3", "type" to "ether"),
                mapOf("name" to "bridge", "type" to "bridge")
            ),
            "interface/vlan" to emptyList(),
            "interface/pppoe-client" to emptyList(),
            "ip/dhcp-client" to listOf(mapOf("interface" to "ether1", "disabled" to "false")),
            "ip/firewall/nat" to listOf(mapOf("chain" to "srcnat", "action" to "masquerade", "out-interface-list" to "WAN", "disabled" to "false")),
            "interface/list" to listOf(mapOf("name" to "WAN"), mapOf("name" to "LAN")),
            "interface/list/member" to listOf(mapOf("list" to "WAN", "interface" to "ether1"), mapOf("list" to "LAN", "interface" to "bridge")),
            "ip/address" to listOf(mapOf("address" to "192.168.88.1/24", "interface" to "bridge")),
            "ip/pool" to listOf(mapOf("name" to "default-dhcp", "ranges" to "192.168.88.10-192.168.88.254")),
            "ip/dhcp-server" to listOf(mapOf("name" to "defconf", "interface" to "bridge", "address-pool" to "default-dhcp", "lease-time" to "30m", "disabled" to "false")),
            "ip/dhcp-server/network" to listOf(mapOf("address" to "192.168.88.0/24", "gateway" to "192.168.88.1", "dns-server" to "192.168.88.1")),
            "interface/bridge" to listOf(mapOf("name" to "bridge")),
            "interface/bridge/port" to listOf(mapOf("bridge" to "bridge", "interface" to "ether2")),
            "interface/wifi/configuration" to emptyList(),
            "interface/wifi/provisioning" to emptyList()
        )
    )

    private suspend fun plan(router: FakeRouterOs, task: ConfigTask): ConfigPlan {
        val snapshot = MikroTikPlanner.paths(task).associateWith { router.list(it) }.toMutableMap()
        router.singletons[MikroTikPlanner.CAPSMAN]?.let { snapshot[MikroTikPlanner.CAPSMAN] = listOf(it.toMap()) }
        return MikroTikPlanner.plan(task, snapshot)
    }

    @Test
    fun pppoe_onDefaultConfig_reusesWanListAndUndoRestoresEverything() = runTest {
        val router = defaultRouter()
        val before = router.state()
        val task = ConfigTask.Pppoe("ether1", null, "cliente@provedor", "s3gredo")

        val plan = plan(router, task)
        assertTrue(plan.ops.any { it is RosAdd && it.path == "interface/pppoe-client" })
        assertTrue(plan.ops.any { it is RosSet && it.path == "ip/dhcp-client" && it.values["disabled"] == "yes" })
        assertTrue(plan.ops.any { it is RosAdd && it.path == "interface/list/member" && it.values["interface"] == "pppoe-out1" })
        assertFalse("WAN list already masquerades", plan.ops.any { it is RosAdd && it.path == "ip/firewall/nat" })
        assertFalse("password hidden in preview", plan.ops.joinToString { it.command }.contains("s3gredo"))

        val executor = MikroTikExecutor(router) { 1_700_000_000_000 }
        val result = executor.apply(plan) as ApplyResult.Success
        assertEquals(listOf("timasters-1700000000"), router.backups)
        assertEquals("cliente@provedor", router.menus.getValue("interface/pppoe-client").single()["user"])

        // Same task again: nothing left to change.
        assertTrue(plan(router, task).isEmpty)

        assertTrue(executor.undo(result.applied) is ApplyResult.Success)
        assertEquals(before, router.state())
    }

    @Test
    fun pppoe_withVlanAndNoWanList_createsVlanAndNatRule() = runTest {
        val router = FakeRouterOs(mapOf("interface/vlan" to emptyList(), "interface/pppoe-client" to emptyList()))

        val plan = plan(router, ConfigTask.Pppoe("ether1", 600, "user", "pass"))

        val vlan = plan.ops.filterIsInstance<RosAdd>().first { it.path == "interface/vlan" }
        assertEquals("600", vlan.values["vlan-id"])
        val pppoe = plan.ops.filterIsInstance<RosAdd>().first { it.path == "interface/pppoe-client" }
        assertEquals(vlan.values["name"], pppoe.values["interface"])
        assertTrue(plan.ops.any { it is RosAdd && it.path == "ip/firewall/nat" && it.values["out-interface"] == "pppoe-out1" })
    }

    @Test
    fun failureMidway_revertsAppliedChanges() = runTest {
        val router = FakeRouterOs(mapOf("interface/vlan" to emptyList(), "interface/pppoe-client" to emptyList()))
        val before = router.state()
        val plan = plan(router, ConfigTask.Pppoe("ether1", 10, "user", "pass"))
        router.failAddOn = "ip/firewall/nat"

        val result = MikroTikExecutor(router).apply(plan) as ApplyResult.Failed

        assertTrue(result.rolledBack)
        assertTrue(result.message.contains("simulated"))
        assertEquals(before, router.state())
    }

    @Test
    fun dhcp_updatesExistingServerPoolAndNetwork() = runTest {
        val router = defaultRouter()
        val before = router.state()
        val task = ConfigTask.Dhcp("bridge", "192.168.88.1", 24, "192.168.88.100", "192.168.88.200", "1.1.1.1, 8.8.8.8", 12)

        val plan = plan(router, task)
        assertFalse("address already there", plan.ops.any { it is RosAdd && it.path == "ip/address" })
        assertTrue(plan.ops.none { it is RosAdd })
        val executor = MikroTikExecutor(router)
        val result = executor.apply(plan) as ApplyResult.Success

        assertEquals("192.168.88.100-192.168.88.200", router.menus.getValue("ip/pool").single()["ranges"])
        assertEquals("12h", router.menus.getValue("ip/dhcp-server").single()["lease-time"])
        assertEquals("1.1.1.1,8.8.8.8", router.menus.getValue("ip/dhcp-server/network").single()["dns-server"])
        executor.undo(result.applied)
        assertEquals(before, router.state())
    }

    @Test
    fun dhcp_onNewInterface_createsAddressPoolServerAndNetwork() = runTest {
        val router = defaultRouter()
        val plan = plan(router, ConfigTask.Dhcp("ether3", "10.10.0.1", 24, "10.10.0.50", "10.10.0.150", "10.10.0.1", 8))

        val adds = plan.ops.filterIsInstance<RosAdd>().map { it.path }
        assertEquals(listOf("ip/address", "ip/pool", "ip/dhcp-server", "ip/dhcp-server/network"), adds)
        assertTrue(plan.warnings.any { it.resId == R.string.config_warn_address_added })
    }

    @Test
    fun bridge_movesPortAndWarns() = runTest {
        val router = defaultRouter()
        val before = router.state()
        val plan = plan(router, ConfigTask.Bridge("bridge-ap", listOf("ether2", "ether3"), null))

        assertTrue(plan.ops.first() is RosAdd)
        assertTrue(plan.warnings.any { it.resId == R.string.config_warn_port_moved })
        val executor = MikroTikExecutor(router)
        val result = executor.apply(plan) as ApplyResult.Success
        assertEquals(setOf("bridge-ap"), router.menus.getValue("interface/bridge/port").map { it["bridge"] }.toSet())
        executor.undo(result.applied)
        assertEquals(before, router.state())
    }

    @Test
    fun mesh_needsWifiPackage_thenConfiguresCapsman() = runTest {
        val router = defaultRouter()
        val task = ConfigTask.Mesh("Cliente_WiFi", "senhaforte123", null, "bridge")

        val unsupported = plan(router, task)
        assertTrue(unsupported.isEmpty)
        assertEquals(R.string.config_warn_mesh_unsupported, unsupported.warnings.single().resId)

        router.singletons[MikroTikPlanner.CAPSMAN] = mutableMapOf("enabled" to "false", "interfaces" to "all")
        val before = router.state()
        val plan = plan(router, task)
        assertTrue(plan.ops.last() is RosSetSingleton)
        val executor = MikroTikExecutor(router)
        val result = executor.apply(plan) as ApplyResult.Success
        assertEquals("yes", router.singletons.getValue(MikroTikPlanner.CAPSMAN)["enabled"])
        executor.undo(result.applied)
        assertEquals(before, router.state())
    }
}
