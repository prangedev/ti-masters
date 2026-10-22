package br.com.timasters.app.data.routers.config

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * The router logged in on the Routers screen, shared by the "Configurar" and "Firmware" tabs.
 * Credentials stay in memory only (never written to disk) so the firmware flow can log in again
 * after the router reboots.
 */
@Singleton
class RouterSessionStore @Inject constructor() {

    data class Login(val endpoint: RouterEndpoint, val user: String, val password: String)

    private val _session = MutableStateFlow<RouterSession?>(null)
    val session: StateFlow<RouterSession?> = _session.asStateFlow()

    var login: Login? = null
        private set

    fun set(session: RouterSession, login: Login) {
        this.login = login
        _session.value = session
    }

    fun clear() {
        login = null
        _session.value = null
    }
}
