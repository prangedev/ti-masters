package br.com.timasters.app.data.routers.config

import br.com.timasters.app.data.routers.config.OpenWrtPlanner.list
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** A logged-in router. Credentials live only inside the client, in memory, for this session. */
sealed interface RouterSession {
    val info: RouterInfo
    val executor: RouterExecutor

    /** LAN address as ("192.168.88.1", 24) to prefill the DHCP form. */
    val lanAddress: Pair<String, Int>?

    /** Current WAN port to prefill PPPoE. */
    val wanPort: String?
}

class MikroTikSession(
    val client: RouterOsClient,
    override val info: RouterInfo,
    override val lanAddress: Pair<String, Int>?,
    override val wanPort: String?
) : RouterSession {
    override val executor: RouterExecutor = MikroTikExecutor(client)
}

class OpenWrtSession(
    val client: UciClient,
    override val info: RouterInfo,
    override val lanAddress: Pair<String, Int>?,
    override val wanPort: String?
) : RouterSession {
    override val executor: RouterExecutor = OpenWrtExecutor(client)

    /** Firmware calls; the real ubus client provides them. */
    val system: OpenWrtSystemClient? get() = client as? OpenWrtSystemClient
}

sealed interface ConnectResult {
    data class Connected(val session: RouterSession) : ConnectResult
    data object WrongPassword : ConnectResult

    /** Answered, but it's neither RouterOS 7 REST nor OpenWrt ubus: use the guided steps. */
    data object Unsupported : ConnectResult
    data class Unreachable(val message: String) : ConnectResult
}

@Singleton
class RouterConfigurator @Inject constructor(private val json: Json) {

    suspend fun connect(endpoint: RouterEndpoint, user: String, password: String): ConnectResult {
        var reachable = false
        try {
            return ConnectResult.Connected(connectMikroTik(MikroTikRestClient(endpoint, user, password, json)))
        } catch (_: RouterAuthException) {
            return ConnectResult.WrongPassword
        } catch (_: LanHttpException) {
            reachable = true // 404: not RouterOS 7 REST; try OpenWrt below
        } catch (_: kotlinx.serialization.SerializationException) {
            reachable = true
        } catch (_: IllegalArgumentException) {
            reachable = true
        } catch (e: IOException) {
            if (e is RouterApiException) reachable = true else return ConnectResult.Unreachable(e.message.orEmpty())
        }
        return try {
            ConnectResult.Connected(connectOpenWrt(OpenWrtUbusClient.login(endpoint, user, password, json)))
        } catch (_: RouterAuthException) {
            ConnectResult.WrongPassword
        } catch (e: IOException) {
            if (reachable) ConnectResult.Unsupported else ConnectResult.Unreachable(e.message.orEmpty())
        } catch (_: Exception) {
            ConnectResult.Unsupported
        }
    }

    suspend fun connectMikroTik(client: RouterOsClient): MikroTikSession {
        val resource = client.get("system/resource")
        val version = resource["version"] ?: throw RouterApiException("Not RouterOS")
        val interfaces = client.list("interface")
        val ports = interfaces.filter { it["type"] in setOf("ether", "vlan") }.mapNotNull { it["name"] }
        val bridges = interfaces.filter { it["type"] == "bridge" }.mapNotNull { it["name"] }
        val supportsMesh = runCatching { client.get(MikroTikPlanner.CAPSMAN) }.isSuccess
        val address = client.list("ip/address").firstOrNull { it["interface"] in bridges } ?: client.list("ip/address").firstOrNull()
        val lan = address?.get("address")?.let { cidr -> cidr.substringBefore('/') to (cidr.substringAfter('/').toIntOrNull() ?: 24) }
        val wan = client.list("interface/list/member").firstOrNull { it["list"] == "WAN" }?.get("interface") ?: ports.firstOrNull()
        val info = RouterInfo(
            platform = RouterPlatform.MIKROTIK,
            model = resource["board-name"].orEmpty(),
            version = version,
            ports = ports,
            bridges = bridges,
            supportsMesh = supportsMesh
        )
        return MikroTikSession(client, info, lan, wan)
    }

    suspend fun connectOpenWrt(client: UciClient): OpenWrtSession {
        val board = client.board()
        val network = client.get("network")
        val wireless = runCatching { client.get("wireless") }.getOrDefault(emptyMap())
        val snapshot: UciSnapshot = mapOf("network" to network, "wireless" to wireless)
        val wan = OpenWrtPlanner.wanDevice(snapshot)
        val bridgePorts = OpenWrtPlanner.lanBridge(snapshot)?.second?.list("ports").orEmpty()
        val interfaces = OpenWrtPlanner.sectionsOfType(snapshot, "network", "interface").keys.filter { it != "loopback" }
        val release = board["release"] as? JsonObject
        val info = RouterInfo(
            platform = RouterPlatform.OPENWRT,
            model = board["model"]?.jsonPrimitive?.contentOrNull.orEmpty(),
            version = release?.get("version")?.jsonPrimitive?.contentOrNull.orEmpty(),
            ports = (listOfNotNull(wan) + bridgePorts).distinct(),
            bridges = interfaces,
            radios = OpenWrtPlanner.sectionsOfType(snapshot, "wireless", "wifi-device").keys.toList(),
            supportsMesh = wireless.isNotEmpty(),
            supportsBridge = OpenWrtPlanner.lanBridge(snapshot) != null
        )
        return OpenWrtSession(client, info, OpenWrtPlanner.lanAddress(snapshot), wan)
    }

    /** Reads the current configuration and computes the changes; nothing is changed yet. */
    suspend fun plan(session: RouterSession, task: ConfigTask): ConfigPlan = when (session) {
        is MikroTikSession -> {
            val snapshot = MikroTikPlanner.paths(task).associateWith { session.client.list(it) }.toMutableMap()
            if (task is ConfigTask.Mesh) runCatching { session.client.get(MikroTikPlanner.CAPSMAN) }.getOrNull()?.let { snapshot[MikroTikPlanner.CAPSMAN] = listOf(it) }
            MikroTikPlanner.plan(task, snapshot)
        }
        is OpenWrtSession -> OpenWrtPlanner.plan(task, OpenWrtPlanner.configs(task).associateWith { session.client.get(it) })
    }
}
