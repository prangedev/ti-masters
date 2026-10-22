package br.com.timasters.app.data.routers.firmware

import br.com.timasters.app.data.routers.RouterOsVersions
import br.com.timasters.app.data.routers.config.LanHttpException
import br.com.timasters.app.data.routers.config.OpenWrtSystemClient
import br.com.timasters.app.data.routers.config.RouterApiException
import br.com.timasters.app.data.routers.config.RouterAuthException
import br.com.timasters.app.data.routers.config.RouterOsClient
import kotlinx.coroutines.delay
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.IOException
import java.security.MessageDigest

/** Router already on OpenWrt: what it is and which image fits it. */
sealed interface FlashCheck {
    data class Compatible(
        val model: String,
        val boardName: String,
        val target: String,
        val currentVersion: String,
        val newVersion: String,
        val profileId: String,
        /** null = couldn't read the router's package list (the new image would lose extra packages). */
        val installedPackages: List<String>?
    ) : FlashCheck

    data class Incompatible(val reason: IncompatibleReason, val detail: String = "") : FlashCheck
}

enum class IncompatibleReason { NO_BOARD_INFO, NOT_IN_RELEASE, SNAPSHOT }

sealed interface FlashOutcome {
    /** The router accepted the image and started writing it; it reboots by itself. */
    data object Started : FlashOutcome
    data object NotEnoughMemory : FlashOutcome
    data object TransferCorrupted : FlashOutcome

    /** OpenWrt's own check refused the image (wrong board, bad metadata or signature). Nothing was written. */
    data object RejectedByRouter : FlashOutcome
}

class BuildFailedException(val detail: String) : IOException(detail)

/**
 * Custom OpenWrt image for a router that already runs OpenWrt: check → build → download → flash.
 * Every step can be stopped before [flash]; [flash] only starts after the router itself validates
 * the uploaded file, exactly like LuCI's "Flash new firmware image" page.
 */
class OpenWrtFlasher(
    private val source: FirmwareSource,
    private val system: OpenWrtSystemClient,
    private val pollDelayMs: Long = 5_000,
    private val maxPolls: Int = 180
) {

    suspend fun check(): FlashCheck {
        val board = system.board()
        val boardName = board.text("board_name")
        val release = board["release"] as? JsonObject
        val target = release?.text("target")
        val currentVersion = release?.text("version").orEmpty()
        if (boardName.isNullOrBlank() || target.isNullOrBlank()) return FlashCheck.Incompatible(IncompatibleReason.NO_BOARD_INFO)
        if (currentVersion.contains("SNAPSHOT", ignoreCase = true)) return FlashCheck.Incompatible(IncompatibleReason.SNAPSHOT, currentVersion)
        val newVersion = source.stableVersion()
        val (profileId, _) = FirmwareRules.profileForBoard(source.profiles(newVersion, target), boardName)
            ?: return FlashCheck.Incompatible(IncompatibleReason.NOT_IN_RELEASE, boardName)
        return FlashCheck.Compatible(
            model = board.text("model").orEmpty(),
            boardName = boardName,
            target = target,
            currentVersion = currentVersion,
            newVersion = newVersion,
            profileId = profileId,
            installedPackages = system.userPackages()
        )
    }

    /** Submits the build and waits for it, reporting queue/build progress. */
    suspend fun build(
        check: FlashCheck.Compatible,
        presets: Collection<PackagePreset>,
        firstBoot: FirstBootSettings?,
        onStatus: (BuildStatus) -> Unit
    ): BuildStatus.Done {
        val packages = FirmwareRules.packages(check.installedPackages.orEmpty(), presets)
        val defaults = firstBoot?.let(FirmwareRules::defaultsScript)
        return waitForBuild(source, source.submit(check.newVersion, check.target, check.profileId, packages, defaults), onStatus, pollDelayMs, maxPolls)
    }

    suspend fun download(done: BuildStatus.Done, onProgress: (Float) -> Unit): VerifiedImage {
        val image = FirmwareRules.pickImage(done.images, firstInstall = false) ?: throw BuildFailedException("no sysupgrade image")
        return source.download(done.binDir, image, onProgress)
    }

    suspend fun flash(image: VerifiedImage, keepSettings: Boolean): FlashOutcome {
        if (!FirmwareRules.fitsInMemory(image.bytes.size, system.availableMemory())) return FlashOutcome.NotEnoughMemory
        val receipt = system.upload(FIRMWARE_PATH, image.bytes)
        val transferOk = when {
            receipt.sha256 != null -> receipt.sha256.equals(image.sha256, ignoreCase = true)
            else -> (receipt.md5 ?: system.md5(FIRMWARE_PATH))?.equals(md5(image.bytes), ignoreCase = true) == true
        }
        if (!transferOk) return FlashOutcome.TransferCorrupted
        val check = system.validateFirmware(FIRMWARE_PATH)
        if (!check.valid) return FlashOutcome.RejectedByRouter
        system.startUpgrade(keepSettings && check.allowKeepSettings)
        return FlashOutcome.Started
    }

    companion object {
        /** LuCI uploads to the same place; /tmp is RAM, so nothing is written to flash before validation. */
        const val FIRMWARE_PATH = "/tmp/firmware.bin"

        private fun md5(bytes: ByteArray) = MessageDigest.getInstance("MD5").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}

/** First OpenWrt install on a router with stock firmware: build the factory image for the chosen device. */
class FactoryImageBuilder(
    private val source: FirmwareSource,
    private val pollDelayMs: Long = 5_000,
    private val maxPolls: Int = 180
) {
    suspend fun build(
        version: String,
        device: OverviewProfile,
        presets: Collection<PackagePreset>,
        firstBoot: FirstBootSettings,
        onStatus: (BuildStatus) -> Unit
    ): BuildStatus.Done {
        // Check up front that a factory image exists, so we don't build something unusable.
        val profile = source.profiles(version, device.target).profiles[device.id] ?: throw BuildFailedException("profile not found")
        if (FirmwareRules.pickImage(profile.images, firstInstall = true) == null) throw NoFactoryImageException()
        val packages = FirmwareRules.packages(emptyList(), presets)
        val submitted = source.submit(version, device.target, device.id, packages, FirmwareRules.defaultsScript(firstBoot))
        return waitForBuild(source, submitted, onStatus, pollDelayMs, maxPolls)
    }

    suspend fun download(done: BuildStatus.Done, onProgress: (Float) -> Unit): VerifiedImage {
        val image = FirmwareRules.pickImage(done.images, firstInstall = true) ?: throw NoFactoryImageException()
        return source.download(done.binDir, image, onProgress)
    }
}

/** The device has no image that can be sent through the stock web page; it needs the OpenWrt wiki procedure. */
class NoFactoryImageException : IOException("no factory image")

private suspend fun waitForBuild(
    source: FirmwareSource,
    first: BuildStatus,
    onStatus: (BuildStatus) -> Unit,
    pollDelayMs: Long,
    maxPolls: Int
): BuildStatus.Done {
    var status = first
    repeat(maxPolls) {
        onStatus(status)
        when (val current = status) {
            is BuildStatus.Done -> return current
            is BuildStatus.Failed -> throw BuildFailedException(current.detail)
            is BuildStatus.Queued, is BuildStatus.Building -> {
                delay(pollDelayMs)
                val hash = (current as? BuildStatus.Queued)?.hash ?: (current as BuildStatus.Building).hash
                status = source.status(hash)
            }
        }
    }
    throw BuildFailedException("timeout")
}

/** RouterOS status for the update card. */
data class RouterOsUpdate(val installed: String, val latest: String, val channel: String, val status: String) {
    val hasUpdate: Boolean get() = latest.isNotBlank() && RouterOsVersions.compare(installed, latest) < 0
}

data class RouterBoot(val current: String, val available: String) {
    val needsUpgrade: Boolean get() = available.isNotBlank() && current.isNotBlank() && current != available
}

/**
 * MikroTik's own update path. RouterOS is closed source, so there is no custom image: the app runs
 * the official package update (after a backup) and then the RouterBOOT upgrade.
 */
class MikroTikUpdater(private val client: RouterOsClient, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun check(): RouterOsUpdate {
        client.run("system/package/update/check-for-updates")
        val update = client.get("system/package/update")
        return RouterOsUpdate(
            installed = update["installed-version"].orEmpty(),
            latest = update["latest-version"].orEmpty(),
            channel = update["channel"].orEmpty(),
            status = update["status"].orEmpty()
        )
    }

    /** Backup on the router, then download + install; the router reboots on its own. */
    suspend fun install(): String {
        val backup = "timasters-update-${clock() / 1000}"
        client.backup(backup)
        runAllowingDisconnect("system/package/update/install")
        return backup
    }

    suspend fun routerBoot(): RouterBoot? = runCatching {
        val board = client.get("system/routerboard")
        if (board["routerboard"] != "true" && board["routerboard"] != "yes") return null
        RouterBoot(current = board["current-firmware"].orEmpty(), available = board["upgrade-firmware"].orEmpty())
    }.getOrNull()

    suspend fun upgradeRouterBoot() {
        client.run("system/routerboard/upgrade")
        runAllowingDisconnect("system/reboot")
    }

    /** Commands that reboot the router: a dropped connection is expected, a refusal is not. */
    private suspend fun runAllowingDisconnect(path: String) {
        try {
            client.run(path)
        } catch (e: RouterAuthException) {
            throw e
        } catch (e: RouterApiException) {
            throw e
        } catch (e: LanHttpException) {
            throw e
        } catch (_: IOException) {
            // Rebooting.
        }
    }
}

private fun Map<String, kotlinx.serialization.json.JsonElement>.text(key: String): String? = (this[key] as? JsonPrimitive)?.contentOrNull
