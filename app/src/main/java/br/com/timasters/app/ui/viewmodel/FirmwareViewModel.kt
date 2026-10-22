package br.com.timasters.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.data.routers.config.ConnectResult
import br.com.timasters.app.data.routers.config.MikroTikSession
import br.com.timasters.app.data.routers.config.OpenWrtSession
import br.com.timasters.app.data.routers.config.RouterConfigurator
import br.com.timasters.app.data.routers.config.RouterPlatform
import br.com.timasters.app.data.routers.config.RouterSession
import br.com.timasters.app.data.routers.config.RouterSessionStore
import br.com.timasters.app.data.routers.firmware.BuildFailedException
import br.com.timasters.app.data.routers.firmware.BuildStatus
import br.com.timasters.app.data.routers.firmware.ChecksumMismatchException
import br.com.timasters.app.data.routers.firmware.FactoryImageBuilder
import br.com.timasters.app.data.routers.firmware.FirstBootSettings
import br.com.timasters.app.data.routers.firmware.FlashCheck
import br.com.timasters.app.data.routers.firmware.FlashOutcome
import br.com.timasters.app.data.routers.firmware.IncompatibleReason
import br.com.timasters.app.data.routers.firmware.MikroTikUpdater
import br.com.timasters.app.data.routers.firmware.NoFactoryImageException
import br.com.timasters.app.data.routers.firmware.OpenWrtFirmwareService
import br.com.timasters.app.data.routers.firmware.OpenWrtFlasher
import br.com.timasters.app.data.routers.firmware.OverviewProfile
import br.com.timasters.app.data.routers.firmware.PackagePreset
import br.com.timasters.app.data.routers.firmware.FirmwareRules
import br.com.timasters.app.data.routers.firmware.RouterBoot
import br.com.timasters.app.data.routers.firmware.RouterOsUpdate
import br.com.timasters.app.data.routers.firmware.VerifiedImage
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/** Why a step failed, so the screen can explain it in Portuguese. */
enum class FirmwareError { NO_INTERNET, BUILD_FAILED, CHECKSUM, NO_FACTORY_IMAGE, NOT_ENOUGH_MEMORY, TRANSFER, REJECTED, ROUTER, REBOOT_TIMEOUT }

sealed interface OpenWrtFirmwareState {
    data object Idle : OpenWrtFirmwareState
    data object Checking : OpenWrtFirmwareState
    data class Incompatible(val reason: IncompatibleReason, val detail: String) : OpenWrtFirmwareState
    data class Ready(val check: FlashCheck.Compatible) : OpenWrtFirmwareState
    data class Building(val check: FlashCheck.Compatible, val status: BuildStatus) : OpenWrtFirmwareState
    data class Downloading(val check: FlashCheck.Compatible, val progress: Float) : OpenWrtFirmwareState
    data class Downloaded(val check: FlashCheck.Compatible, val image: VerifiedImage, val keepSettings: Boolean) : OpenWrtFirmwareState
    data object Flashing : OpenWrtFirmwareState
    data class Rebooting(val keepSettings: Boolean) : OpenWrtFirmwareState

    /** [version] = what the router reports after the reboot; null when it came back with other settings. */
    data class Finished(val version: String?) : OpenWrtFirmwareState

    /** [nothingWritten] = the router flash was not touched (safe to retry). */
    data class Failed(val error: FirmwareError, val detail: String, val nothingWritten: Boolean) : OpenWrtFirmwareState
}

sealed interface MikroTikFirmwareState {
    data object Idle : MikroTikFirmwareState
    data object Checking : MikroTikFirmwareState
    data class Checked(val update: RouterOsUpdate, val boot: RouterBoot?, val justUpdated: Boolean = false) : MikroTikFirmwareState
    data object Installing : MikroTikFirmwareState
    data object Rebooting : MikroTikFirmwareState
    data class Failed(val error: FirmwareError, val detail: String) : MikroTikFirmwareState
}

sealed interface FactoryFirmwareState {
    data object Idle : FactoryFirmwareState
    data object Loading : FactoryFirmwareState
    data class Results(val devices: List<OverviewProfile>) : FactoryFirmwareState
    data class Selected(val device: OverviewProfile) : FactoryFirmwareState
    data class Building(val device: OverviewProfile, val status: BuildStatus) : FactoryFirmwareState
    data class Downloading(val device: OverviewProfile, val progress: Float) : FactoryFirmwareState
    data class Saved(val device: OverviewProfile, val file: File, val sha256: String) : FactoryFirmwareState
    data class Failed(val error: FirmwareError, val detail: String) : FactoryFirmwareState
}

@HiltViewModel
class FirmwareViewModel @Inject constructor(
    private val sessionStore: RouterSessionStore,
    private val configurator: RouterConfigurator,
    private val firmwareService: OpenWrtFirmwareService,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    val session: StateFlow<RouterSession?> = sessionStore.session

    private val _openWrt = MutableStateFlow<OpenWrtFirmwareState>(OpenWrtFirmwareState.Idle)
    val openWrt: StateFlow<OpenWrtFirmwareState> = _openWrt.asStateFlow()

    private val _mikroTik = MutableStateFlow<MikroTikFirmwareState>(MikroTikFirmwareState.Idle)
    val mikroTik: StateFlow<MikroTikFirmwareState> = _mikroTik.asStateFlow()

    private val _factory = MutableStateFlow<FactoryFirmwareState>(FactoryFirmwareState.Idle)
    val factory: StateFlow<FactoryFirmwareState> = _factory.asStateFlow()

    private var stableVersion: String? = null

    // region OpenWrt already installed

    private fun flasher(): OpenWrtFlasher? =
        (session.value as? OpenWrtSession)?.system?.let { OpenWrtFlasher(firmwareService, it) }

    fun checkOpenWrt() {
        val flasher = flasher() ?: return
        _openWrt.value = OpenWrtFirmwareState.Checking
        viewModelScope.launch {
            _openWrt.value = try {
                when (val check = flasher.check()) {
                    is FlashCheck.Compatible -> OpenWrtFirmwareState.Ready(check)
                    is FlashCheck.Incompatible -> OpenWrtFirmwareState.Incompatible(check.reason, check.detail)
                }
            } catch (e: Exception) {
                OpenWrtFirmwareState.Failed(FirmwareError.NO_INTERNET, e.message.orEmpty(), nothingWritten = true)
            }
        }
    }

    /** Builds the image on OpenWrt's server and downloads it (checksum verified). Nothing touches the router yet. */
    fun buildOpenWrt(check: FlashCheck.Compatible, presets: Set<PackagePreset>, keepSettings: Boolean, firstBoot: FirstBootSettings) {
        val flasher = flasher() ?: return
        viewModelScope.launch {
            try {
                val done = flasher.build(check, presets, if (keepSettings) null else firstBoot) { status ->
                    _openWrt.value = OpenWrtFirmwareState.Building(check, status)
                }
                val image = flasher.download(done) { _openWrt.value = OpenWrtFirmwareState.Downloading(check, it) }
                _openWrt.value = OpenWrtFirmwareState.Downloaded(check, image, keepSettings)
            } catch (e: Exception) {
                _openWrt.value = OpenWrtFirmwareState.Failed(buildError(e), e.message.orEmpty(), nothingWritten = true)
            }
        }
    }

    fun flashOpenWrt(state: OpenWrtFirmwareState.Downloaded) {
        val flasher = flasher() ?: return
        if (_openWrt.value == OpenWrtFirmwareState.Flashing) return
        _openWrt.value = OpenWrtFirmwareState.Flashing
        viewModelScope.launch {
            val outcome = try {
                flasher.flash(state.image, state.keepSettings)
            } catch (e: Exception) {
                _openWrt.value = OpenWrtFirmwareState.Failed(FirmwareError.ROUTER, e.message.orEmpty(), nothingWritten = true)
                return@launch
            }
            when (outcome) {
                FlashOutcome.Started -> {
                    _openWrt.value = OpenWrtFirmwareState.Rebooting(state.keepSettings)
                    val back = waitForRouter(RouterPlatform.OPENWRT)
                    _openWrt.value = when {
                        back != null -> OpenWrtFirmwareState.Finished(back.info.version)
                        !state.keepSettings -> OpenWrtFirmwareState.Finished(null)
                        else -> OpenWrtFirmwareState.Failed(FirmwareError.REBOOT_TIMEOUT, "", nothingWritten = false)
                    }
                }
                FlashOutcome.NotEnoughMemory -> fail(FirmwareError.NOT_ENOUGH_MEMORY)
                FlashOutcome.TransferCorrupted -> fail(FirmwareError.TRANSFER)
                FlashOutcome.RejectedByRouter -> fail(FirmwareError.REJECTED)
            }
        }
    }

    private fun fail(error: FirmwareError) {
        _openWrt.value = OpenWrtFirmwareState.Failed(error, "", nothingWritten = true)
    }

    fun resetOpenWrt() {
        _openWrt.value = OpenWrtFirmwareState.Idle
    }

    // endregion

    // region MikroTik

    private fun updater(): MikroTikUpdater? = (session.value as? MikroTikSession)?.client?.let { MikroTikUpdater(it) }

    fun checkMikroTik(justUpdated: Boolean = false) {
        val updater = updater() ?: return
        _mikroTik.value = MikroTikFirmwareState.Checking
        viewModelScope.launch {
            _mikroTik.value = try {
                MikroTikFirmwareState.Checked(updater.check(), updater.routerBoot(), justUpdated)
            } catch (e: Exception) {
                MikroTikFirmwareState.Failed(FirmwareError.ROUTER, e.message.orEmpty())
            }
        }
    }

    fun installRouterOs() {
        val updater = updater() ?: return
        _mikroTik.value = MikroTikFirmwareState.Installing
        viewModelScope.launch {
            try {
                updater.install()
            } catch (e: Exception) {
                _mikroTik.value = MikroTikFirmwareState.Failed(FirmwareError.ROUTER, e.message.orEmpty())
                return@launch
            }
            afterMikroTikReboot()
        }
    }

    fun upgradeRouterBoot() {
        val updater = updater() ?: return
        _mikroTik.value = MikroTikFirmwareState.Installing
        viewModelScope.launch {
            try {
                updater.upgradeRouterBoot()
            } catch (e: Exception) {
                _mikroTik.value = MikroTikFirmwareState.Failed(FirmwareError.ROUTER, e.message.orEmpty())
                return@launch
            }
            afterMikroTikReboot()
        }
    }

    private suspend fun afterMikroTikReboot() {
        _mikroTik.value = MikroTikFirmwareState.Rebooting
        if (waitForRouter(RouterPlatform.MIKROTIK) != null) checkMikroTik(justUpdated = true)
        else _mikroTik.value = MikroTikFirmwareState.Failed(FirmwareError.REBOOT_TIMEOUT, "")
    }

    // endregion

    // region First install on stock firmware

    fun searchDevices(query: String) {
        _factory.value = FactoryFirmwareState.Loading
        viewModelScope.launch {
            _factory.value = try {
                val version = stableVersion ?: firmwareService.stableVersion().also { stableVersion = it }
                FactoryFirmwareState.Results(FirmwareRules.search(firmwareService.overview(version), query))
            } catch (e: Exception) {
                FactoryFirmwareState.Failed(FirmwareError.NO_INTERNET, e.message.orEmpty())
            }
        }
    }

    fun selectDevice(device: OverviewProfile) {
        _factory.value = FactoryFirmwareState.Selected(device)
    }

    fun buildFactory(device: OverviewProfile, presets: Set<PackagePreset>, firstBoot: FirstBootSettings) {
        viewModelScope.launch {
            try {
                val version = stableVersion ?: firmwareService.stableVersion().also { stableVersion = it }
                val builder = FactoryImageBuilder(firmwareService)
                val done = builder.build(version, device, presets, firstBoot) { _factory.value = FactoryFirmwareState.Building(device, it) }
                val image = builder.download(done) { _factory.value = FactoryFirmwareState.Downloading(device, it) }
                _factory.value = FactoryFirmwareState.Saved(device, save(image), image.sha256)
            } catch (e: Exception) {
                _factory.value = FactoryFirmwareState.Failed(buildError(e), e.message.orEmpty())
            }
        }
    }

    fun resetFactory() {
        _factory.value = FactoryFirmwareState.Idle
    }

    /** Kept in files/firmware (shared through the FileProvider); older images are removed. */
    private suspend fun save(image: VerifiedImage): File = withContext(Dispatchers.IO) {
        val dir = File(context.filesDir, "firmware").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        File(dir, image.name).apply { writeBytes(image.bytes) }
    }

    // endregion

    /** Waits for the router to come back after a reboot and logs in again with the same credentials. */
    private suspend fun waitForRouter(platform: RouterPlatform): RouterSession? {
        val login = sessionStore.login ?: return null
        delay(REBOOT_GRACE_MS)
        repeat(RECONNECT_ATTEMPTS) {
            val result = runCatching { configurator.connect(login.endpoint, login.user, login.password) }.getOrNull()
            val session = (result as? ConnectResult.Connected)?.session
            if (session != null && session.info.platform == platform) {
                sessionStore.set(session, login)
                return session
            }
            delay(RECONNECT_INTERVAL_MS)
        }
        return null
    }

    private fun buildError(e: Exception): FirmwareError = when (e) {
        is NoFactoryImageException -> FirmwareError.NO_FACTORY_IMAGE
        is ChecksumMismatchException -> FirmwareError.CHECKSUM
        is BuildFailedException -> FirmwareError.BUILD_FAILED
        else -> FirmwareError.NO_INTERNET
    }

    private companion object {
        const val REBOOT_GRACE_MS = 60_000L
        const val RECONNECT_ATTEMPTS = 36
        const val RECONNECT_INTERVAL_MS = 10_000L
    }
}
