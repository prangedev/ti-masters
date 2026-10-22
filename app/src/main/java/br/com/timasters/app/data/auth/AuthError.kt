package br.com.timasters.app.data.auth

import androidx.annotation.StringRes
import br.com.timasters.app.R
import io.github.jan.supabase.auth.exception.AuthErrorCode
import io.github.jan.supabase.auth.exception.AuthRestException
import io.github.jan.supabase.auth.exception.AuthWeakPasswordException
import java.io.IOException

/** User-facing auth failures, each with a pt-BR message. */
enum class AuthError(@param:StringRes val messageResId: Int) {
    INVALID_CREDENTIALS(R.string.auth_error_invalid_credentials),
    EMAIL_NOT_CONFIRMED(R.string.auth_error_email_not_confirmed),
    EMAIL_ALREADY_REGISTERED(R.string.auth_error_email_registered),
    EMAIL_NOT_ACCEPTED(R.string.auth_error_email_not_accepted),
    WEAK_PASSWORD(R.string.auth_error_weak_password),
    SAME_PASSWORD(R.string.auth_error_same_password),
    RATE_LIMITED(R.string.auth_error_rate_limited),
    LINK_EXPIRED(R.string.auth_error_link_expired),
    NETWORK(R.string.auth_error_network),
    UNKNOWN(R.string.auth_error_unknown)
}

fun Throwable.toAuthError(): AuthError = when (this) {
    is AuthWeakPasswordException -> AuthError.WEAK_PASSWORD
    is AuthRestException -> when (errorCode) {
        AuthErrorCode.InvalidCredentials -> AuthError.INVALID_CREDENTIALS
        AuthErrorCode.EmailNotConfirmed -> AuthError.EMAIL_NOT_CONFIRMED
        AuthErrorCode.UserAlreadyExists, AuthErrorCode.EmailExists -> AuthError.EMAIL_ALREADY_REGISTERED
        AuthErrorCode.EmailAddressInvalid, AuthErrorCode.EmailAddressNotAuthorized -> AuthError.EMAIL_NOT_ACCEPTED
        AuthErrorCode.WeakPassword -> AuthError.WEAK_PASSWORD
        AuthErrorCode.SamePassword -> AuthError.SAME_PASSWORD
        AuthErrorCode.OverEmailSendRateLimit, AuthErrorCode.OverRequestRateLimit -> AuthError.RATE_LIMITED
        AuthErrorCode.FlowStateExpired, AuthErrorCode.FlowStateNotFound,
        AuthErrorCode.OtpExpired, AuthErrorCode.BadCodeVerifier -> AuthError.LINK_EXPIRED
        else -> AuthError.UNKNOWN
    }
    // Includes Supabase's HttpRequestException and Ktor timeouts.
    is IOException -> AuthError.NETWORK
    else -> AuthError.UNKNOWN
}
