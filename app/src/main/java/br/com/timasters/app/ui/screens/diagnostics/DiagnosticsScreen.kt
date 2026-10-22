package br.com.timasters.app.ui.screens.diagnostics

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.PrimaryScrollableTabRow
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import br.com.timasters.app.R
import br.com.timasters.app.data.plan.Feature
import br.com.timasters.app.data.plan.SubscriptionPlan
import br.com.timasters.app.ui.screens.tools.ToolsTab
import androidx.compose.ui.unit.dp
import br.com.timasters.app.ui.components.AppScreenScaffold

private enum class DiagnosticsTab(@param:StringRes val titleResId: Int) {
    SPEED(R.string.diagnostics_tab_speed),
    PING(R.string.diagnostics_tab_ping),
    CONNECTION(R.string.diagnostics_tab_connection),
    TOOLS(R.string.diagnostics_tab_tools)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DiagnosticsScreen(
    plan: SubscriptionPlan,
    onNavigateToPaywall: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableStateOf(DiagnosticsTab.SPEED) }

    AppScreenScaffold(title = stringResource(id = R.string.nav_diagnostics), modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 0.dp) {
                DiagnosticsTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(stringResource(id = tab.titleResId)) }
                    )
                }
            }
            when (selectedTab) {
                DiagnosticsTab.SPEED -> SpeedTestTab(plan = plan, onNavigateToPaywall = onNavigateToPaywall)
                DiagnosticsTab.PING -> PingTab()
                DiagnosticsTab.CONNECTION -> ConnectionTab()
                DiagnosticsTab.TOOLS -> ToolsTab(canUseNetworkTools = plan.has(Feature.NETWORK_TOOLS), onNavigateToPaywall = onNavigateToPaywall)
            }
        }
    }
}
