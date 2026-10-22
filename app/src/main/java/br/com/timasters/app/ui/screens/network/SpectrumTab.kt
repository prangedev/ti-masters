package br.com.timasters.app.ui.screens.network

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.network.ChannelAdvisor
import br.com.timasters.app.core.network.SpectrumEntry
import br.com.timasters.app.core.network.WifiBand
import br.com.timasters.app.core.network.WifiMath
import br.com.timasters.app.data.plan.Feature
import br.com.timasters.app.data.plan.SubscriptionPlan
import br.com.timasters.app.data.wifi.WifiNetwork
import br.com.timasters.app.ui.components.LockedFeatureCard
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.WifiViewModel

private val BANDS = listOf(WifiBand.GHZ_2_4, WifiBand.GHZ_5, WifiBand.GHZ_6)

@Composable
fun SpectrumTab(
    plan: SubscriptionPlan,
    onNavigateToPaywall: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: WifiViewModel = hiltViewModel()
) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    val networks by viewModel.nearbyNetworks.collectAsStateWithLifecycle()
    var band by rememberSaveable { mutableStateOf(WifiBand.GHZ_2_4) }
    // Basic plan sees 2.4 GHz; 5 and 6 GHz analysis is part of Advanced.
    val bandLocked = band != WifiBand.GHZ_2_4 && !plan.has(Feature.SPECTRUM_FULL)
    val bandNetworks = networks.filter { WifiMath.bandOf(it.frequencyMhz) == band }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { WifiAccessCards(access = access, onAccessMayHaveChanged = viewModel::refreshAccess) }
        if (!access.canScan) return@LazyColumn

        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                BANDS.forEachIndexed { index, option ->
                    SegmentedButton(
                        selected = band == option,
                        onClick = { band = option },
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = BANDS.size)
                    ) { Text(stringResource(id = option.labelResId)) }
                }
            }
        }

        if (bandLocked) {
            item {
                LockedFeatureCard(
                    title = stringResource(id = R.string.spectrum_locked_title),
                    description = stringResource(id = R.string.spectrum_locked_desc),
                    onUnlock = onNavigateToPaywall
                )
            }
            return@LazyColumn
        }

        item {
            SectionCard(title = stringResource(id = R.string.spectrum_title, bandNetworks.size)) {
                if (bandNetworks.isEmpty()) {
                    Text(stringResource(id = R.string.spectrum_empty), style = MaterialTheme.typography.bodyMedium)
                } else {
                    SpectrumChart(
                        band = band,
                        networks = bandNetworks,
                        description = stringResource(id = R.string.spectrum_chart_description, bandNetworks.size)
                    )
                }
                Text(
                    text = stringResource(id = R.string.spectrum_scan_note),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        ChannelAdvisor.recommend(
            band,
            bandNetworks.map { SpectrumEntry(it.centerFrequencyMhz, it.channelWidthMhz, it.rssiDbm) }
        )?.takeIf { bandNetworks.isNotEmpty() }?.let { best ->
            item {
                MessageCard(
                    text = stringResource(id = R.string.spectrum_recommendation, best.channel, best.networks),
                    isError = false
                )
            }
        }

        items(bandNetworks, key = { "${it.bssid}_${it.frequencyMhz}" }) { network ->
            NetworkRow(network)
            HorizontalDivider()
        }
    }
}

@Composable
private fun NetworkRow(network: WifiNetwork) {
    val quality = WifiMath.signalQuality(network.rssiDbm)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = network.ssid.ifEmpty { stringResource(id = R.string.wifi_hidden_network) } +
                    if (network.isConnected) " · " + stringResource(id = R.string.wifi_connected_badge) else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = if (network.isConnected) FontWeight.Bold else FontWeight.Normal
            )
            Text(
                text = stringResource(
                    id = R.string.spectrum_network_details,
                    WifiMath.channelOf(network.frequencyMhz) ?: 0,
                    network.channelWidthMhz,
                    network.security,
                    network.standard ?: ""
                ).trimEnd(' ', '·'),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(text = network.bssid, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = "${network.rssiDbm} dBm",
            style = MaterialTheme.typography.titleSmall,
            color = qualityColor(quality)
        )
    }
}
