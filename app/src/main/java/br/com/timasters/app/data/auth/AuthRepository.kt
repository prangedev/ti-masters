package br.com.timasters.app.data.auth

import android.content.Intent
import br.com.timasters.app.core.validation.AuthInputValidator
import br.com.timasters.app.data.legal.CURRENT_TERMS_VERSION
import br.com.timasters.app.di.AUTH_DEEPLINK_HOST
import br.com.timasters.app.di.AUTH_DEEPLINK_SCHEME
import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.auth.auth
import io.github.jan.supabase.auth.handleDeeplinks
import io.github.jan.supabase.auth.providers.builtin.Email
import io.github.jan.supabase.auth.status.SessionStatus
import io.github.jan.supabase.postgrest.from
import io.github.jan.supabase.postgrest.postgrest
import io.github.jan.supabase.postgrest.query.Columns
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import javax.inject.Inject
import javax.inject.Singleton

sealed interface AuthState {
    data object Loading : AuthState
    data object SignedOut : AuthState
    data class SignedIn(val userId: String?, val email: String?) : AuthState

    /** The user opened a password-reset link and must choose a new password. */
    data object PasswordRecovery : AuthState
}

enum class SignUpOutcome { SIGNED_IN, CONFIRM_EMAIL }

@Serializable
private data class ProfileTerms(
    @SerialName("accepted_terms_version") val acceptedTermsVersion: String? = null
)

@Singleton
class AuthRepository @Inject constructor(
    private val supabase: SupabaseClient
) {
    private val auth get() = supabase.auth

    private val recoveryPending = MutableStateFlow(false)
    private val _pendingLinkError = MutableStateFlow<AuthError?>(null)

    /** Set when an e-mail link could not be used (expired, already used, opened on another phone). */
    val pendingLinkError: StateFlow<AuthError?> = _pendingLinkError.asStateFlow()

    val authState: Flow<AuthState> = combine(auth.sessionStatus, recoveryPending) { status, recovery ->
        when (status) {
            SessionStatus.Initializing -> AuthState.Loading
            is SessionStatus.NotAuthenticated -> AuthState.SignedOut
            is SessionStatus.Authenticated -> if (recovery) {
                AuthState.PasswordRecovery
            } else {
                val user = status.session.user ?: auth.currentUserOrNull()
                AuthState.SignedIn(userId = user?.id, email = user?.email)
            }
            // Token refresh failed, usually because the phone is offline: keep the user signed in.
            is SessionStatus.RefreshFailure -> {
                val user = auth.currentUserOrNull()
                if (auth.currentSessionOrNull() != null) {
                    AuthState.SignedIn(userId = user?.id, email = user?.email)
                } else {
                    AuthState.SignedOut
                }
            }
        }
    }

    fun currentUserId(): String? = auth.currentUserOrNull()?.id

    fun consumeLinkError() {
        _pendingLinkError.value = null
    }

    /** Handles timasters://auth/... links from confirmation and password-reset e-mails. */
    fun handleDeeplink(intent: Intent?) {
        val uri = intent?.data ?: return
        if (uri.scheme != AUTH_DEEPLINK_SCHEME || uri.host != AUTH_DEEPLINK_HOST) return

        val hasError = uri.getQueryParameter("error") != null ||
            uri.fragment?.contains("error=") == true
        if (hasError) {
            _pendingLinkError.value = AuthError.LINK_EXPIRED
            return
        }
        if (uri.path == RESET_PATH) recoveryPending.value = true

        supabase.handleDeeplinks(
            intent = intent,
            onError = {
                recoveryPending.value = false
                _pendingLinkError.value = AuthError.LINK_EXPIRED
            }
        )
    }

    suspend fun signIn(email: String, password: String) {
        auth.signInWith(Email) {
            this.email = AuthInputValidator.normalizeEmail(email)
            this.password = password
        }
    }

    /** Creates the account and records consent to [CURRENT_TERMS_VERSION] (stored by a database trigger). */
    suspend fun signUp(name: String, email: String, password: String): SignUpOutcome {
        auth.signUpWith(Email, redirectUrl = CONFIRM_REDIRECT_URL) {
            this.email = AuthInputValidator.normalizeEmail(email)
            this.password = password
            data = buildJsonObject {
                put("full_name", name.trim())
                put("accepted_terms_version", CURRENT_TERMS_VERSION)
            }
        }
        // With e-mail confirmation on, Supabase returns no session until the link is opened.
        return if (auth.currentSessionOrNull() != null) SignUpOutcome.SIGNED_IN else SignUpOutcome.CONFIRM_EMAIL
    }

    suspend fun sendPasswordReset(email: String) {
        auth.resetPasswordForEmail(AuthInputValidator.normalizeEmail(email), redirectUrl = RESET_REDIRECT_URL)
    }

    suspend fun updatePassword(newPassword: String) {
        auth.updateUser { password = newPassword }
        recoveryPending.value = false
    }

    fun finishPasswordRecovery() {
        recoveryPending.value = false
    }

    /**
     * Returns whether the signed-in user accepted the current terms, or null when it can't be
     * checked (offline). Callers should not block the app on null.
     */
    suspend fun hasAcceptedCurrentTerms(): Boolean? {
        val userId = auth.currentUserOrNull()?.id ?: return null
        return try {
            val profile = supabase.from("profiles")
                .select(Columns.list("accepted_terms_version")) {
                    filter { eq("id", userId) }
                }
                .decodeSingleOrNull<ProfileTerms>()
            profile?.acceptedTermsVersion == CURRENT_TERMS_VERSION
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    /** Records consent with the server clock (see the accept_terms SQL function). */
    suspend fun acceptCurrentTerms() {
        supabase.postgrest.rpc(
            function = "accept_terms",
            parameters = buildJsonObject { put("terms_version", CURRENT_TERMS_VERSION) }
        )
    }

    suspend fun signOut() {
        try {
            auth.signOut()
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            // Offline or server error: still forget the session on this phone.
            auth.clearSession()
        }
        recoveryPending.value = false
    }

    /** Permanently deletes the account and all its data on the server (LGPD / Google Play rule). */
    suspend fun deleteAccount() {
        supabase.postgrest.rpc("delete_my_account")
        signOut()
    }

    private companion object {
        const val RESET_PATH = "/reset"
        const val CONFIRM_REDIRECT_URL = "$AUTH_DEEPLINK_SCHEME://$AUTH_DEEPLINK_HOST/confirm"
        const val RESET_REDIRECT_URL = "$AUTH_DEEPLINK_SCHEME://$AUTH_DEEPLINK_HOST$RESET_PATH"
    }
}
