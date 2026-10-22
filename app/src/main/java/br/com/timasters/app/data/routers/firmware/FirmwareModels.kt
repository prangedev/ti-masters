package br.com.timasters.app.data.routers.firmware

import androidx.annotation.StringRes
import br.com.timasters.app.R
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Optional package groups the technician can add to a custom OpenWrt image. */
enum class PackagePreset(@param:StringRes val titleResId: Int, @param:StringRes val descriptionResId: Int, val packages: List<String>) {
    LUCI_PT_BR(R.string.fw_preset_luci, R.string.fw_preset_luci_desc, listOf("luci", "luci-i18n-base-pt-br")),

    /** Mesh needs the full wpad; "-pkg" removes the basic one from the defaults. */
    MESH(R.string.fw_preset_mesh, R.string.fw_preset_mesh_desc, listOf("wpad-mesh-mbedtls", "-wpad-basic-mbedtls")),
    WIREGUARD(R.string.fw_preset_wireguard, R.string.fw_preset_wireguard_desc, listOf("wireguard-tools", "luci-proto-wireguard")),
    SQM(R.string.fw_preset_sqm, R.string.fw_preset_sqm_desc, listOf("luci-app-sqm", "luci-i18n-sqm-pt-br"))
}

/**
 * Settings baked into the image and applied on its first boot. Only used for fresh installs or when
 * the current settings are not kept, otherwise they would overwrite the router's configuration.
 * Secrets travel to the public OpenWrt build server, so PPPoE and admin passwords are left out on
 * purpose (configure them later in the "Configurar" tab).
 */
data class FirstBootSettings(
    val hostname: String = "",
    val lanIp: String = "",
    val wifiName: String = "",
    val wifiPassword: String = ""
)

/** One device in OpenWrt's list (downloads.openwrt.org/releases/<version>/.overview.json). */
@Serializable
data class OverviewFile(val release: String = "", val profiles: List<OverviewProfile> = emptyList())

@Serializable
data class OverviewProfile(val id: String, val target: String, val titles: List<DeviceTitle> = emptyList()) {
    /** "TP-Link Archer C6 v2 (EU/RU/JP)" and the other names the same image covers. */
    val names: List<String> get() = titles.map { it.display }
}

@Serializable
data class DeviceTitle(
    val vendor: String = "",
    val model: String = "",
    val variant: String = "",
    /** Some devices only have a single free-text title. */
    val title: String = ""
) {
    val display: String get() = title.ifBlank { listOf(vendor, model, variant).filter { it.isNotBlank() }.joinToString(" ") }
}

/** profiles.json of one target: what a device needs and which images exist. */
@Serializable
data class TargetProfiles(
    @SerialName("version_number") val version: String = "",
    val target: String = "",
    val profiles: Map<String, DeviceProfile> = emptyMap()
)

@Serializable
data class DeviceProfile(
    @SerialName("supported_devices") val supportedDevices: List<String> = emptyList(),
    val titles: List<DeviceTitle> = emptyList(),
    val images: List<FirmwareImage> = emptyList(),
    @SerialName("file_size_limits") val sizeLimits: Map<String, Long> = emptyMap()
)

@Serializable
data class FirmwareImage(val name: String, val type: String, val sha256: String = "", val size: Long = 0)

/** What the ASU build server answered. */
sealed interface BuildStatus {
    data class Queued(val hash: String, val position: Int?) : BuildStatus
    data class Building(val hash: String) : BuildStatus
    data class Done(val hash: String, val binDir: String, val images: List<FirmwareImage>) : BuildStatus
    data class Failed(val detail: String) : BuildStatus
}

/** A finished, downloaded and checksum-verified image. */
class VerifiedImage(val name: String, val sha256: String, val bytes: ByteArray)
