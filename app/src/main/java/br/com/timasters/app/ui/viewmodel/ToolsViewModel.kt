package br.com.timasters.app.ui.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.data.tools.CertificateInfo
import br.com.timasters.app.data.tools.DnsLookupResult
import br.com.timasters.app.data.tools.DnsServerScore
import br.com.timasters.app.data.tools.DnsType
import br.com.timasters.app.data.tools.NetworkTools
import br.com.timasters.app.data.tools.SiteCheckResult
import br.com.timasters.app.data.tools.TraceHop
import br.com.timasters.app.data.tools.WakeOnLanPacket
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.inject.Inject

/** State of one asynchronous tool run. */
sealed interface ToolState<out T> {
    data object Idle : ToolState<Nothing>
    data object Running : ToolState<Nothing>
    data class Done<T>(val value: T) : ToolState<T>
    data class Error(val kind: ToolFailure, val detail: String = "") : ToolState<Nothing>
}

enum class ToolFailure { INVALID_INPUT, UNKNOWN_HOST, TIMEOUT, NO_NETWORK, FAILED }

data class TraceState(val running: Boolean = false, val hops: List<TraceHop> = emptyList(), val error: ToolFailure? = null)

@Serializable
data class PortEntry(val port: String, val proto: String, val service: String, val notes: String = "")

@Serializable
private data class PortsFile(val version: Int, val ports: List<PortEntry>)

@HiltViewModel
class ToolsViewModel @Inject constructor(
    private val tools: NetworkTools,
    private val json: Json,
    @param:ApplicationContext private val context: Context
) : ViewModel() {

    private val _trace = MutableStateFlow(TraceState())
    val trace: StateFlow<TraceState> = _trace.asStateFlow()
    private var traceJob: Job? = null

    private val _dns = MutableStateFlow<ToolState<DnsLookupResult>>(ToolState.Idle)
    val dns: StateFlow<ToolState<DnsLookupResult>> = _dns.asStateFlow()

    private val _benchmark = MutableStateFlow<ToolState<List<DnsServerScore>>>(ToolState.Idle)
    val benchmark: StateFlow<ToolState<List<DnsServerScore>>> = _benchmark.asStateFlow()

    private val _site = MutableStateFlow<ToolState<SiteCheckResult>>(ToolState.Idle)
    val site: StateFlow<ToolState<SiteCheckResult>> = _site.asStateFlow()

    private val _certificate = MutableStateFlow<ToolState<CertificateInfo>>(ToolState.Idle)
    val certificate: StateFlow<ToolState<CertificateInfo>> = _certificate.asStateFlow()

    private val _wol = MutableStateFlow<ToolState<String>>(ToolState.Idle)
    val wol: StateFlow<ToolState<String>> = _wol.asStateFlow()

    private val _mtu = MutableStateFlow<ToolState<Int?>>(ToolState.Idle)
    val mtu: StateFlow<ToolState<Int?>> = _mtu.asStateFlow()

    private val _ports = MutableStateFlow<List<PortEntry>>(emptyList())
    val ports: StateFlow<List<PortEntry>> = _ports.asStateFlow()

    fun startTrace(host: String) {
        traceJob?.cancel()
        _trace.value = TraceState(running = true)
        traceJob = viewModelScope.launch {
            try {
                tools.traceroute(host).collect { hop -> _trace.value = _trace.value.copy(hops = _trace.value.hops + hop) }
                _trace.value = _trace.value.copy(running = false)
            } catch (e: Exception) {
                _trace.value = _trace.value.copy(running = false, error = classify(e))
            }
        }
    }

    fun stopTrace() {
        traceJob?.cancel()
        _trace.value = _trace.value.copy(running = false)
    }

    fun lookup(name: String, type: DnsType, server: String) = run(_dns) { tools.dnsLookup(name.trim(), type, server.trim().ifBlank { null }) }

    fun runBenchmark() = run(_benchmark) { tools.dnsBenchmark() }

    fun checkSite(url: String) = run(_site) { tools.checkSite(url) }

    fun checkCertificate(host: String, port: String) =
        run(_certificate) { tools.checkCertificate(host.trim(), port.trim().toIntOrNull() ?: throw IllegalArgumentException("port")) }

    fun defaultBroadcast(): String = tools.localBroadcast().orEmpty()

    fun wake(mac: String, broadcast: String) = run(_wol) {
        val bytes = WakeOnLanPacket.parseMac(mac) ?: throw IllegalArgumentException("mac")
        tools.wakeOnLan(bytes, broadcast.trim())
        mac.trim().uppercase()
    }

    fun discoverMtu(host: String) = run(_mtu) { tools.discoverMtu(host) }

    fun loadPorts() {
        if (_ports.value.isNotEmpty()) return
        viewModelScope.launch {
            _ports.value = withContext(Dispatchers.IO) {
                runCatching {
                    context.assets.open("tools/ports.json").bufferedReader().use { json.decodeFromString<PortsFile>(it.readText()).ports }
                }.getOrDefault(emptyList())
            }
        }
    }

    private fun <T> run(target: MutableStateFlow<ToolState<T>>, block: suspend () -> T) {
        if (target.value == ToolState.Running) return
        target.value = ToolState.Running
        viewModelScope.launch {
            target.value = try {
                ToolState.Done(block())
            } catch (e: Exception) {
                ToolState.Error(classify(e), e.message.orEmpty())
            }
        }
    }

    private fun classify(e: Exception): ToolFailure = when (e) {
        is IllegalArgumentException -> ToolFailure.INVALID_INPUT
        is UnknownHostException -> ToolFailure.UNKNOWN_HOST
        is SocketTimeoutException -> ToolFailure.TIMEOUT
        is java.net.ConnectException, is java.net.NoRouteToHostException -> ToolFailure.NO_NETWORK
        else -> ToolFailure.FAILED
    }
}
