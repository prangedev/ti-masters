package br.com.timasters.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.data.network.ConnectionInfoRepository
import br.com.timasters.app.data.routers.LatestRouterOs
import br.com.timasters.app.data.routers.RouterCatalogRepository
import br.com.timasters.app.data.routers.RouterDetection
import br.com.timasters.app.data.routers.RouterEntry
import br.com.timasters.app.data.routers.RouterGuide
import br.com.timasters.app.data.routers.RouterMatcher
import br.com.timasters.app.data.routers.RouterProbe
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

private val WHILE_SUBSCRIBED_ROUTERS = SharingStarted.WhileSubscribed(5_000)

sealed interface DetectState {
    data object Idle : DetectState
    data object Running : DetectState
    data class Done(val detection: RouterDetection, val latest: LatestRouterOs? = null) : DetectState
}

@HiltViewModel
class MyRouterViewModel @Inject constructor(
    private val probe: RouterProbe,
    connectionInfo: ConnectionInfoRepository
) : ViewModel() {

    private val _state = MutableStateFlow<DetectState>(DetectState.Idle)
    val state: StateFlow<DetectState> = _state.asStateFlow()

    /** Gateway of the current network (null without Wi-Fi/cable), refreshed when the network changes. */
    val gateway: StateFlow<String?> = connectionInfo.observe().map { it.gateway }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_ROUTERS, null)

    fun detect() {
        if (_state.value == DetectState.Running) return
        _state.value = DetectState.Running
        viewModelScope.launch {
            val detection = probe.detect()
            _state.value = DetectState.Done(detection)
            detection.mikrotik?.versionNumber?.let { installed ->
                val latest = probe.latestRouterOs(installed)
                _state.value = DetectState.Done(detection, latest)
            }
        }
    }
}

@HiltViewModel
class RouterCatalogViewModel @Inject constructor(catalog: RouterCatalogRepository) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    val entries: StateFlow<List<RouterEntry>> = combine(flow { emit(catalog.brands()) }, _query) { brands, query ->
        RouterMatcher.search(brands, query)
    }.stateIn(viewModelScope, WHILE_SUBSCRIBED_ROUTERS, emptyList())

    fun onQueryChange(value: String) {
        _query.value = value
    }
}

@HiltViewModel
class RouterModelViewModel @Inject constructor(private val catalog: RouterCatalogRepository) : ViewModel() {

    private val _entry = MutableStateFlow<RouterEntry?>(null)
    val entry: StateFlow<RouterEntry?> = _entry.asStateFlow()

    fun load(key: String) {
        if (_entry.value?.key == key) return
        viewModelScope.launch { _entry.value = catalog.entry(key) }
    }
}

@HiltViewModel
class RouterGuidesViewModel @Inject constructor(catalog: RouterCatalogRepository) : ViewModel() {

    val guides: StateFlow<List<RouterGuide>> = flow { emit(catalog.guides()) }
        .stateIn(viewModelScope, WHILE_SUBSCRIBED_ROUTERS, emptyList())
}
