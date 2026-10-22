package br.com.timasters.app.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.data.network.PingMethod
import br.com.timasters.app.data.network.PingTool
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.MetricTile
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.PingViewModel

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PingTab(
    modifier: Modifier = Modifier,
    viewModel: PingViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val stats = state.stats
    val quickTargets = listOfNotNull(
        viewModel.gateway?.let { stringResource(id = R.string.ping_target_router) to it },
        "Google DNS" to "8.8.8.8",
        "Cloudflare" to "1.1.1.1",
        "google.com.br" to "google.com.br"
    )

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            OutlinedTextField(
                value = state.host,
                onValueChange = viewModel::onHostChange,
                label = { Text(stringResource(id = R.string.ping_host_label)) },
                singleLine = true,
                enabled = !state.isRunning,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Go),
                keyboardActions = KeyboardActions(onGo = { viewModel.start() }),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                quickTargets.forEach { (label, host) ->
                    AssistChip(
                        onClick = { viewModel.onHostChange(host) },
                        enabled = !state.isRunning,
                        label = { Text(label) }
                    )
                }
            }
        }
        item {
            if (state.isRunning) {
                OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(id = R.string.stop))
                }
            } else {
                Button(onClick = viewModel::start, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(id = R.string.ping_start, PingTool.DEFAULT_COUNT))
                }
            }
        }
        state.error?.let { error ->
            item { MessageCard(text = stringResource(id = error.messageResId), isError = true) }
        }
        if (state.method == PingMethod.TCP) {
            item { MessageCard(text = stringResource(id = R.string.ping_tcp_fallback, PingTool.TCP_PORT), isError = false) }
        }
        if (stats != null) {
            item {
                SectionCard(title = state.resolvedAddress?.let { stringResource(id = R.string.ping_results_for, it) }
                    ?: stringResource(id = R.string.ping_results)) {
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        MetricTile(
                            label = stringResource(id = R.string.metric_received),
                            value = "${stats.received}/${stats.sent}",
                            unit = stringResource(id = R.string.unit_packets),
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = stringResource(id = R.string.metric_loss),
                            value = BrFormatters.formatNumber(stats.lossPercent, 0),
                            unit = "%",
                            valueColor = if (stats.lossPercent > 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        MetricTile(
                            label = stringResource(id = R.string.metric_jitter),
                            value = stats.jitterMs.formatOrDash(1),
                            unit = "ms",
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                        MetricTile(stringResource(id = R.string.metric_min), stats.minMs.formatOrDash(1), "ms", Modifier.weight(1f))
                        MetricTile(stringResource(id = R.string.metric_avg), stats.avgMs.formatOrDash(1), "ms", Modifier.weight(1f))
                        MetricTile(stringResource(id = R.string.metric_max), stats.maxMs.formatOrDash(1), "ms", Modifier.weight(1f))
                    }
                }
            }
        }
        itemsIndexed(state.replies) { index, timeMs ->
            Text(
                text = if (timeMs != null) {
                    stringResource(id = R.string.ping_reply, index + 1, BrFormatters.formatNumber(timeMs, 1))
                } else {
                    stringResource(id = R.string.ping_lost, index + 1)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = if (timeMs == null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
            )
        }
    }
}

private fun Double?.formatOrDash(decimals: Int): String =
    this?.let { BrFormatters.formatNumber(it, decimals) } ?: "—"
