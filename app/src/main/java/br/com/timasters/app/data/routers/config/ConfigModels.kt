package br.com.timasters.app.data.routers.config

import androidx.annotation.StringRes
import kotlinx.serialization.json.JsonElement

/** Router platforms the configurator can change directly. Anything else gets filled-in manual steps. */
enum class RouterPlatform { MIKROTIK, OPENWRT }

/** What was found after logging in. */
data class RouterInfo(
    val platform: RouterPlatform,
    val model: String,
    val version: String,
    /** Physical ports and VLANs usable as WAN or bridge ports. */
    val ports: List<String>,
    /** Bridges / LAN interfaces. */
    val bridges: List<String>,
    /** Wi-Fi radios (OpenWrt) or empty. */
    val radios: List<String> = emptyList(),
    /** False when the router lacks what a task needs (e.g. MikroTik without the wifi package). */
    val supportsMesh: Boolean = true,
    /** OpenWrt with DSA "device" sections; older swconfig builds can't do the bridge task. */
    val supportsBridge: Boolean = true
)

sealed interface ConfigTask {
    /** PPPoE dial-up on [port], optionally through a VLAN. */
    data class Pppoe(val port: String, val vlanId: Int?, val user: String, val password: String) : ConfigTask

    /** DHCP server on [lan] for [gateway]/[prefix], handing out [rangeStart]..[rangeEnd]. */
    data class Dhcp(
        val lan: String,
        val gateway: String,
        val prefix: Int,
        val rangeStart: String,
        val rangeEnd: String,
        val dns: String,
        val leaseHours: Int
    ) : ConfigTask

    /**
     * Bridge [ports] together. On OpenWrt this is "AP mode": the WAN port joins the LAN bridge, the
     * DHCP server is turned off and [upstreamGateway] becomes the router's own gateway and DNS.
     */
    data class Bridge(val name: String, val ports: List<String>, val upstreamGateway: String?) : ConfigTask

    /**
     * Managed Wi-Fi across several access points: CAPsMAN on MikroTik (wifi package), 802.11s mesh
     * on OpenWrt. [network] is the SSID (MikroTik) or mesh id (OpenWrt).
     */
    data class Mesh(val network: String, val password: String, val radio: String?, val lan: String) : ConfigTask
}

/** One change, shown in the preview as the equivalent command so technicians can review it. */
sealed interface ConfigOp {
    val command: String
}

/** RouterOS: `/path add ...` → REST PUT. */
data class RosAdd(val path: String, val values: Map<String, String>) : ConfigOp {
    override val command: String get() = "/${path.replace('/', ' ')} add ${rosArgs(values)}"
}

/** RouterOS: `/path set [find .id=...] ...` → REST PATCH; [previous] holds the old values for undo. */
data class RosSet(val path: String, val id: String, val label: String, val values: Map<String, String>, val previous: Map<String, String>) : ConfigOp {
    override val command: String get() = "/${path.replace('/', ' ')} set $label ${rosArgs(values)}"
}

/** RouterOS menus without items (e.g. /interface wifi capsman) → REST POST .../set. */
data class RosSetSingleton(val path: String, val values: Map<String, String>, val previous: Map<String, String>) : ConfigOp {
    override val command: String get() = "/${path.replace('/', ' ')} set ${rosArgs(values)}"
}

/** OpenWrt: `uci add config type` with options. */
data class UciAdd(val config: String, val type: String, val values: Map<String, JsonElement>) : ConfigOp {
    override val command: String get() = "uci add $config $type " + uciArgs(values)
}

/** OpenWrt: `uci set config.section.option=value`; a null previous value means the option didn't exist. */
data class UciSet(val config: String, val section: String, val values: Map<String, JsonElement>, val previous: Map<String, JsonElement?>) : ConfigOp {
    override val command: String get() = values.entries.joinToString("\n") { (key, value) -> "uci set $config.$section.$key=${uciValue(key, value)}" }
}

data class PlanWarning(@param:StringRes val resId: Int, val args: List<String> = emptyList())

data class ConfigPlan(val task: ConfigTask, val ops: List<ConfigOp>, val warnings: List<PlanWarning> = emptyList()) {
    val isEmpty: Boolean get() = ops.isEmpty()
}

/** An executed op plus what's needed to revert it (the id RouterOS / uci gave a created item). */
data class AppliedOp(val op: ConfigOp, val createdId: String? = null)

sealed interface ApplyResult {
    data class Success(val applied: List<AppliedOp>, val backupName: String?) : ApplyResult

    /** Failed midway; [rolledBack] tells whether the already-applied changes were reverted. */
    data class Failed(val message: String, val rolledBack: Boolean) : ApplyResult
}

private val SECRET_KEYS = setOf("password", "key", "security.passphrase")

private fun shown(key: String, value: String) = if (key in SECRET_KEYS) "********" else value

private fun rosArgs(values: Map<String, String>): String =
    values.entries.joinToString(" ") { (key, value) ->
        val text = shown(key, value)
        "$key=" + if (text.isEmpty() || text.any { it == ' ' || it == ',' }) "\"$text\"" else text
    }

private fun uciValue(key: String, value: JsonElement): String =
    if (key in SECRET_KEYS) "'********'" else value.toString().replace('"', '\'')

private fun uciArgs(values: Map<String, JsonElement>): String =
    values.entries.joinToString(" ") { (key, value) -> "$key=${uciValue(key, value)}" }
