package br.com.timasters.app.ui.viewmodel

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import br.com.timasters.app.core.validation.AuthInputValidator
import br.com.timasters.app.data.auth.AuthError
import br.com.timasters.app.data.auth.AuthRepository
import br.com.timasters.app.data.auth.AuthState
import br.com.timasters.app.data.auth.SignUpOutcome
import br.com.timasters.app.data.auth.toAuthError
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class AuthMode { LOGIN, SIGN_UP, FORGOT_PASSWORD }

enum class EmailSentReason { CONFIRM_ACCOUNT, RESET_PASSWORD }

data class AuthUiState(
    val mode: AuthMode = AuthMode.LOGIN,
    val name: String = "",
    val email: String = "",
    val password: String = "",
    val acceptedTerms: Boolean = false,
    /** Field errors appear only after the first submit attempt. */
    val showFieldErrors: Boolean = false,
    val isSubmitting: Boolean = false,
    val error: AuthError? = null,
    val emailSent: EmailSentReason? = null) {
    val isNameValid get() = AuthInputValidator.isValidName(name)
    val isEmailValid get() = AuthInputValidator.isValidEmail(email)
    val isPasswordValid get() = AuthInputValidator.isValidPassword(password)
}

/** Sign-in, sign-up, forgot-password and new-password (after a reset link) forms. */
@HiltViewModel
class AuthViewModel @Inject constructor(
    private val authRepository: AuthRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState: StateFlow<AuthUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            authRepository.pendingLinkError.filterNotNull().collect { error ->
                _uiState.update { it.copy(error = error) }
                authRepository.consumeLinkError()
            }
        }
        // Forget typed passwords once a session exists; the form starts clean after signing out.
        viewModelScope.launch {
            authRepository.authState.collect { state ->
                if (state is AuthState.SignedIn) _uiState.value = AuthUiState()
            }
        }
    }

    fun setMode(mode: AuthMode) {
        _uiState.update {
            it.copy(mode = mode, password = "", showFieldErrors = false, error = null, emailSent = null)
        }
    }

    fun onNameChange(value: String) = _uiState.update { it.copy(name = value.take(AuthInputValidator.MAX_NAME_LENGTH)) }
    fun onEmailChange(value: String) = _uiState.update { it.copy(email = value.trim()) }
    fun onPasswordChange(value: String) = _uiState.update { it.copy(password = value) }
    fun onAcceptedTermsChange(value: Boolean) = _uiState.update { it.copy(acceptedTerms = value) }
    fun dismissError() = _uiState.update { it.copy(error = null) }

    fun dismissEmailSent() {
        _uiState.update { it.copy(emailSent = null, mode = AuthMode.LOGIN, password = "") }
    }

    fun submit() {
        val state = _uiState.value
        val formValid = when (state.mode) {
            AuthMode.LOGIN -> state.isEmailValid && state.password.isNotEmpty()
            AuthMode.SIGN_UP -> state.isNameValid && state.isEmailValid && state.isPasswordValid && state.acceptedTerms
            AuthMode.FORGOT_PASSWORD -> state.isEmailValid
        }
        if (!formValid) {
            _uiState.update { it.copy(showFieldErrors = true) }
            return
        }
        launchAuthCall {
            when (state.mode) {
                AuthMode.LOGIN -> authRepository.signIn(state.email, state.password)
                AuthMode.SIGN_UP -> {
                    val outcome = authRepository.signUp(state.name, state.email, state.password)
                    if (outcome == SignUpOutcome.CONFIRM_EMAIL) {
                        _uiState.update { it.copy(emailSent = EmailSentReason.CONFIRM_ACCOUNT, password = "") }
                    }
                }
                AuthMode.FORGOT_PASSWORD -> {
                    authRepository.sendPasswordReset(state.email)
                    _uiState.update { it.copy(emailSent = EmailSentReason.RESET_PASSWORD) }
                }
            }
        }
    }

    fun submitNewPassword() {
        if (!_uiState.value.isPasswordValid) {
            _uiState.update { it.copy(showFieldErrors = true) }
            return
        }
        val newPassword = _uiState.value.password
        launchAuthCall {
            authRepository.updatePassword(newPassword)
            _uiState.value = AuthUiState()
        }
    }

    fun cancelPasswordRecovery() {
        authRepository.finishPasswordRecovery()
        _uiState.update { AuthUiState() }
    }

    private fun launchAuthCall(block: suspend () -> Unit) {
        if (_uiState.value.isSubmitting) return
        _uiState.update { it.copy(isSubmitting = true, error = null, showFieldErrors = false) }
        viewModelScope.launch {
            try {
                block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.toAuthError()) }
            } finally {
                _uiState.update { it.copy(isSubmitting = false) }
            }
        }
    }
}
