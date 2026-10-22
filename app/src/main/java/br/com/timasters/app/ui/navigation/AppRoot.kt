package br.com.timasters.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.data.auth.AuthState
import br.com.timasters.app.data.legal.LegalDocument
import br.com.timasters.app.ui.screens.auth.AuthScreen
import br.com.timasters.app.ui.screens.auth.NewPasswordScreen
import br.com.timasters.app.ui.screens.auth.TermsUpdateScreen
import br.com.timasters.app.ui.components.UpdateDialog
import br.com.timasters.app.ui.screens.legal.LegalDocumentScreen
import br.com.timasters.app.ui.viewmodel.MainViewModel
import br.com.timasters.app.ui.viewmodel.SessionViewModel
import br.com.timasters.app.ui.viewmodel.UpdateViewModel

/** Top-level switch between sign-in, password reset, terms update and the main app. */
@Composable
fun AppRoot(
    sessionViewModel: SessionViewModel = hiltViewModel(),
    mainViewModel: MainViewModel = hiltViewModel(),
    updateViewModel: UpdateViewModel = hiltViewModel()
) {
    val authState by sessionViewModel.authState.collectAsStateWithLifecycle()
    val termsState by sessionViewModel.termsState.collectAsStateWithLifecycle()
    val entitlement by mainViewModel.entitlement.collectAsStateWithLifecycle()
    val planRefreshState by mainViewModel.refreshState.collectAsStateWithLifecycle()
    val tourCompleted by mainViewModel.tourCompleted.collectAsStateWithLifecycle()
    val signedInUserId = (authState as? AuthState.SignedIn)?.userId
    var openLegalDocument by rememberSaveable { mutableStateOf<LegalDocument?>(null) }
    val openLegal: (LegalDocument) -> Unit = { openLegalDocument = it }

    // Confirm the plan with the server whenever the app comes to the foreground signed in.
    LifecycleStartEffect(signedInUserId) {
        if (signedInUserId != null) {
            mainViewModel.refreshEntitlement()
            // New versions are only readable by signed-in users (see app_releases RLS).
            updateViewModel.checkAutomatically()
            updateViewModel.onReturnedToApp()
        }
        onStopOrDispose { }
    }

    // The legal document is drawn on top so the screen below keeps its state (tab, typed fields).
    Box(modifier = Modifier.fillMaxSize()) {
        when (val state = authState) {
            AuthState.Loading -> LoadingScreen()
            AuthState.SignedOut -> AuthScreen(onOpenLegalDocument = openLegal)
            AuthState.PasswordRecovery -> NewPasswordScreen()
            is AuthState.SignedIn -> if (termsState.acceptanceRequired) {
                TermsUpdateScreen(
                    state = termsState,
                    onOpenLegalDocument = openLegal,
                    onAccept = sessionViewModel::acceptTerms,
                    onSignOut = sessionViewModel::signOut
                )
            } else {
                // The cached plan loads in milliseconds; render only after it is known.
                entitlement?.let { current ->
                    MainAppShell(
                        entitlement = current,
                        planRefreshState = planRefreshState,
                        canSimulatePlan = mainViewModel.canSimulatePlan,
                        tourCompleted = tourCompleted ?: true,
                        onTourFinished = mainViewModel::completeTour,
                        onSimulatePlan = mainViewModel::simulatePlan,
                        onRefreshPlan = mainViewModel::refreshEntitlement,
                        onOpenLegalDocument = openLegal
                    )
                }
            }
        }

        if (signedInUserId != null) UpdateDialog(viewModel = updateViewModel)

        openLegalDocument?.let { document ->
            BackHandler { openLegalDocument = null }
            Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
                LegalDocumentScreen(document = document, onBack = { openLegalDocument = null })
            }
        }
    }
}

@Composable
private fun LoadingScreen() {
    val description = stringResource(id = R.string.loading)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxSize()
            .semantics { contentDescription = description }
    ) {
        CircularProgressIndicator()
    }
}
