package br.com.timasters.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.core.network.HostValidator
import br.com.timasters.app.core.network.Ports
import br.com.timasters.app.data.lan.LanScanProgress
import br.com.timasters.app.data.lan.LanScanner
import br.com.timasters.app.data.lan.PortScanProgress
import br.com.timasters.app.data.lan.PortScanner
import br.com.timasters.app.data.network.ConnectionInfoRepository
import br.com.timasters.app.data.preferences.AppPreferencesRepository
import br.com.timasters.app.data.wifi.WifiAccess
import br.com.timasters.app.data.wifi.WifiConnection
import br.com.timasters.app.data.wifi.WifiNetwork
import br.com.timasters.app.data.wifi.WifiRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.runningFold
import kotlinx.coroutines.flow.shareIn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
@HiltViewModel
class WifiViewModel @Inject constructor(
    private val wifiRepository: WifiRepository
) : ViewModel() {

    private val _access = MutableStateFlow(wifiRepository.access())

    /** Re-read when the screen resumes (the user may have granted permission or turned location on). */
    val access: StateFlow<WifiAccess> = _access.asStateFlow()

    private val liveConnection = wifiRepository.liveConnection()
        .shareIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), replay = 1)

    val connection: StateFlow<WifiConnection?> = liveConnection
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    /** One reading per second for the last minute. */
    val rssiHistory: StateFlow<List<Int>> = liveConnection
        .runningFold(emptyList<Int>()) { history, connection ->
            if (connection == null) emptyList() else (history + connection.rssiDbm).takeLast(RSSI_HISTORY_SIZE)
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val nearbyNetworks: StateFlow<List<WifiNetwork>> = _access
        .flatMapLatest { if (it.canScan) wifiRepository.nearbyNetworks() else emptyFlow() }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun refreshAccess() {
        _access.value = wifiRepository.access()
    }

    companion object {
        const val RSSI_HISTORY_SIZE = 60
    }
}

/** Shared by the local network and port scanners: the "authorized use only" notice. */
abstract class ScannerViewModel(
    private val preferences: AppPreferencesRepository
) : ViewModel() {

    val noticeAccepted: StateFlow<Boolean?> = preferences.scannerNoticeAccepted
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun acceptNotice() {
        viewModelScope.launch { preferences.acceptScannerNotice() }
    }
}

data class LanScanUiState(
    val progress: LanScanProgress? = null,
    val isRunning: Boolean = false,
    val error: ToolError? = null
)

@HiltViewModel
class LanScanViewModel @Inject constructor(
    private val scanner: LanScanner,
    preferences: AppPreferencesRepository
) : ScannerViewModel(preferences) {

    private val _uiState = MutableStateFlow(LanScanUiState())
    val uiState: StateFlow<LanScanUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    fun start() {
        if (_uiState.value.isRunning) return
        _uiState.value = LanScanUiState(isRunning = true)
        job = viewModelScope.launch {
            try {
                scanner.scan().collect { progress -> _uiState.update { it.copy(progress = progress) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.toToolError()) }
            } finally {
                _uiState.update { it.copy(isRunning = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}

enum class PortScanMode { COMMON, CUSTOM }

data class PortScanUiState(
    val host: String = "",
    val mode: PortScanMode = PortScanMode.COMMON,
    val customPorts: String = "1-1024",
    val progress: PortScanProgress? = null,
    val isRunning: Boolean = false,
    val error: ToolError? = null
)

@HiltViewModel
class PortScanViewModel @Inject constructor(
    private val scanner: PortScanner,
    connectionInfo: ConnectionInfoRepository,
    preferences: AppPreferencesRepository
) : ScannerViewModel(preferences) {

    // The router is the most common target, so it is suggested by default.
    private val _uiState = MutableStateFlow(PortScanUiState(host = connectionInfo.current().gateway.orEmpty()))
    val uiState: StateFlow<PortScanUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    fun onHostChange(value: String) = _uiState.update { it.copy(host = value.take(253), error = null) }
    fun onModeChange(mode: PortScanMode) = _uiState.update { it.copy(mode = mode, error = null) }
    fun onCustomPortsChange(value: String) = _uiState.update { it.copy(customPorts = value.take(200), error = null) }

    /** Lets the device list hand a host over to the port scanner. */
    fun scanHost(host: String) {
        _uiState.update { it.copy(host = host) }
        start()
    }

    fun start() {
        val state = _uiState.value
        if (state.isRunning) return
        if (!HostValidator.isValid(state.host)) {
            _uiState.update { it.copy(error = ToolError.INVALID_HOST) }
            return
        }
        val ports = when (state.mode) {
            PortScanMode.COMMON -> Ports.COMMON_SERVICES.keys.toList()
            PortScanMode.CUSTOM -> Ports.parse(state.customPorts)
        }
        if (ports == null) {
            _uiState.update { it.copy(error = ToolError.INVALID_PORTS) }
            return
        }
        _uiState.update { it.copy(isRunning = true, progress = null, error = null) }
        job = viewModelScope.launch {
            try {
                scanner.scan(state.host, ports).collect { progress -> _uiState.update { it.copy(progress = progress) } }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.toToolError()) }
            } finally {
                _uiState.update { it.copy(isRunning = false) }
            }
        }
    }

    fun stop() {
        job?.cancel()
    }
}
