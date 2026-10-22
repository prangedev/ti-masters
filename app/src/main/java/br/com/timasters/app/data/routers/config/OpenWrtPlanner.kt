package br.com.timasters.app.data.routers.config

import br.com.timasters.app.R
import br.com.timasters.app.core.network.SubnetUtils
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

/** UCI configs read before planning: config → section name → options (with ".type"). */
typealias UciSnapshot = Map<String, Map<String, Map<String, JsonElement>>>

/** Turns a task into UCI changes (OpenWrt 21.02+ with DSA "device" sections). Pure. */
object OpenWrtPlanner {

    fun configs(task: ConfigTask): List<String> = when (task) {
        is ConfigTask.Pppoe -> listOf("network")
        is ConfigTask.Dhcp, is ConfigTask.Bridge -> listOf("network", "dhcp")
        is ConfigTask.Mesh -> listOf("wireless")
    }

    fun plan(task: ConfigTask, snapshot: UciSnapshot): ConfigPlan = when (task) {
        is ConfigTask.Pppoe -> pppoe(task, snapshot)
        is ConfigTask.Dhcp -> dhcp(task, snapshot)
        is ConfigTask.Bridge -> bridge(task, snapshot)
        is ConfigTask.Mesh -> mesh(task, snapshot)
    }

    // region Helpers shared with RouterConfigurator

    fun Map<String, JsonElement>.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull

    fun Map<String, JsonElement>.list(key: String): List<String> = when (val value = this[key]) {
        is JsonArray -> value.mapNotNull { it.jsonPrimitive.contentOrNull }
        is JsonPrimitive -> value.contentOrNull?.split(' ')?.filter { it.isNotBlank() }.orEmpty()
        else -> emptyList()
    }

    fun sectionsOfType(snapshot: UciSnapshot, config: String, type: String) =
        snapshot[config].orEmpty().filterValues { it.text(".type") == type }

    /** The LAN bridge device section ("config device" with name br-lan). */
    fun lanBridge(snapshot: UciSnapshot): Pair<String, Map<String, JsonElement>>? =
        sectionsOfType(snapshot, "network", "device").entries.firstOrNull { it.value.text("name") == "br-lan" }?.toPair()

    /** LAN address as ("192.168.1.1", 24), from "ipaddr" + "netmask" or a CIDR "ipaddr". */
    fun lanAddress(snapshot: UciSnapshot): Pair<String, Int>? {
        val lan = snapshot["network"]?.get("lan") ?: return null
        val raw = lan.list("ipaddr").firstOrNull() ?: return null
        val ip = raw.substringBefore('/')
        val prefix = raw.substringAfter('/', "").toIntOrNull() ?: lan.text("netmask")?.let(ConfigValidation::netmaskToPrefix) ?: 24
        return ip.takeIf { SubnetUtils.isValidIpv4(it) }?.let { it to prefix }
    }

    /** Device of the WAN interface: "device" (21.02+) or "ifname" (older). */
    fun wanDevice(snapshot: UciSnapshot): String? {
        val wan = snapshot["network"]?.get("wan") ?: return null
        return wan.text("device") ?: wan.text("ifname")
    }

    // endregion

    private fun str(value: String) = JsonPrimitive(value)

    private fun list(values: List<String>) = JsonArray(values.map(::JsonPrimitive))

    private fun set(config: String, name: String, section: Map<String, JsonElement>, values: Map<String, JsonElement>): UciSet? {
        val changed = values.filter { (key, value) -> section[key] != value }
        if (changed.isEmpty()) return null
        return UciSet(config, name, changed, changed.keys.associateWith { section[it] })
    }

    private fun pppoe(task: ConfigTask.Pppoe, snapshot: UciSnapshot): ConfigPlan {
        val wan = snapshot["network"]?.get("wan")
            ?: return ConfigPlan(task, emptyList(), listOf(PlanWarning(R.string.config_warn_openwrt_no_wan)))
        val deviceKey = if (wan.containsKey("ifname") && !wan.containsKey("device")) "ifname" else "device"
        val device = task.vlanId?.let { "${task.port}.$it" } ?: task.port
        val values = mapOf(
            "proto" to str("pppoe"),
            "username" to str(task.user),
            "password" to str(task.password),
            deviceKey to str(device)
        )
        return ConfigPlan(task, listOfNotNull(set("network", "wan", wan, values)))
    }

    private fun dhcp(task: ConfigTask.Dhcp, snapshot: UciSnapshot): ConfigPlan {
        val (lanIp, lanPrefix) = lanAddress(snapshot)
            ?: return ConfigPlan(task, emptyList(), listOf(PlanWarning(R.string.config_warn_openwrt_no_lan)))
        // Changing the LAN address would cut this connection before the change can be confirmed.
        if (lanIp != task.gateway || lanPrefix != task.prefix) {
            return ConfigPlan(task, emptyList(), listOf(PlanWarning(R.string.config_warn_openwrt_lan_ip, listOf("$lanIp/$lanPrefix"))))
        }
        val network = SubnetUtils.ipv4ToInt(lanIp)!! and ConfigValidation.prefixMask(lanPrefix)
        val start = SubnetUtils.ipv4ToInt(task.rangeStart)!! - network
        val limit = SubnetUtils.ipv4ToInt(task.rangeEnd)!! - SubnetUtils.ipv4ToInt(task.rangeStart)!! + 1
        val values = mapOf(
            "start" to str(start.toString()),
            "limit" to str(limit.toString()),
            "leasetime" to str("${task.leaseHours}h"),
            "dhcp_option" to list(listOf("6," + task.dns.replace(" ", ""))),
            "ignore" to str("0")
        )
        val section = sectionsOfType(snapshot, "dhcp", "dhcp").entries.firstOrNull { it.value.text("interface") == task.lan }
        val op = if (section == null) UciAdd("dhcp", "dhcp", mapOf("interface" to str(task.lan)) + values)
        else set("dhcp", section.key, section.value, values.filterKeys { it != "ignore" || section.value.containsKey("ignore") })
        return ConfigPlan(task, listOfNotNull(op))
    }

    /** "AP mode": WAN port joins br-lan, WAN goes unused, DHCP server off, upstream router as gateway. */
    private fun bridge(task: ConfigTask.Bridge, snapshot: UciSnapshot): ConfigPlan {
        val (bridgeName, bridge) = lanBridge(snapshot)
            ?: return ConfigPlan(task, emptyList(), listOf(PlanWarning(R.string.config_warn_openwrt_no_dsa)))
        val ops = mutableListOf<ConfigOp>()
        val ports = (bridge.list("ports") + task.ports).distinct()
        set("network", bridgeName, bridge, mapOf("ports" to list(ports)))?.let(ops::add)
        listOf("wan", "wan6").forEach { name ->
            snapshot["network"]?.get(name)?.let { section -> set("network", name, section, mapOf("proto" to str("none")))?.let(ops::add) }
        }
        sectionsOfType(snapshot, "dhcp", "dhcp").entries.firstOrNull { it.value.text("interface") == "lan" }?.let { (name, section) ->
            set("dhcp", name, section, mapOf("ignore" to str("1")))?.let(ops::add)
        }
        task.upstreamGateway?.let { gateway ->
            snapshot["network"]?.get("lan")?.let { lan ->
                set("network", "lan", lan, mapOf("gateway" to str(gateway), "dns" to list(listOf(gateway))))?.let(ops::add)
            }
        }
        val lanIp = lanAddress(snapshot)?.let { "${it.first}/${it.second}" }.orEmpty()
        return ConfigPlan(task, ops, listOf(PlanWarning(R.string.config_warn_ap_mode, listOf(lanIp))))
    }

    /** 802.11s mesh interface bridged into LAN. */
    private fun mesh(task: ConfigTask.Mesh, snapshot: UciSnapshot): ConfigPlan {
        val radios = sectionsOfType(snapshot, "wireless", "wifi-device")
        val radioName = task.radio?.takeIf { it in radios } ?: radios.keys.firstOrNull()
            ?: return ConfigPlan(task, emptyList(), listOf(PlanWarning(R.string.config_warn_openwrt_no_radio)))
        val ops = mutableListOf<ConfigOp>()
        val values = mapOf(
            "device" to str(radioName),
            "mode" to str("mesh"),
            "mesh_id" to str(task.network),
            "encryption" to str("sae"),
            "key" to str(task.password),
            "network" to str(task.lan),
            "mesh_fwding" to str("1"),
            "mesh_rssi_threshold" to str("0")
        )
        val existing = sectionsOfType(snapshot, "wireless", "wifi-iface").entries
            .firstOrNull { it.value.text("device") == radioName && it.value.text("mode") == "mesh" }
        if (existing == null) ops += UciAdd("wireless", "wifi-iface", values)
        else set("wireless", existing.key, existing.value, values)?.let(ops::add)

        val radio = radios.getValue(radioName)
        if (radio.text("disabled") == "1") set("wireless", radioName, radio, mapOf("disabled" to str("0")))?.let(ops::add)
        val warnings = mutableListOf(PlanWarning(R.string.config_warn_mesh_openwrt))
        if (radio.text("channel").let { it == null || it == "auto" }) warnings += PlanWarning(R.string.config_warn_mesh_channel)
        return ConfigPlan(task, ops, warnings)
    }
}
