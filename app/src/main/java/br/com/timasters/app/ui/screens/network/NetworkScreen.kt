package br.com.timasters.app.ui.screens.network

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
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import br.com.timasters.app.ui.components.LocalNetworkGate
import br.com.timasters.app.R
import br.com.timasters.app.data.plan.Feature
import br.com.timasters.app.data.plan.SubscriptionPlan
import br.com.timasters.app.ui.components.AppScreenScaffold
import br.com.timasters.app.ui.viewmodel.PortScanViewModel

private enum class NetworkTab(@param:StringRes val titleResId: Int) {
    WIFI(R.string.network_tab_wifi),
    SPECTRUM(R.string.network_tab_spectrum),
    DEVICES(R.string.network_tab_devices),
    PORTS(R.string.network_tab_ports)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    plan: SubscriptionPlan,
    onNavigateToPaywall: () -> Unit,
    modifier: Modifier = Modifier
) {
    var selectedTab by rememberSaveable { mutableStateOf(NetworkTab.WIFI) }
    // Shared so a device found in the scan can be sent straight to the port scanner.
    val portScanViewModel: PortScanViewModel = hiltViewModel()
    val canScanPorts = plan.has(Feature.PORT_SCANNER)

    AppScreenScaffold(title = stringResource(id = R.string.nav_network), modifier = modifier) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            PrimaryScrollableTabRow(selectedTabIndex = selectedTab.ordinal, edgePadding = 0.dp) {
                NetworkTab.entries.forEach { tab ->
                    Tab(
                        selected = selectedTab == tab,
                        onClick = { selectedTab = tab },
                        text = { Text(stringResource(id = tab.titleResId)) }
                    )
                }
            }
            when (selectedTab) {
                NetworkTab.WIFI -> WifiTab()
                NetworkTab.SPECTRUM -> SpectrumTab(plan = plan, onNavigateToPaywall = onNavigateToPaywall)
                NetworkTab.DEVICES -> LocalNetworkGate {
                    DevicesTab(
                        onScanPorts = { ip ->
                            if (canScanPorts) {
                                portScanViewModel.onHostChange(ip)
                                selectedTab = NetworkTab.PORTS
                            } else {
                                onNavigateToPaywall()
                            }
                        }
                    )
                }
                NetworkTab.PORTS -> LocalNetworkGate {
                    PortsTab(
                        canScanPorts = canScanPorts,
                        onNavigateToPaywall = onNavigateToPaywall,
                        viewModel = portScanViewModel
                    )
                }
            }
        }
    }
}
