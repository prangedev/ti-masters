package br.com.timasters.app.data.routers.config

import br.com.timasters.app.R

/** RouterOS menus read before planning: path → items. Menus without items are stored as a single-item list. */
typealias RosSnapshot = Map<String, List<Map<String, String>>>

/**
 * Turns a task into RouterOS changes, reusing what already exists (same PPPoE client, pool, bridge…)
 * so running the same task twice doesn't create duplicates. Pure: easy to test and to preview.
 */
object MikroTikPlanner {

    const val PPPOE_NAME = "pppoe-out1"
    const val CAPSMAN = "interface/wifi/capsman"

    /** Menus each task needs. */
    fun paths(task: ConfigTask): List<String> = when (task) {
        is ConfigTask.Pppoe -> listOf(
            "interface/vlan", "interface/pppoe-client", "ip/dhcp-client", "ip/firewall/nat", "interface/list", "interface/list/member"
        )
        is ConfigTask.Dhcp -> listOf("ip/address", "ip/pool", "ip/dhcp-server", "ip/dhcp-server/network")
        is ConfigTask.Bridge -> listOf("interface/bridge", "interface/bridge/port")
        is ConfigTask.Mesh -> listOf("interface/wifi/configuration", "interface/wifi/provisioning")
    }

    fun plan(task: ConfigTask, snapshot: RosSnapshot): ConfigPlan = when (task) {
        is ConfigTask.Pppoe -> pppoe(task, snapshot)
        is ConfigTask.Dhcp -> dhcp(task, snapshot)
        is ConfigTask.Bridge -> bridge(task, snapshot)
        is ConfigTask.Mesh -> mesh(task, snapshot)
    }

    private fun RosSnapshot.items(path: String) = this[path].orEmpty()

    private fun Map<String, String>.isEnabled() = this["disabled"] != "true" && this["disabled"] != "yes"

    private fun set(path: String, item: Map<String, String>, values: Map<String, String>): RosSet? {
        val changed = values.filter { (key, value) -> item[key] != value }
        if (changed.isEmpty()) return null
        return RosSet(path, item.getValue(".id"), item["name"] ?: item["interface"] ?: item.getValue(".id"), changed, item.filterKeys { it in changed })
    }

    private fun pppoe(task: ConfigTask.Pppoe, snapshot: RosSnapshot): ConfigPlan {
        val ops = mutableListOf<ConfigOp>()
        val warnings = mutableListOf<PlanWarning>()
        val dialPort = if (task.vlanId != null) {
            val existing = snapshot.items("interface/vlan").firstOrNull { it["interface"] == task.port && it["vlan-id"] == task.vlanId.toString() }
            val name = existing?.get("name") ?: "vlan${task.vlanId}-${task.port}"
            if (existing == null) ops += RosAdd("interface/vlan", mapOf("name" to name, "interface" to task.port, "vlan-id" to task.vlanId.toString()))
            name
        } else {
            task.port
        }

        val clients = snapshot.items("interface/pppoe-client")
        val current = clients.firstOrNull { it["name"] == PPPOE_NAME } ?: clients.firstOrNull()
        val name = current?.get("name") ?: PPPOE_NAME
        val values = mapOf(
            "interface" to dialPort,
            "user" to task.user,
            "password" to task.password,
            "add-default-route" to "yes",
            "use-peer-dns" to "yes",
            "disabled" to "no"
        )
        if (current == null) {
            ops += RosAdd("interface/pppoe-client", mapOf("name" to name) + values)
        } else {
            warnings += PlanWarning(R.string.config_warn_pppoe_replaced, listOf(name))
            set("interface/pppoe-client", current, values.normalizedFor(current))?.let(ops::add)
        }

        snapshot.items("ip/dhcp-client").filter { it["interface"] == task.port && it.isEnabled() }.forEach { client ->
            warnings += PlanWarning(R.string.config_warn_dhcp_client_disabled, listOf(task.port))
            set("ip/dhcp-client", client, mapOf("disabled" to "yes"))?.let(ops::add)
        }

        val nat = snapshot.items("ip/firewall/nat").filter { it["action"] == "masquerade" && it.isEnabled() }
        if (nat.none { it["out-interface"] == name }) {
            val hasWanList = snapshot.items("interface/list").any { it["name"] == "WAN" }
            val masqueradesWan = nat.any { it["out-interface-list"] == "WAN" }
            if (hasWanList && masqueradesWan) {
                if (snapshot.items("interface/list/member").none { it["list"] == "WAN" && it["interface"] == name }) {
                    ops += RosAdd("interface/list/member", mapOf("list" to "WAN", "interface" to name))
                }
            } else {
                ops += RosAdd("ip/firewall/nat", mapOf("chain" to "srcnat", "action" to "masquerade", "out-interface" to name, "comment" to "TI Masters"))
            }
        }
        return ConfigPlan(task, ops, warnings)
    }

    private fun dhcp(task: ConfigTask.Dhcp, snapshot: RosSnapshot): ConfigPlan {
        val ops = mutableListOf<ConfigOp>()
        val warnings = mutableListOf<PlanWarning>()
        val cidr = ConfigValidation.networkCidr(task.gateway, task.prefix).orEmpty()
        val range = "${task.rangeStart}-${task.rangeEnd}"
        val lease = "${task.leaseHours}h"

        if (snapshot.items("ip/address").none { it["interface"] == task.lan && it["address"]?.substringBefore('/') == task.gateway }) {
            ops += RosAdd("ip/address", mapOf("address" to "${task.gateway}/${task.prefix}", "interface" to task.lan))
            warnings += PlanWarning(R.string.config_warn_address_added, listOf("${task.gateway}/${task.prefix}", task.lan))
        }

        val pools = snapshot.items("ip/pool")
        val server = snapshot.items("ip/dhcp-server").firstOrNull { it["interface"] == task.lan }
        val poolName = server?.get("address-pool")?.takeIf { name -> pools.any { it["name"] == name } } ?: "dhcp-${task.lan}"
        val pool = pools.firstOrNull { it["name"] == poolName }
        if (pool == null) ops += RosAdd("ip/pool", mapOf("name" to poolName, "ranges" to range))
        else set("ip/pool", pool, mapOf("ranges" to range))?.let(ops::add)

        val serverValues = mapOf("address-pool" to poolName, "lease-time" to lease, "disabled" to "no")
        if (server == null) {
            ops += RosAdd("ip/dhcp-server", mapOf("name" to "dhcp-${task.lan}", "interface" to task.lan) + serverValues)
        } else {
            set("ip/dhcp-server", server, serverValues.normalizedFor(server))?.let(ops::add)
        }

        val networkValues = mapOf("gateway" to task.gateway, "dns-server" to task.dns.replace(" ", ""))
        val network = snapshot.items("ip/dhcp-server/network").firstOrNull { it["address"] == cidr }
        if (network == null) ops += RosAdd("ip/dhcp-server/network", mapOf("address" to cidr) + networkValues)
        else set("ip/dhcp-server/network", network, networkValues)?.let(ops::add)
        return ConfigPlan(task, ops, warnings)
    }

    private fun bridge(task: ConfigTask.Bridge, snapshot: RosSnapshot): ConfigPlan {
        val ops = mutableListOf<ConfigOp>()
        val warnings = mutableListOf(PlanWarning(R.string.config_warn_bridge_disconnect))
        if (snapshot.items("interface/bridge").none { it["name"] == task.name }) {
            ops += RosAdd("interface/bridge", mapOf("name" to task.name))
        }
        val ports = snapshot.items("interface/bridge/port")
        task.ports.forEach { port ->
            val existing = ports.firstOrNull { it["interface"] == port }
            when {
                existing == null -> ops += RosAdd("interface/bridge/port", mapOf("bridge" to task.name, "interface" to port))
                existing["bridge"] != task.name -> {
                    warnings += PlanWarning(R.string.config_warn_port_moved, listOf(port, existing["bridge"].orEmpty()))
                    set("interface/bridge/port", existing, mapOf("bridge" to task.name))?.let(ops::add)
                }
            }
        }
        return ConfigPlan(task, ops, warnings)
    }

    /** CAPsMAN controller on the RouterOS 7 wifi package; cAP/hAP units in CAP mode get the network. */
    private fun mesh(task: ConfigTask.Mesh, snapshot: RosSnapshot): ConfigPlan {
        val capsman = snapshot[CAPSMAN]?.firstOrNull()
            ?: return ConfigPlan(task, emptyList(), listOf(PlanWarning(R.string.config_warn_mesh_unsupported)))
        val ops = mutableListOf<ConfigOp>()
        val configName = "timasters-wifi"
        val configValues = mapOf(
            "ssid" to task.network,
            "security.authentication-types" to "wpa2-psk,wpa3-psk",
            "security.passphrase" to task.password
        )
        val config = snapshot.items("interface/wifi/configuration").firstOrNull { it["name"] == configName }
        if (config == null) ops += RosAdd("interface/wifi/configuration", mapOf("name" to configName) + configValues)
        else set("interface/wifi/configuration", config, configValues)?.let(ops::add)

        if (snapshot.items("interface/wifi/provisioning").none { it["master-configuration"] == configName }) {
            ops += RosAdd("interface/wifi/provisioning", mapOf("action" to "create-dynamic-enabled", "master-configuration" to configName))
        }
        val capsmanValues = mapOf("enabled" to "yes", "interfaces" to task.lan)
        val changed = capsmanValues.filter { (key, value) -> capsman[key] != value && !(key == "enabled" && capsman[key] == "true") }
        if (changed.isNotEmpty()) ops += RosSetSingleton(CAPSMAN, changed, capsman.filterKeys { it in changed })
        return ConfigPlan(task, ops, listOf(PlanWarning(R.string.config_warn_capsman)))
    }

    /** RouterOS answers booleans as "true"/"false"; don't count yes vs true as a change. */
    private fun Map<String, String>.normalizedFor(item: Map<String, String>): Map<String, String> = filter { (key, value) ->
        val current = item[key]
        !(value == "yes" && current == "true") && !(value == "no" && current == "false")
    }
}
