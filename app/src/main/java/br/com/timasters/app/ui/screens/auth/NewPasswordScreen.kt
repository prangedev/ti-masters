package br.com.timasters.app.ui.screens.auth

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.ui.viewmodel.AuthViewModel

/** Shown after the user opens a password-reset link from the e-mail. */
@Composable
fun NewPasswordScreen(
    modifier: Modifier = Modifier,
    viewModel: AuthViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BackHandler(onBack = viewModel::cancelPasswordRecovery)

    AuthFormContainer(modifier = modifier) {
        AuthHeader(
            title = stringResource(id = R.string.auth_new_password_title),
            description = stringResource(id = R.string.auth_new_password_desc)
        )
        PasswordField(
            value = state.password,
            onValueChange = viewModel::onPasswordChange,
            label = stringResource(id = R.string.auth_new_password_label),
            errorText = if (state.showFieldErrors && !state.isPasswordValid) {
                stringResource(id = R.string.auth_error_password_invalid)
            } else {
                null
            },
            helperText = stringResource(id = R.string.auth_password_hint),
            onDone = viewModel::submitNewPassword
        )
        AuthErrorCard(error = state.error)
        SubmitButton(
            text = stringResource(id = R.string.auth_btn_save_password),
            isSubmitting = state.isSubmitting,
            onClick = viewModel::submitNewPassword
        )
        TextButton(onClick = viewModel::cancelPasswordRecovery, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.cancel))
        }
    }
}
