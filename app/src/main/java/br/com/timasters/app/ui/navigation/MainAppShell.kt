package br.com.timasters.app.ui.navigation

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PlainTooltip
import androidx.compose.material3.Text
import androidx.compose.material3.TooltipBox
import androidx.compose.material3.TooltipDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.rememberTooltipState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.timasters.app.R
import br.com.timasters.app.data.legal.LegalDocument
import br.com.timasters.app.data.plan.Entitlement
import br.com.timasters.app.data.plan.Feature
import br.com.timasters.app.data.plan.SubscriptionPlan
import br.com.timasters.app.ui.components.AdMobBanner
import br.com.timasters.app.ui.screens.business.BusinessScreen
import br.com.timasters.app.ui.screens.diagnostics.DiagnosticsScreen
import br.com.timasters.app.ui.screens.routers.RoutersScreen
import br.com.timasters.app.ui.screens.network.NetworkScreen
import br.com.timasters.app.ui.screens.paywall.PaywallScreen
import br.com.timasters.app.ui.screens.settings.SettingsScreen
import br.com.timasters.app.ui.viewmodel.PlanRefreshState

/**
 * App shell: bottom bar on phones, navigation rail on tablets and landscape
 * (NavigationSuiteScaffold picks automatically). The bar shows icons only; a long press shows
 * the name. Back returns to the first tab before leaving the app.
 */
@Composable
fun MainAppShell(
    entitlement: Entitlement,
    planRefreshState: PlanRefreshState,
    canSimulatePlan: Boolean,
    tourCompleted: Boolean,
    onTourFinished: () -> Unit,
    onSimulatePlan: (SubscriptionPlan?) -> Unit,
    onRefreshPlan: () -> Unit,
    onOpenLegalDocument: (LegalDocument) -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPlan = entitlement.plan
    var currentScreen by rememberSaveable { mutableStateOf(Screen.Diagnostics) }
    var showPaywall by rememberSaveable { mutableStateOf(false) }
    /** Index into [TOUR_STEPS] while the tour runs, otherwise null. */
    var tourStep by rememberSaveable { mutableStateOf<Int?>(null) }
    val iconBounds = remember { mutableStateMapOf<Screen, Rect>() }

    fun goToTourStep(index: Int) {
        tourStep = index
        currentScreen = TOUR_STEPS[index].screen
    }

    fun endTour() {
        tourStep = null
        currentScreen = Screen.Diagnostics
        onTourFinished()
    }

    if (showPaywall) {
        BackHandler { showPaywall = false }
        PaywallScreen(
            currentPlan = currentPlan,
            canSelectPlan = canSimulatePlan,
            onSelectPlan = { plan ->
                onSimulatePlan(plan)
                showPaywall = false
            },
            onBack = { showPaywall = false },
            modifier = modifier
        )
        return
    }

    BackHandler(enabled = currentScreen != Screen.Diagnostics && tourStep == null) {
        currentScreen = Screen.Diagnostics
    }

    val openPaywall = { showPaywall = true }

    Box(modifier = modifier.fillMaxSize()) {
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                Screen.entries.forEach { screen ->
                    val isLocked = when (screen) {
                        Screen.Routers -> !currentPlan.has(Feature.ROUTERS)
                        Screen.Business -> !currentPlan.has(Feature.BUSINESS_SUITE)
                        else -> false
                    }
                    item(
                        selected = currentScreen == screen,
                        onClick = { currentScreen = screen },
                        icon = {
                            NavigationIcon(
                                screen = screen,
                                isLocked = isLocked,
                                modifier = Modifier.onGloballyPositioned { iconBounds[screen] = it.boundsInRoot() }
                            )
                        }
                    )
                }
            }
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Box(modifier = Modifier.weight(1f)) {
                    when (currentScreen) {
                        Screen.Diagnostics -> DiagnosticsScreen(plan = currentPlan, onNavigateToPaywall = openPaywall)
                        Screen.Network -> NetworkScreen(plan = currentPlan, onNavigateToPaywall = openPaywall)
                        Screen.Routers -> RoutersScreen(
                            canAccessRouters = currentPlan.has(Feature.ROUTERS),
                            canAccessFiber = currentPlan.has(Feature.ROUTERS_FIBER),
                            onNavigateToPaywall = openPaywall
                        )
                        Screen.Business -> BusinessScreen(
                            canAccessCrm = currentPlan.has(Feature.BUSINESS_SUITE),
                            onNavigateToPaywall = openPaywall
                        )
                        Screen.Settings -> SettingsScreen(
                            entitlement = entitlement,
                            planRefreshState = planRefreshState,
                            canSimulatePlan = canSimulatePlan,
                            onRefreshPlan = onRefreshPlan,
                            onNavigateToPaywall = openPaywall,
                            onSimulatePlan = onSimulatePlan,
                            onOpenLegalDocument = onOpenLegalDocument,
                            onStartTour = { goToTourStep(0) }
                        )
                    }
                }
                if (currentPlan.showsAds) {
                    AdMobBanner()
                }
            }
        }

        val step = tourStep
        if (step != null) {
            BackHandler { if (step > 0) goToTourStep(step - 1) else endTour() }
            TourOverlay(
                stepIndex = step,
                target = iconBounds[TOUR_STEPS[step].screen],
                onNext = { if (step < TOUR_STEPS.lastIndex) goToTourStep(step + 1) else endTour() },
                onPrevious = { goToTourStep(step - 1) },
                onSkip = ::endTour
            )
        } else if (!tourCompleted) {
            TourIntroDialog(onStart = { goToTourStep(0) }, onSkip = onTourFinished)
        }
    }
}

/** Icon with its name in a tooltip (long press) and as the TalkBack description. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NavigationIcon(screen: Screen, isLocked: Boolean, modifier: Modifier = Modifier) {
    val label = stringResource(id = screen.titleResId)
    TooltipBox(
        positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } },
        state = rememberTooltipState(),
        modifier = modifier
    ) {
        if (isLocked) {
            BadgedBox(
                badge = {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = stringResource(id = R.string.cd_locked),
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(1.dp)
                    )
                }
            ) {
                Icon(imageVector = screen.icon, contentDescription = label)
            }
        } else {
            Icon(imageVector = screen.icon, contentDescription = label)
        }
    }
}
