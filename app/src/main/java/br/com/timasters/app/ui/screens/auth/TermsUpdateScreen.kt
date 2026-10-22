package br.com.timasters.app.ui.screens.auth

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import br.com.timasters.app.R
import br.com.timasters.app.data.legal.LegalDocument
import br.com.timasters.app.ui.viewmodel.TermsUiState

/** Blocks the app until a signed-in user accepts a new version of the terms (LGPD consent). */
@Composable
fun TermsUpdateScreen(
    state: TermsUiState,
    onOpenLegalDocument: (LegalDocument) -> Unit,
    onAccept: () -> Unit,
    onSignOut: () -> Unit,
    modifier: Modifier = Modifier
) {
    AuthFormContainer(modifier = modifier) {
        AuthHeader(
            title = stringResource(id = R.string.terms_update_title),
            description = stringResource(id = R.string.terms_update_desc)
        )
        LegalLinks(onOpenLegalDocument = onOpenLegalDocument)
        AuthErrorCard(error = state.error)
        SubmitButton(
            text = stringResource(id = R.string.terms_update_accept),
            isSubmitting = state.isSubmitting,
            onClick = onAccept
        )
        TextButton(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.terms_update_sign_out))
        }
    }
}
