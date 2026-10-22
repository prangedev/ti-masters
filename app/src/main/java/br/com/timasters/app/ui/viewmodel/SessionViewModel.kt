package br.com.timasters.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.data.auth.AuthError
import br.com.timasters.app.data.auth.AuthRepository
import br.com.timasters.app.data.auth.AuthState
import br.com.timasters.app.data.auth.toAuthError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class TermsUiState(
    val acceptanceRequired: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: AuthError? = null
)

/** Decides what the app shows: loading, sign-in, new password, terms update or the main shell. */
@HiltViewModel
class SessionViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    val authState: StateFlow<AuthState> = authRepository.authState
        .stateIn(viewModelScope, SharingStarted.Eagerly, AuthState.Loading)

    private val _termsState = MutableStateFlow(TermsUiState())
    val termsState: StateFlow<TermsUiState> = _termsState.asStateFlow()

    init {
        // Re-check consent whenever a (different) user signs in. Offline: don't block the app.
        viewModelScope.launch {
            authState
                .map { (it as? AuthState.SignedIn)?.userId }
                .distinctUntilChanged()
                .collect { userId ->
                    _termsState.value = TermsUiState()
                    if (userId != null && authRepository.hasAcceptedCurrentTerms() == false) {
                        _termsState.update { it.copy(acceptanceRequired = true) }
                    }
                }
        }
    }

    fun acceptTerms() {
        if (_termsState.value.isSubmitting) return
        _termsState.update { it.copy(isSubmitting = true, error = null) }
        viewModelScope.launch {
            try {
                authRepository.acceptCurrentTerms()
                _termsState.value = TermsUiState()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _termsState.update { it.copy(isSubmitting = false, error = e.toAuthError()) }
            }
        }
    }

    fun signOut() {
        viewModelScope.launch { authRepository.signOut() }
    }
}
