package br.com.timasters.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import br.com.timasters.app.core.format.BrFormatters

data class ChartSeries(val label: String, val values: List<Double>, val color: Color)

/**
 * Minimal line chart (oldest point on the left). Drawn with Canvas to avoid a chart library;
 * [description] is read by screen readers instead of the drawing.
 */
@Composable
fun LineChart(
    series: List<ChartSeries>,
    description: String,
    modifier: Modifier = Modifier
) {
    val textMeasurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outline
    val labelStyle = MaterialTheme.typography.labelSmall.copy(color = MaterialTheme.colorScheme.onSurfaceVariant)
    val maxValue = series.flatMap { it.values }.maxOrNull()?.takeIf { it > 0 } ?: 1.0

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Canvas(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp)
                .semantics { contentDescription = description }
        ) {
            val maxLabel = textMeasurer.measure(BrFormatters.formatNumber(maxValue, 0), labelStyle)
            val left = maxLabel.size.width + 8.dp.toPx()
            val chartWidth = size.width - left
            val chartHeight = size.height - 4.dp.toPx()

            drawText(maxLabel, topLeft = Offset(0f, 0f))
            drawText(textMeasurer.measure("0", labelStyle), topLeft = Offset(0f, chartHeight - maxLabel.size.height))
            drawLine(axisColor, Offset(left, 0f), Offset(left, chartHeight), strokeWidth = 1.dp.toPx())
            drawLine(axisColor, Offset(left, chartHeight), Offset(size.width, chartHeight), strokeWidth = 1.dp.toPx())

            series.forEach { line ->
                if (line.values.isEmpty()) return@forEach
                val stepX = if (line.values.size > 1) chartWidth / (line.values.size - 1) else 0f
                val path = Path()
                line.values.forEachIndexed { index, value ->
                    val point = Offset(left + index * stepX, chartHeight - (value / maxValue).toFloat() * chartHeight)
                    if (index == 0) path.moveTo(point.x, point.y) else path.lineTo(point.x, point.y)
                    drawCircle(line.color, radius = 3.dp.toPx(), center = point)
                }
                drawPath(path, line.color, style = Stroke(width = 2.dp.toPx()))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            series.forEach { line ->
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Box(
                        modifier = Modifier
                            .size(10.dp)
                            .background(line.color, CircleShape)
                    )
                    Text(text = line.label, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
