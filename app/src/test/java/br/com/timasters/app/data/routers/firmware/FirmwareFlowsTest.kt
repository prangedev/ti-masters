package br.com.timasters.app.data.routers.firmware

import br.com.timasters.app.data.routers.config.FakeRouterOs
import br.com.timasters.app.data.routers.config.FirmwareCheck
import br.com.timasters.app.data.routers.config.OpenWrtSystemClient
import br.com.timasters.app.data.routers.config.UploadReceipt
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.MessageDigest

private fun sha256(bytes: ByteArray) = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

/** OpenWrt services without internet: one build that is queued, then building, then done. */
private class FakeSource(private val images: List<FirmwareImage>, private val failBuild: Boolean = false) : FirmwareSource {
    val submitted = mutableListOf<Pair<List<String>, String?>>()
    private var polls = 0
    val payload = ByteArray(1000) { it.toByte() }

    override suspend fun stableVersion() = "25.12.6"
    override suspend fun overview(version: String) = emptyList<OverviewProfile>()
    override suspend fun profiles(version: String, target: String) = TargetProfiles(
        version, target,
        mapOf(
            "tplink_archer-c6-v2" to DeviceProfile(
                supportedDevices = listOf("tplink,archer-c6-v2"),
                titles = listOf(DeviceTitle("TP-Link", "Archer C6", "v2")),
                images = images
            )
        )
    )

    override suspend fun submit(version: String, target: String, profile: String, packages: List<String>, defaults: String?): BuildStatus {
        submitted += packages to defaults
        return BuildStatus.Queued("h1", 2)
    }

    override suspend fun status(hash: String): BuildStatus = when {
        failBuild -> BuildStatus.Failed("Impossible package selection")
        polls++ == 0 -> BuildStatus.Building(hash)
        else -> BuildStatus.Done(hash, hash, images)
    }

    override suspend fun download(binDir: String, image: FirmwareImage, onProgress: (Float) -> Unit): VerifiedImage {
        onProgress(1f)
        return VerifiedImage(image.name, sha256(payload), payload)
    }
}

private class FakeSystem(
    private val boardName: String = "tplink,archer-c6-v2",
    var memory: Long? = 64_000_000,
    var receivedSha: String? = null,
    var valid: Boolean = true
) : OpenWrtSystemClient {
    var upgradedWithKeep: Boolean? = null
    var uploaded: ByteArray? = null

    override suspend fun board(): Map<String, JsonElement> = mapOf(
        "model" to JsonPrimitive("TP-Link Archer C6 v2"),
        "board_name" to JsonPrimitive(boardName),
        "release" to JsonObject(mapOf("version" to JsonPrimitive("23.05.5"), "target" to JsonPrimitive("ath79/generic")))
    )

    override suspend fun availableMemory() = memory
    override suspend fun userPackages() = listOf("luci", "tcpdump")
    override suspend fun upload(path: String, data: ByteArray): UploadReceipt {
        uploaded = data
        return UploadReceipt(sha256 = receivedSha ?: sha256(data), md5 = null)
    }

    override suspend fun md5(path: String): String? = null
    override suspend fun validateFirmware(path: String) = FirmwareCheck(valid = valid, allowKeepSettings = true)
    override suspend fun startUpgrade(keepSettings: Boolean) {
        upgradedWithKeep = keepSettings
    }
}

class FirmwareFlowsTest {

    private val images = listOf(
        FirmwareImage("x-sysupgrade.bin", "sysupgrade", "aa", 1000),
        FirmwareImage("x-factory.bin", "factory", "bb", 1000)
    )

    @Test
    fun check_findsProfileByBoardAndKeepsUserPackages() = runTest {
        val check = OpenWrtFlasher(FakeSource(images), FakeSystem()).check() as FlashCheck.Compatible

        assertEquals("tplink_archer-c6-v2", check.profileId)
        assertEquals("23.05.5", check.currentVersion)
        assertEquals("25.12.6", check.newVersion)
        assertEquals(listOf("luci", "tcpdump"), check.installedPackages)
    }

    @Test
    fun check_refusesOtherHardwareRevision() = runTest {
        val result = OpenWrtFlasher(FakeSource(images), FakeSystem(boardName = "tplink,archer-c6-v3")).check()

        assertEquals(IncompatibleReason.NOT_IN_RELEASE, (result as FlashCheck.Incompatible).reason)
    }

    @Test
    fun buildDownloadAndFlash_happyPath() = runTest {
        val source = FakeSource(images)
        val system = FakeSystem()
        val flasher = OpenWrtFlasher(source, system, pollDelayMs = 0)
        val check = flasher.check() as FlashCheck.Compatible
        val statuses = mutableListOf<BuildStatus>()

        val done = flasher.build(check, setOf(PackagePreset.LUCI_PT_BR), firstBoot = null) { statuses += it }
        val image = flasher.download(done) {}
        val outcome = flasher.flash(image, keepSettings = true)

        assertTrue(statuses.first() is BuildStatus.Queued)
        assertTrue(statuses.any { it is BuildStatus.Building })
        assertEquals(listOf("luci", "tcpdump", "luci-i18n-base-pt-br"), source.submitted.single().first)
        assertNull("keeping settings: no first-boot script", source.submitted.single().second)
        assertEquals("x-sysupgrade.bin", image.name)
        assertEquals(FlashOutcome.Started, outcome)
        assertEquals(true, system.upgradedWithKeep)
    }

    @Test
    fun flash_stopsBeforeWritingWhenSomethingIsWrong() = runTest {
        val image = VerifiedImage("x", sha256(ByteArray(1000)), ByteArray(1000))

        val lowMemory = FakeSystem(memory = 1_000)
        assertEquals(FlashOutcome.NotEnoughMemory, OpenWrtFlasher(FakeSource(images), lowMemory).flash(image, true))
        assertNull(lowMemory.uploaded)

        val corrupted = FakeSystem(receivedSha = "deadbeef")
        assertEquals(FlashOutcome.TransferCorrupted, OpenWrtFlasher(FakeSource(images), corrupted).flash(image, true))
        assertNull(corrupted.upgradedWithKeep)

        val rejected = FakeSystem(valid = false)
        assertEquals(FlashOutcome.RejectedByRouter, OpenWrtFlasher(FakeSource(images), rejected).flash(image, true))
        assertNull(rejected.upgradedWithKeep)
    }

    @Test(expected = BuildFailedException::class)
    fun build_failureIsReported() = runTest {
        val flasher = OpenWrtFlasher(FakeSource(images, failBuild = true), FakeSystem(), pollDelayMs = 0)
        flasher.build(flasher.check() as FlashCheck.Compatible, emptySet(), null) {}
    }

    @Test
    fun factory_requiresFactoryImageAndSendsFirstBootScript() = runTest {
        val device = OverviewProfile("tplink_archer-c6-v2", "ath79/generic", listOf(DeviceTitle("TP-Link", "Archer C6", "v2")))
        val source = FakeSource(images)
        val builder = FactoryImageBuilder(source, pollDelayMs = 0)

        val done = builder.build("25.12.6", device, setOf(PackagePreset.LUCI_PT_BR), FirstBootSettings(wifiName = "Cliente")) {}
        assertEquals("x-factory.bin", builder.download(done) {}.name)
        assertTrue(source.submitted.single().second!!.contains("ssid='Cliente'"))

        val noFactory = FactoryImageBuilder(FakeSource(images.filter { it.type == "sysupgrade" }), pollDelayMs = 0)
        val error = runCatching { noFactory.build("25.12.6", device, emptySet(), FirstBootSettings()) {} }.exceptionOrNull()
        assertTrue(error is NoFactoryImageException)
    }

    @Test
    fun parseBuild_readsAsuAnswers() {
        val json = Json { ignoreUnknownKeys = true }
        val queued = OpenWrtFirmwareService.parseBuild(202, """{"status":202,"detail":"queued","queue_position":3,"request_hash":"abc"}""", json)
        assertEquals(BuildStatus.Queued("abc", 3), queued)
        assertEquals(BuildStatus.Building("abc"), OpenWrtFirmwareService.parseBuild(202, """{"detail":"started","request_hash":"abc"}""", json))
        val done = OpenWrtFirmwareService.parseBuild(
            200,
            """{"detail":"done","request_hash":"abc","bin_dir":"abc","images":[{"name":"i.bin","type":"sysupgrade","sha256":"ff","size":5,"filesystem":"squashfs"}]}""",
            json
        ) as BuildStatus.Done
        assertEquals("i.bin", done.images.single().name)
        assertEquals(BuildStatus.Failed("Error: Impossible package selection"), OpenWrtFirmwareService.parseBuild(500, """{"detail":"Error: Impossible package selection"}""", json))
    }

    @Test
    fun mikrotik_checkInstallAndRouterBoot() = runTest {
        val router = FakeRouterOs().apply {
            singletons["system/package/update"] = mutableMapOf("installed-version" to "7.14.2", "latest-version" to "7.16.1", "channel" to "stable", "status" to "New version is available")
            singletons["system/routerboard"] = mutableMapOf("routerboard" to "true", "current-firmware" to "7.14.2", "upgrade-firmware" to "7.16.1")
        }
        val updater = MikroTikUpdater(router) { 1_700_000_000_000 }

        val update = updater.check()
        assertTrue(update.hasUpdate)
        assertEquals("timasters-update-1700000000", updater.install())
        assertEquals(listOf("timasters-update-1700000000"), router.backups)
        assertEquals(listOf("system/package/update/check-for-updates", "system/package/update/install"), router.commands)
        assertTrue(updater.routerBoot()!!.needsUpgrade)
        updater.upgradeRouterBoot()
        assertEquals(listOf("system/routerboard/upgrade", "system/reboot"), router.commands.takeLast(2))

        router.singletons.getValue("system/package/update")["latest-version"] = "7.14.2"
        assertFalse(updater.check().hasUpdate)
    }
}
