package br.com.timasters.app.ui.screens.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.data.network.TransportType
import br.com.timasters.app.data.plan.Feature
import br.com.timasters.app.data.plan.SubscriptionPlan
import br.com.timasters.app.data.speedtest.SpeedTestPhase
import br.com.timasters.app.data.speedtest.SpeedTestProgress
import br.com.timasters.app.data.speedtest.SpeedTestResult
import br.com.timasters.app.ui.components.ChartSeries
import br.com.timasters.app.ui.components.LineChart
import br.com.timasters.app.ui.components.LockedFeatureCard
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.MetricTile
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.SpeedTestViewModel

/** Basic plan keeps the latest results visible; the full history and chart are Advanced. */
private const val BASIC_HISTORY_LIMIT = 10
private const val CHART_POINTS = 20

@Composable
fun SpeedTestTab(
    plan: SubscriptionPlan,
    onNavigateToPaywall: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: SpeedTestViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val hasFullHistory = plan.has(Feature.TEST_HISTORY_FULL)
    val visibleHistory = if (hasFullHistory) history else history.take(BASIC_HISTORY_LIMIT)
    var confirmClear by rememberSaveable { mutableStateOf(false) }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            SpeedGauge(progress = state.progress, isRunning = state.isRunning)
        }
        item {
            ResultTiles(progress = state.progress ?: history.firstOrNull()?.toProgress())
        }
        state.progress?.server?.let { server ->
            item {
                Text(
                    text = stringResource(
                        id = R.string.speed_server_info,
                        listOfNotNull(server.city, server.colo).joinToString(" · ").ifEmpty { "—" },
                        server.asOrganization ?: "—"
                    ),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.Center
                )
            }
        }
        item {
            Column(
                modifier = Modifier.widthIn(max = 480.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (state.isRunning) {
                    OutlinedButton(onClick = viewModel::cancel, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(id = R.string.cancel))
                    }
                } else {
                    Button(
                        onClick = viewModel::start,
                        enabled = connection.transport != TransportType.NONE,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text(
                            stringResource(
                                id = if (state.progress == null) R.string.speed_start else R.string.speed_restart
                            )
                        )
                    }
                }
                when {
                    connection.transport == TransportType.NONE ->
                        MessageCard(text = stringResource(id = R.string.tool_error_no_connection), isError = true)
                    connection.transport == TransportType.CELLULAR && !state.isRunning ->
                        MessageCard(text = stringResource(id = R.string.speed_mobile_data_warning), isError = false)
                }
                state.error?.let { MessageCard(text = stringResource(id = it.messageResId), isError = true) }
            }
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = stringResource(id = R.string.speed_history_title),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.weight(1f)
                )
                if (history.isNotEmpty()) {
                    TextButton(onClick = { confirmClear = true }) {
                        Text(stringResource(id = R.string.speed_history_clear))
                    }
                }
            }
        }
        if (history.isEmpty()) {
            item {
                Text(
                    text = stringResource(id = R.string.speed_history_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        } else if (hasFullHistory && history.size >= 2) {
            // A single result is not a trend; the chart starts with the second test.
            item { HistoryChart(history = history) }
        }
        items(visibleHistory, key = { it.id }) { result ->
            HistoryRow(result = result)
            HorizontalDivider()
        }
        if (!hasFullHistory && history.isNotEmpty()) {
            item {
                LockedFeatureCard(
                    title = stringResource(id = R.string.speed_history_locked_title),
                    description = stringResource(id = R.string.speed_history_locked_desc, BASIC_HISTORY_LIMIT),
                    onUnlock = onNavigateToPaywall
                )
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            title = { Text(stringResource(id = R.string.speed_history_clear_title)) },
            text = { Text(stringResource(id = R.string.speed_history_clear_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmClear = false
                    viewModel.clearHistory()
                }) { Text(stringResource(id = R.string.delete)) }
            },
            dismissButton = {
                TextButton(onClick = { confirmClear = false }) { Text(stringResource(id = R.string.cancel)) }
            }
        )
    }
}

@Composable
private fun SpeedGauge(progress: SpeedTestProgress?, isRunning: Boolean) {
    val phase = progress?.phase
    val centerValue = when {
        progress == null -> null
        progress.liveMbps != null -> progress.liveMbps
        phase == SpeedTestPhase.DONE -> progress.downloadMbps
        else -> null
    }
    val phaseText = stringResource(
        id = when (phase) {
            null -> R.string.speed_phase_idle
            SpeedTestPhase.CONNECTING -> R.string.speed_phase_connecting
            SpeedTestPhase.LATENCY -> R.string.speed_phase_latency
            SpeedTestPhase.DOWNLOAD -> R.string.speed_phase_download
            SpeedTestPhase.UPLOAD -> R.string.speed_phase_upload
            SpeedTestPhase.DONE -> R.string.speed_phase_done
        }
    )
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(200.dp)) {
            if (isRunning) {
                CircularProgressIndicator(
                    progress = { progress?.phaseProgress ?: 0f },
                    strokeWidth = 10.dp,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                CircularProgressIndicator(
                    progress = { if (phase == SpeedTestPhase.DONE) 1f else 0f },
                    strokeWidth = 10.dp,
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxSize()
                )
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(
                    text = centerValue?.let { BrFormatters.formatNumber(it, 1) } ?: "—",
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(text = "Mbps", style = MaterialTheme.typography.labelLarge)
            }
        }
        Text(
            text = phaseText,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier
                .padding(top = 8.dp)
                .semantics { liveRegion = LiveRegionMode.Polite }
        )
    }
}

@Composable
private fun ResultTiles(progress: SpeedTestProgress?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 600.dp),
        horizontalArrangement = Arrangement.SpaceEvenly
    ) {
        MetricTile(
            label = stringResource(id = R.string.metric_download),
            value = progress?.downloadMbps.formatOrDash(1),
            unit = "Mbps",
            valueColor = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = stringResource(id = R.string.metric_upload),
            value = progress?.uploadMbps.formatOrDash(1),
            unit = "Mbps",
            valueColor = MaterialTheme.colorScheme.tertiary,
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = stringResource(id = R.string.metric_ping),
            value = progress?.pingMs.formatOrDash(0),
            unit = "ms",
            modifier = Modifier.weight(1f)
        )
        MetricTile(
            label = stringResource(id = R.string.metric_jitter),
            value = progress?.jitterMs.formatOrDash(1),
            unit = "ms",
            modifier = Modifier.weight(1f)
        )
    }
}

@Composable
private fun HistoryChart(history: List<SpeedTestResult>) {
    val points = history.take(CHART_POINTS).reversed()
    SectionCard(title = stringResource(id = R.string.speed_chart_title, points.size)) {
        LineChart(
            series = listOf(
                ChartSeries(stringResource(id = R.string.metric_download), points.map { it.downloadMbps }, MaterialTheme.colorScheme.primary),
                ChartSeries(stringResource(id = R.string.metric_upload), points.map { it.uploadMbps }, MaterialTheme.colorScheme.tertiary)
            ),
            description = stringResource(id = R.string.speed_chart_description, points.size)
        )
    }
}

@Composable
private fun HistoryRow(result: SpeedTestResult) {
    val connectionLabel = result.networkName ?: TransportType.entries
        .firstOrNull { it.name == result.connectionType }
        ?.let { stringResource(id = it.labelResId) }
        .orEmpty()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp)
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = BrFormatters.formatDateTime(result.measuredAt),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            Text(text = connectionLabel, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text(
            text = stringResource(
                id = R.string.speed_history_row,
                BrFormatters.formatNumber(result.downloadMbps, 1),
                BrFormatters.formatNumber(result.uploadMbps, 1),
                result.pingMs.formatOrDash(0)
            ),
            style = MaterialTheme.typography.bodyMedium
        )
    }
}

private fun Double?.formatOrDash(decimals: Int): String =
    this?.let { BrFormatters.formatNumber(it, decimals) } ?: "—"

private fun SpeedTestResult.toProgress() = SpeedTestProgress(
    phase = SpeedTestPhase.DONE,
    downloadMbps = downloadMbps,
    uploadMbps = uploadMbps,
    pingMs = pingMs,
    jitterMs = jitterMs
)
