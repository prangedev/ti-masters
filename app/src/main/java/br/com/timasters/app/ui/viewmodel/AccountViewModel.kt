package br.com.timasters.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.data.auth.AuthError
import br.com.timasters.app.data.auth.AuthRepository
import br.com.timasters.app.data.business.AccountDataEraser
import br.com.timasters.app.data.auth.AuthState
import br.com.timasters.app.data.auth.toAuthError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AccountActionState(
    val isWorking: Boolean = false,
    val error: AuthError? = null
)

/** Account section of Settings: who is signed in, sign out and account deletion. */
@HiltViewModel
class AccountViewModel @Inject constructor(
    private val authRepository: AuthRepository,
    private val accountDataEraser: AccountDataEraser
) : ViewModel() {

    val email: StateFlow<String?> = authRepository.authState
        .map { (it as? AuthState.SignedIn)?.email }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    private val _actionState = MutableStateFlow(AccountActionState())
    val actionState: StateFlow<AccountActionState> = _actionState.asStateFlow()

    fun signOut() = runAction { authRepository.signOut() }

    /** Cloud files first (they need the session), then the account, then this phone's copies. */
    fun deleteAccount() = runAction {
        accountDataEraser.deleteCloudFiles()
        authRepository.deleteAccount()
        accountDataEraser.deleteLocalData()
    }

    fun dismissError() = _actionState.update { it.copy(error = null) }

    private fun runAction(block: suspend () -> Unit) {
        if (_actionState.value.isWorking) return
        _actionState.value = AccountActionState(isWorking = true)
        viewModelScope.launch {
            try {
                block()
                _actionState.value = AccountActionState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _actionState.value = AccountActionState(error = e.toAuthError())
            }
        }
    }
}
