package br.com.timasters.app.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.ConnectionInfoViewModel

@Composable
fun ConnectionTab(
    modifier: Modifier = Modifier,
    viewModel: ConnectionInfoViewModel = hiltViewModel()
) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val publicInfo by viewModel.publicInfo.collectAsStateWithLifecycle()
    val yes = stringResource(id = R.string.yes)
    val no = stringResource(id = R.string.no)
    val unknown = "—"

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        SectionCard(title = stringResource(id = R.string.connection_section_network)) {
            InfoRow(stringResource(id = R.string.connection_type), stringResource(id = connection.transport.labelResId))
            InfoRow(stringResource(id = R.string.connection_internet), if (connection.hasInternet) yes else no)
            InfoRow(stringResource(id = R.string.connection_metered), if (connection.isMetered) yes else no)
            InfoRow(stringResource(id = R.string.connection_interface), connection.interfaceName ?: unknown)
            connection.downstreamKbps?.let {
                InfoRow(
                    stringResource(id = R.string.connection_estimated_speed),
                    stringResource(
                        id = R.string.connection_estimated_speed_value,
                        BrFormatters.formatNumber(it / 1000.0, 0),
                        BrFormatters.formatNumber((connection.upstreamKbps ?: 0) / 1000.0, 0)
                    )
                )
            }
        }

        SectionCard(title = stringResource(id = R.string.connection_section_addresses)) {
            InfoRow(stringResource(id = R.string.connection_local_ip), connection.ipv4.joinToString("\n").ifEmpty { unknown })
            connection.ipv4PrefixLength?.let { InfoRow(stringResource(id = R.string.connection_prefix), "/$it") }
            InfoRow(stringResource(id = R.string.connection_gateway), connection.gateway ?: unknown)
            InfoRow(stringResource(id = R.string.connection_dns), connection.dnsServers.joinToString("\n").ifEmpty { unknown })
            connection.privateDns?.let { InfoRow(stringResource(id = R.string.connection_private_dns), it) }
            if (connection.ipv6.isNotEmpty()) {
                InfoRow(stringResource(id = R.string.connection_ipv6), connection.ipv6.joinToString("\n"))
            }
        }

        SectionCard(title = stringResource(id = R.string.connection_section_public)) {
            val info = publicInfo.info
            when {
                publicInfo.isLoading -> Text(stringResource(id = R.string.loading))
                publicInfo.failed -> Text(stringResource(id = R.string.tool_error_network))
                info != null -> {
                    InfoRow(stringResource(id = R.string.connection_public_ip), info.clientIp ?: unknown)
                    InfoRow(stringResource(id = R.string.connection_isp), info.asOrganization ?: unknown)
                    InfoRow(
                        stringResource(id = R.string.connection_location),
                        listOfNotNull(info.city, info.country).joinToString(", ").ifEmpty { unknown }
                    )
                }
            }
            OutlinedButton(onClick = viewModel::refreshPublicInfo, enabled = !publicInfo.isLoading) {
                Text(stringResource(id = R.string.refresh))
            }
        }
    }
}
