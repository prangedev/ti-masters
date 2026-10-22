package br.com.timasters.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.data.legal.LegalDocument
import br.com.timasters.app.data.plan.Entitlement
import br.com.timasters.app.data.plan.SubscriptionPlan
import br.com.timasters.app.ui.components.AppScreenScaffold
import br.com.timasters.app.ui.viewmodel.PlanRefreshState

@Composable
fun SettingsScreen(
    entitlement: Entitlement,
    planRefreshState: PlanRefreshState,
    canSimulatePlan: Boolean,
    onNavigateToPaywall: () -> Unit,
    onRefreshPlan: () -> Unit,
    onSimulatePlan: (SubscriptionPlan?) -> Unit,
    onOpenLegalDocument: (LegalDocument) -> Unit,
    onStartTour: () -> Unit,
    modifier: Modifier = Modifier
) {
    val currentPlan = entitlement.plan
    AppScreenScaffold(title = stringResource(id = R.string.settings_title), modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .padding(16.dp)
        ) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Star,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.padding(end = 8.dp)
                        )
                        Text(
                            text = stringResource(
                                id = R.string.settings_current_plan,
                                stringResource(id = currentPlan.titleResId)
                            ),
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(
                            id = R.string.settings_plan_price,
                            stringResource(id = currentPlan.priceResId)
                        ),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.primary
                    )

                    Spacer(modifier = Modifier.height(8.dp))

                    Text(
                        text = stringResource(id = currentPlan.descriptionResId),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    Spacer(modifier = Modifier.height(12.dp))

                    PlanVerificationStatus(entitlement = entitlement, refreshState = planRefreshState)

                    Spacer(modifier = Modifier.height(16.dp))

                    Button(
                        onClick = onNavigateToPaywall,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(text = stringResource(id = R.string.settings_manage_subscription))
                    }
                    OutlinedButton(
                        onClick = onRefreshPlan,
                        enabled = planRefreshState != PlanRefreshState.REFRESHING,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                    ) {
                        if (planRefreshState == PlanRefreshState.REFRESHING) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                        } else {
                            Text(text = stringResource(id = R.string.settings_refresh_plan))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            AppVersionSection()

            Spacer(modifier = Modifier.height(16.dp))
            AccountSection(onOpenLegalDocument = onOpenLegalDocument)

            OutlinedButton(
                onClick = onStartTour,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
            ) {
                Text(text = stringResource(id = R.string.settings_restart_tour))
            }

            if (canSimulatePlan) {
                Spacer(modifier = Modifier.height(24.dp))
                DebugPlanSimulator(entitlement = entitlement, onSimulatePlan = onSimulatePlan)
            }
        }
    }
}

@Composable
private fun PlanVerificationStatus(entitlement: Entitlement, refreshState: PlanRefreshState) {
    val verifiedAt = entitlement.verifiedAtMillis
    val text = when {
        refreshState == PlanRefreshState.FAILED -> stringResource(id = R.string.settings_plan_refresh_failed)
        verifiedAt != null -> stringResource(
            id = R.string.settings_plan_verified_at,
            BrFormatters.formatDateTime(verifiedAt)
        )
        else -> stringResource(id = R.string.settings_plan_not_verified)
    }
    Text(
        text = text,
        style = MaterialTheme.typography.bodySmall,
        color = if (refreshState == PlanRefreshState.FAILED) {
            MaterialTheme.colorScheme.error
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        }
    )
}

/** Developer-only: pretend to have another plan on this device; hidden in release builds. */
@Composable
private fun DebugPlanSimulator(
    entitlement: Entitlement,
    onSimulatePlan: (SubscriptionPlan?) -> Unit
) {
    Text(
        text = stringResource(id = R.string.settings_debug_plan_switch),
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(bottom = 8.dp)
    )
    Row(modifier = Modifier.fillMaxWidth()) {
        SubscriptionPlan.entries.forEachIndexed { index, plan ->
            OutlinedButton(
                onClick = { onSimulatePlan(plan) },
                enabled = !(entitlement.isSimulated && entitlement.plan == plan),
                modifier = Modifier
                    .weight(1f)
                    .padding(start = if (index == 0) 0.dp else 4.dp)
            ) {
                Text(text = stringResource(id = plan.titleResId))
            }
        }
    }
    if (entitlement.isSimulated) {
        Text(
            text = stringResource(
                id = R.string.settings_plan_simulated,
                stringResource(id = entitlement.accountPlan.titleResId)
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.padding(top = 8.dp)
        )
        TextButton(onClick = { onSimulatePlan(null) }, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(id = R.string.settings_use_account_plan))
        }
    }
}
