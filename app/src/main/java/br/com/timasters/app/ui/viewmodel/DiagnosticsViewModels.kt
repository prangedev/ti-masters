package br.com.timasters.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.core.network.HostValidator
import br.com.timasters.app.core.network.LatencyStats
import br.com.timasters.app.data.network.CloudflareSpeedApi
import br.com.timasters.app.data.network.ConnectionDetails
import br.com.timasters.app.data.network.ConnectionInfoRepository
import br.com.timasters.app.data.network.PingEvent
import br.com.timasters.app.data.network.PingMethod
import br.com.timasters.app.data.network.PingTool
import br.com.timasters.app.data.network.PublicNetworkInfo
import br.com.timasters.app.data.speedtest.SpeedTestEngine
import br.com.timasters.app.data.speedtest.SpeedTestPhase
import br.com.timasters.app.data.speedtest.SpeedTestProgress
import br.com.timasters.app.data.speedtest.SpeedTestRepository
import br.com.timasters.app.data.speedtest.SpeedTestResult
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SpeedTestUiState(
    /** Null before the first test in this session. */
    val progress: SpeedTestProgress? = null,
    val isRunning: Boolean = false,
    val error: ToolError? = null
)

@HiltViewModel
class SpeedTestViewModel @Inject constructor(
    private val engine: SpeedTestEngine,
    private val repository: SpeedTestRepository,
    connectionInfo: ConnectionInfoRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(SpeedTestUiState())
    val uiState: StateFlow<SpeedTestUiState> = _uiState.asStateFlow()

    val history: StateFlow<List<SpeedTestResult>> =
        repository.history.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Used to warn that the test consumes mobile data. */
    val connection: StateFlow<ConnectionDetails> =
        connectionInfo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), connectionInfo.current())

    private var job: Job? = null

    fun start() {
        if (_uiState.value.isRunning) return
        _uiState.value = SpeedTestUiState(progress = SpeedTestProgress(), isRunning = true)
        job = viewModelScope.launch {
            try {
                engine.run().collect { progress ->
                    _uiState.update { it.copy(progress = progress) }
                    if (progress.phase == SpeedTestPhase.DONE) repository.save(progress)
                }
                _uiState.update { it.copy(isRunning = false) }
            } catch (e: CancellationException) {
                _uiState.update { it.copy(isRunning = false, progress = null) }
                throw e
            } catch (e: Exception) {
                // Back to the idle state, so the gauge does not stay on a half-finished phase.
                _uiState.update { it.copy(isRunning = false, progress = null, error = e.toToolError()) }
            }
        }
    }

    fun cancel() {
        job?.cancel()
        job = null
    }

    fun clearHistory() {
        viewModelScope.launch { repository.clear() }
    }
}

data class PingUiState(
    val host: String = "8.8.8.8",
    val isRunning: Boolean = false,
    val resolvedAddress: String? = null,
    val method: PingMethod? = null,
    /** Reply times in ms, null for lost packets, in order. */
    val replies: List<Double?> = emptyList(),
    val sent: Int = 0,
    val error: ToolError? = null
) {
    val stats: LatencyStats? get() =
        if (sent == 0) null else LatencyStats.from(sent, replies.filterNotNull())
}

@HiltViewModel
class PingViewModel @Inject constructor(
    private val pingTool: PingTool,
    connectionInfo: ConnectionInfoRepository
) : ViewModel() {

    /** Offered as a one-tap target: pinging the router separates Wi-Fi problems from internet problems. */
    val gateway: String? = connectionInfo.current().gateway

    private val _uiState = MutableStateFlow(PingUiState())
    val uiState: StateFlow<PingUiState> = _uiState.asStateFlow()
    private var job: Job? = null

    fun onHostChange(value: String) = _uiState.update { it.copy(host = value.take(253), error = null) }

    fun start() {
        val host = _uiState.value.host
        if (!HostValidator.isValid(host)) {
            _uiState.update { it.copy(error = ToolError.INVALID_HOST) }
            return
        }
        job?.cancel()
        _uiState.value = PingUiState(host = host, isRunning = true)
        job = viewModelScope.launch {
            try {
                pingTool.ping(host).collect { event ->
                    _uiState.update { state ->
                        when (event) {
                            is PingEvent.Resolved -> state.copy(resolvedAddress = event.address)
                            is PingEvent.FallingBackToTcp -> state.copy(replies = emptyList(), sent = 0, method = PingMethod.TCP)
                            is PingEvent.Reply -> state.copy(
                                method = event.method,
                                replies = state.replies + event.timeMs,
                                sent = state.sent + 1
                            )
                            is PingEvent.Lost -> state.copy(
                                method = event.method,
                                replies = state.replies + null,
                                sent = state.sent + 1
                            )
                        }
                    }
                }
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

data class PublicInfoState(
    val info: PublicNetworkInfo? = null,
    val isLoading: Boolean = false,
    val failed: Boolean = false
)

@HiltViewModel
class ConnectionInfoViewModel @Inject constructor(
    connectionInfo: ConnectionInfoRepository,
    private val cloudflare: CloudflareSpeedApi
) : ViewModel() {

    val connection: StateFlow<ConnectionDetails> =
        connectionInfo.observe().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), connectionInfo.current())

    private val _publicInfo = MutableStateFlow(PublicInfoState())
    val publicInfo: StateFlow<PublicInfoState> = _publicInfo.asStateFlow()

    init {
        refreshPublicInfo()
    }

    fun refreshPublicInfo() {
        if (_publicInfo.value.isLoading) return
        _publicInfo.update { it.copy(isLoading = true, failed = false) }
        viewModelScope.launch {
            _publicInfo.value = try {
                PublicInfoState(info = cloudflare.fetchPublicInfo())
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                PublicInfoState(failed = true)
            }
        }
    }
}
