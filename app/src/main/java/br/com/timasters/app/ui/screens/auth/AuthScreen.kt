package br.com.timasters.app.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.ImeAction
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.data.legal.LegalDocument
import br.com.timasters.app.ui.viewmodel.AuthMode
import br.com.timasters.app.ui.viewmodel.AuthUiState
import br.com.timasters.app.ui.viewmodel.AuthViewModel
import br.com.timasters.app.ui.viewmodel.EmailSentReason

/** Sign in, create account and forgot password, shown while no one is signed in. */
@Composable
fun AuthScreen(
    onOpenLegalDocument: (LegalDocument) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(enabled = state.mode != AuthMode.LOGIN || state.emailSent != null) {
        if (state.emailSent != null) viewModel.dismissEmailSent() else viewModel.setMode(AuthMode.LOGIN)
    }

    AuthFormContainer(modifier = modifier) {
        val emailSent = state.emailSent
        if (emailSent != null) {
            EmailSentContent(reason = emailSent, email = state.email, onBackToLogin = viewModel::dismissEmailSent)
            return@AuthFormContainer
        }

        when (state.mode) {
            AuthMode.LOGIN -> AuthHeader(title = stringResource(id = R.string.auth_login_title))
            AuthMode.SIGN_UP -> AuthHeader(title = stringResource(id = R.string.auth_signup_title))
            AuthMode.FORGOT_PASSWORD -> AuthHeader(
                title = stringResource(id = R.string.auth_forgot_title),
                description = stringResource(id = R.string.auth_forgot_desc)
            )
        }

        if (state.mode == AuthMode.SIGN_UP) {
            NameField(
                value = state.name,
                onValueChange = viewModel::onNameChange,
                errorText = fieldError(state.showFieldErrors && !state.isNameValid, R.string.auth_error_name_required)
            )
        }

        EmailField(
            value = state.email,
            onValueChange = viewModel::onEmailChange,
            errorText = fieldError(state.showFieldErrors && !state.isEmailValid, R.string.auth_error_email_invalid),
            imeAction = if (state.mode == AuthMode.FORGOT_PASSWORD) ImeAction.Done else ImeAction.Next,
            onDone = viewModel::submit
        )

        if (state.mode != AuthMode.FORGOT_PASSWORD) {
            PasswordField(
                value = state.password,
                onValueChange = viewModel::onPasswordChange,
                label = stringResource(id = R.string.auth_password_label),
                errorText = passwordError(state),
                helperText = if (state.mode == AuthMode.SIGN_UP) stringResource(id = R.string.auth_password_hint) else null,
                onDone = viewModel::submit
            )
        }

        if (state.mode == AuthMode.LOGIN) {
            TextButton(
                onClick = { viewModel.setMode(AuthMode.FORGOT_PASSWORD) },
                modifier = Modifier.align(Alignment.End)
            ) {
                Text(stringResource(id = R.string.auth_btn_forgot))
            }
        }

        if (state.mode == AuthMode.SIGN_UP) {
            TermsConsent(
                accepted = state.acceptedTerms,
                showError = state.showFieldErrors && !state.acceptedTerms,
                onAcceptedChange = viewModel::onAcceptedTermsChange,
                onOpenLegalDocument = onOpenLegalDocument
            )
        }

        AuthErrorCard(error = state.error)

        SubmitButton(
            text = stringResource(
                id = when (state.mode) {
                    AuthMode.LOGIN -> R.string.auth_btn_login
                    AuthMode.SIGN_UP -> R.string.auth_btn_signup
                    AuthMode.FORGOT_PASSWORD -> R.string.auth_btn_send_link
                }
            ),
            isSubmitting = state.isSubmitting,
            onClick = viewModel::submit
        )

        TextButton(
            onClick = {
                viewModel.setMode(if (state.mode == AuthMode.LOGIN) AuthMode.SIGN_UP else AuthMode.LOGIN)
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                stringResource(
                    id = when (state.mode) {
                        AuthMode.LOGIN -> R.string.auth_btn_to_signup
                        AuthMode.SIGN_UP -> R.string.auth_btn_to_login
                        AuthMode.FORGOT_PASSWORD -> R.string.auth_btn_back_to_login
                    }
                )
            )
        }
    }
}

@Composable
private fun fieldError(show: Boolean, messageResId: Int): String? =
    if (show) stringResource(id = messageResId) else null

@Composable
private fun passwordError(state: AuthUiState): String? = when {
    !state.showFieldErrors -> null
    state.mode == AuthMode.LOGIN && state.password.isEmpty() -> stringResource(id = R.string.auth_error_password_required)
    state.mode == AuthMode.SIGN_UP && !state.isPasswordValid -> stringResource(id = R.string.auth_error_password_invalid)
    else -> null
}

@Composable
private fun TermsConsent(
    accepted: Boolean,
    showError: Boolean,
    onAcceptedChange: (Boolean) -> Unit,
    onOpenLegalDocument: (LegalDocument) -> Unit
) {
    Column {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(value = accepted, role = Role.Checkbox, onValueChange = onAcceptedChange)
        ) {
            Checkbox(checked = accepted, onCheckedChange = null)
            Text(
                text = stringResource(id = R.string.auth_terms_checkbox),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f)
            )
        }
        LegalLinks(onOpenLegalDocument = onOpenLegalDocument)
        if (showError) {
            Text(
                text = stringResource(id = R.string.auth_error_terms_required),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun LegalLinks(onOpenLegalDocument: (LegalDocument) -> Unit, modifier: Modifier = Modifier) {
    FlowRow(modifier = modifier) {
        LegalDocument.entries.forEach { document ->
            TextButton(onClick = { onOpenLegalDocument(document) }) {
                Text(stringResource(id = document.titleResId))
            }
        }
    }
}

@Composable
private fun EmailSentContent(reason: EmailSentReason, email: String, onBackToLogin: () -> Unit) {
    AuthHeader(
        title = stringResource(id = R.string.auth_email_sent_title),
        description = stringResource(
            id = when (reason) {
                EmailSentReason.CONFIRM_ACCOUNT -> R.string.auth_email_sent_confirm
                EmailSentReason.RESET_PASSWORD -> R.string.auth_email_sent_reset
            },
            email
        )
    )
    Text(
        text = stringResource(id = R.string.auth_email_sent_tip),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    SubmitButton(
        text = stringResource(id = R.string.auth_btn_back_to_login),
        isSubmitting = false,
        onClick = onBackToLogin
    )
}
