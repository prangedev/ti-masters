package br.com.timasters.app.ui.screens.network

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import br.com.timasters.app.core.network.WifiBand
import br.com.timasters.app.data.wifi.WifiNetwork

private data class BandAxis(val startMhz: Int, val endMhz: Int, val labeledChannels: List<Pair<Int, Int>>)

private fun axisFor(band: WifiBand): BandAxis = when (band) {
    WifiBand.GHZ_2_4 -> BandAxis(2400, 2496, (1..13).map { it to 2407 + 5 * it })
    WifiBand.GHZ_5 -> BandAxis(5160, 5890, listOf(36, 52, 100, 116, 132, 149, 165).map { it to 5000 + 5 * it })
    else -> BandAxis(5925, 7125, listOf(1, 33, 65, 97, 129, 161, 193, 225).map { it to 5950 + 5 * it })
}

/** Distinct colors that stay readable on light and dark backgrounds. */
private val NETWORK_COLORS = listOf(
    Color(0xFF1E88E5), Color(0xFFE53935), Color(0xFF43A047), Color(0xFFFB8C00),
    Color(0xFF8E24AA), Color(0xFF00ACC1), Color(0xFFD81B60), Color(0xFF7CB342)
)

private const val FLOOR_DBM = -100
private const val TOP_DBM = -20

/**
 * Classic Wi-Fi analyzer view: each network is a hump as wide as its channel and as tall as its
 * signal, so overlapping (interfering) networks are easy to spot.
 */
@Composable
fun SpectrumChart(
    band: WifiBand,
    networks: List<WifiNetwork>,
    description: String,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val axis = axisFor(band)
    val gridColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)

    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(220.dp)
            .semantics { contentDescription = description }
    ) {
        val labelHeight = textMeasurer.measure("0", labelStyle).size.height.toFloat()
        val left = textMeasurer.measure("-100", labelStyle).size.width + 6.dp.toPx()
        val bottom = size.height - labelHeight - 4.dp.toPx()
        val width = size.width - left

        fun x(mhz: Int) = left + (mhz - axis.startMhz).toFloat() / (axis.endMhz - axis.startMhz) * width
        fun y(dbm: Int) = bottom * (1f - (dbm.coerceIn(FLOOR_DBM, TOP_DBM) - FLOOR_DBM).toFloat() / (TOP_DBM - FLOOR_DBM))

        listOf(-30, -50, -70, -90).forEach { level ->
            drawLine(gridColor, Offset(left, y(level)), Offset(size.width, y(level)), strokeWidth = 1.dp.toPx())
            drawText(textMeasurer.measure("$level", labelStyle), topLeft = Offset(0f, y(level) - labelHeight / 2))
        }
        drawLine(gridColor, Offset(left, bottom), Offset(size.width, bottom), strokeWidth = 1.dp.toPx())
        axis.labeledChannels.forEach { (channel, mhz) ->
            val label = textMeasurer.measure("$channel", labelStyle)
            drawText(label, topLeft = Offset(x(mhz) - label.size.width / 2, bottom + 2.dp.toPx()))
        }

        networks.forEachIndexed { index, network ->
            val color = NETWORK_COLORS[index % NETWORK_COLORS.size]
            val half = network.channelWidthMhz / 2
            val startX = x(network.centerFrequencyMhz - half)
            val endX = x(network.centerFrequencyMhz + half)
            val peakY = y(network.rssiDbm)
            val shoulder = (endX - startX) * 0.15f
            // A cubic curve only reaches 3/4 of the way to its control points, so they are placed
            // higher for the hump's top to land exactly on the measured signal.
            val controlY = bottom - (bottom - peakY) * 4f / 3f
            val path = Path().apply {
                moveTo(startX, bottom)
                cubicTo(startX + shoulder, controlY, endX - shoulder, controlY, endX, bottom)
            }
            drawPath(path, color.copy(alpha = 0.18f))
            drawPath(path, color, style = Stroke(width = if (network.isConnected) 3.dp.toPx() else 1.5.dp.toPx()))
            val name = network.ssid.ifEmpty { "?" }.take(14)
            val label = textMeasurer.measure(
                name,
                labelStyle.copy(color = color),
                overflow = TextOverflow.Ellipsis,
                maxLines = 1
            )
            val labelX = ((startX + endX) / 2 - label.size.width / 2).coerceIn(left, size.width - label.size.width)
            val labelY = (peakY - label.size.height).coerceAtLeast(0f)
            drawText(label, topLeft = Offset(labelX, labelY))
        }
    }
}
