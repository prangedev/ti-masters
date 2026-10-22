package br.com.timasters.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.data.network.ConnectionInfoRepository
import br.com.timasters.app.data.routers.config.AppliedOp
import br.com.timasters.app.data.routers.config.ApplyResult
import br.com.timasters.app.data.routers.config.ConfigPlan
import br.com.timasters.app.data.routers.config.ConfigTask
import br.com.timasters.app.data.routers.config.ConnectResult
import br.com.timasters.app.data.routers.config.RouterConfigurator
import br.com.timasters.app.data.routers.config.RouterEndpoint
import br.com.timasters.app.data.routers.config.RouterSession
import br.com.timasters.app.data.routers.config.RouterSessionStore
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

sealed interface ConfigConnection {
    data object Idle : ConfigConnection
    data object Connecting : ConfigConnection
    data object WrongPassword : ConfigConnection
    data class Unreachable(val message: String) : ConfigConnection

    /** Logged in to a router the app can change directly. */
    data class Connected(val session: RouterSession) : ConfigConnection

    /** No automatic access: tasks produce filled-in manual steps. [unsupported] = the router was reached but isn't compatible. */
    data class Manual(val unsupported: Boolean) : ConfigConnection
}

/** Where the technician is inside the configurator, after connecting. */
sealed interface ConfigStage {
    data object Tasks : ConfigStage
    data class Form(val kind: TaskKind) : ConfigStage
    data object Reading : ConfigStage
    data class Preview(val plan: ConfigPlan) : ConfigStage
    data class Manual(val task: ConfigTask) : ConfigStage
    data class Applying(val plan: ConfigPlan) : ConfigStage
    data class Done(val plan: ConfigPlan, val result: ApplyResult) : ConfigStage
    data object Undoing : ConfigStage
    data class Undone(val result: ApplyResult) : ConfigStage
    data class Error(val message: String) : ConfigStage
}

enum class TaskKind { PPPOE, DHCP, BRIDGE, MESH }

/** "192.168.88.1" or "192.168.88.1:8080" → address and optional port. */
fun parseHost(input: String): Pair<String, Int?> {
    val text = input.trim().removePrefix("http://").removePrefix("https://").trimEnd('/')
    val port = text.substringAfter(':', "").toIntOrNull()?.takeIf { it in 1..65535 }
    return text.substringBefore(':') to port
}

@HiltViewModel
class RouterConfigViewModel @Inject constructor(
    private val configurator: RouterConfigurator,
    private val connectionInfo: ConnectionInfoRepository,
    private val sessionStore: RouterSessionStore
) : ViewModel() {

    private val _connection = MutableStateFlow<ConfigConnection>(ConfigConnection.Idle)
    val connection: StateFlow<ConfigConnection> = _connection.asStateFlow()

    private val _stage = MutableStateFlow<ConfigStage>(ConfigStage.Tasks)
    val stage: StateFlow<ConfigStage> = _stage.asStateFlow()

    /** Changes of the last successful apply, kept so they can be undone. */
    private var lastApplied: List<AppliedOp> = emptyList()

    init {
        // The Firmware tab logs in again after a reboot; follow the session it stores.
        viewModelScope.launch {
            sessionStore.session.collect { session ->
                val current = _connection.value
                when {
                    session != null && (current as? ConfigConnection.Connected)?.session !== session ->
                        _connection.value = ConfigConnection.Connected(session)
                    session == null && current is ConfigConnection.Connected -> _connection.value = ConfigConnection.Idle
                }
            }
        }
    }

    fun defaultHost(): String = connectionInfo.current().gateway.orEmpty()

    fun connect(host: String, https: Boolean, user: String, password: String) {
        if (_connection.value == ConfigConnection.Connecting) return
        _connection.value = ConfigConnection.Connecting
        viewModelScope.launch {
            val (address, port) = parseHost(host)
            val endpoint = port?.let { RouterEndpoint(address, https, it) } ?: RouterEndpoint(address, https)
            _connection.value = runCatching { configurator.connect(endpoint, user.trim(), password) }.fold(
                onSuccess = { result ->
                    when (result) {
                        is ConnectResult.Connected -> {
                            sessionStore.set(result.session, RouterSessionStore.Login(endpoint, user.trim(), password))
                            ConfigConnection.Connected(result.session)
                        }
                        ConnectResult.WrongPassword -> ConfigConnection.WrongPassword
                        ConnectResult.Unsupported -> ConfigConnection.Manual(unsupported = true)
                        is ConnectResult.Unreachable -> ConfigConnection.Unreachable(result.message)
                    }
                },
                onFailure = { ConfigConnection.Unreachable(it.message.orEmpty()) }
            )
            _stage.value = ConfigStage.Tasks
        }
    }

    fun useManualMode() {
        _connection.value = ConfigConnection.Manual(unsupported = false)
        _stage.value = ConfigStage.Tasks
    }

    fun disconnect() {
        sessionStore.clear()
        _connection.value = ConfigConnection.Idle
        _stage.value = ConfigStage.Tasks
        lastApplied = emptyList()
    }

    fun openTask(kind: TaskKind) {
        _stage.value = ConfigStage.Form(kind)
    }

    fun backToTasks() {
        _stage.value = ConfigStage.Tasks
    }

    /** Reads the router and builds the preview; in manual mode shows the filled-in steps instead. */
    fun review(task: ConfigTask) {
        val session = (_connection.value as? ConfigConnection.Connected)?.session
        if (session == null) {
            _stage.value = ConfigStage.Manual(task)
            return
        }
        _stage.value = ConfigStage.Reading
        viewModelScope.launch {
            _stage.value = runCatching { configurator.plan(session, task) }.fold(
                onSuccess = { ConfigStage.Preview(it) },
                onFailure = { ConfigStage.Error(it.message.orEmpty()) }
            )
        }
    }

    fun apply(plan: ConfigPlan) {
        val session = (_connection.value as? ConfigConnection.Connected)?.session ?: return
        if (_stage.value is ConfigStage.Applying) return
        _stage.value = ConfigStage.Applying(plan)
        viewModelScope.launch {
            val result = runCatching { session.executor.apply(plan) }.getOrElse { ApplyResult.Failed(it.message.orEmpty(), rolledBack = false) }
            lastApplied = (result as? ApplyResult.Success)?.applied.orEmpty()
            _stage.value = ConfigStage.Done(plan, result)
        }
    }

    val canUndo: Boolean get() = lastApplied.isNotEmpty()

    fun undo() {
        val session = (_connection.value as? ConfigConnection.Connected)?.session ?: return
        if (lastApplied.isEmpty()) return
        val toUndo = lastApplied
        _stage.value = ConfigStage.Undoing
        viewModelScope.launch {
            val result = runCatching { session.executor.undo(toUndo) }.getOrElse { ApplyResult.Failed(it.message.orEmpty(), rolledBack = false) }
            if (result is ApplyResult.Success) lastApplied = emptyList()
            _stage.value = ConfigStage.Undone(result)
        }
    }
}
