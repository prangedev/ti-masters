package br.com.timasters.app.ui.screens.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.network.SignalQuality
import br.com.timasters.app.core.network.WifiMath
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.WifiViewModel

private const val RSSI_HISTORY_SIZE = WifiViewModel.RSSI_HISTORY_SIZE

@Composable
fun WifiTab(
    modifier: Modifier = Modifier,
    viewModel: WifiViewModel = hiltViewModel()
) {
    val access by viewModel.access.collectAsStateWithLifecycle()
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val rssiHistory by viewModel.rssiHistory.collectAsStateWithLifecycle()

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        WifiAccessCards(access = access, onAccessMayHaveChanged = viewModel::refreshAccess)

        val current = connection
        if (current == null) {
            if (access.isWifiEnabled) {
                MessageCard(text = stringResource(id = R.string.wifi_not_connected), isError = false)
            }
            return@Column
        }

        val quality = WifiMath.signalQuality(current.rssiDbm)
        val qualityColor = qualityColor(quality)
        SectionCard(title = current.ssid ?: stringResource(id = R.string.wifi_name_hidden)) {
            Text(
                text = stringResource(id = R.string.wifi_rssi_value, current.rssiDbm),
                style = MaterialTheme.typography.displaySmall,
                fontWeight = FontWeight.Bold,
                color = qualityColor
            )
            Text(
                text = stringResource(
                    id = R.string.wifi_quality_value,
                    stringResource(id = quality.labelResId),
                    WifiMath.signalPercent(current.rssiDbm)
                ),
                style = MaterialTheme.typography.titleSmall
            )
            LinearProgressIndicator(
                progress = { WifiMath.signalPercent(current.rssiDbm) / 100f },
                color = qualityColor,
                modifier = Modifier.fillMaxWidth()
            )
            RssiHistoryChart(values = rssiHistory, color = qualityColor)
        }

        if (quality == SignalQuality.WEAK || quality == SignalQuality.VERY_WEAK) {
            MessageCard(text = stringResource(id = R.string.wifi_weak_tip), isError = false)
        }

        SectionCard(title = stringResource(id = R.string.wifi_details_title)) {
            val band = WifiMath.bandOf(current.frequencyMhz)
            InfoRow(stringResource(id = R.string.wifi_bssid), current.bssid ?: "—")
            InfoRow(stringResource(id = R.string.wifi_band), stringResource(id = band.labelResId))
            InfoRow(stringResource(id = R.string.wifi_channel), WifiMath.channelOf(current.frequencyMhz)?.toString() ?: "—")
            InfoRow(stringResource(id = R.string.wifi_frequency), "${current.frequencyMhz} MHz")
            InfoRow(stringResource(id = R.string.wifi_standard), current.standard ?: "—")
            InfoRow(stringResource(id = R.string.wifi_link_speed), current.linkSpeedMbps?.let { "$it Mbps" } ?: "—")
            if (current.txLinkSpeedMbps != null || current.rxLinkSpeedMbps != null) {
                InfoRow(
                    stringResource(id = R.string.wifi_tx_rx),
                    "${current.txLinkSpeedMbps ?: "—"} / ${current.rxLinkSpeedMbps ?: "—"} Mbps"
                )
            }
        }
    }
}

@Composable
internal fun qualityColor(quality: SignalQuality): Color = when (quality) {
    SignalQuality.EXCELLENT, SignalQuality.GOOD -> Color(0xFF2E7D32)
    SignalQuality.FAIR -> Color(0xFFF9A825)
    SignalQuality.WEAK, SignalQuality.VERY_WEAK -> MaterialTheme.colorScheme.error
}

/** Last minute of signal readings, -100 dBm at the bottom and -30 dBm at the top. */
@Composable
private fun RssiHistoryChart(values: List<Int>, color: Color) {
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val description = stringResource(id = R.string.wifi_rssi_chart_description)
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(80.dp)
            .semantics { contentDescription = description }
    ) {
        fun y(rssi: Int) = size.height * (1f - ((rssi + 100).coerceIn(0, 70) / 70f))
        listOf(-50, -70).forEach { level ->
            drawLine(gridColor, Offset(0f, y(level)), Offset(size.width, y(level)), strokeWidth = 1.dp.toPx())
        }
        if (values.size < 2) return@Canvas
        val step = size.width / (RSSI_HISTORY_SIZE - 1)
        val startX = size.width - (values.size - 1) * step
        val path = Path()
        values.forEachIndexed { index, rssi ->
            val point = Offset(startX + index * step, y(rssi))
            if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx()))
    }
}
