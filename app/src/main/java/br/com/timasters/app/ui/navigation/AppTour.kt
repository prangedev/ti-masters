package br.com.timasters.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import br.com.timasters.app.R

data class TourStep(
    val screen: Screen,
    @param:StringRes val titleResId: Int,
    @param:StringRes val textResId: Int
)

/** One step per tab of the bottom bar, in the order they appear. */
val TOUR_STEPS = listOf(
    TourStep(Screen.Diagnostics, R.string.tour_diagnostics_title, R.string.tour_diagnostics_text),
    TourStep(Screen.Network, R.string.tour_network_title, R.string.tour_network_text),
    TourStep(Screen.Routers, R.string.tour_routers_title, R.string.tour_routers_text),
    TourStep(Screen.Business, R.string.tour_business_title, R.string.tour_business_text),
    TourStep(Screen.Settings, R.string.tour_settings_title, R.string.tour_settings_text)
)

/** First-launch question: take the tour now or skip it. */
@Composable
fun TourIntroDialog(onStart: () -> Unit, onSkip: () -> Unit) {
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text(stringResource(id = R.string.tour_intro_title)) },
        text = { Text(stringResource(id = R.string.tour_intro_text)) },
        confirmButton = { Button(onClick = onStart) { Text(stringResource(id = R.string.tour_start)) } },
        dismissButton = { TextButton(onClick = onSkip) { Text(stringResource(id = R.string.tour_skip)) } }
    )
}

/**
 * Dims the screen, cuts a spotlight around [target] (a bottom-bar or rail icon, in root
 * coordinates) and shows the explanation card next to it. Touches outside the card are blocked.
 */
@Composable
fun TourOverlay(
    stepIndex: Int,
    target: Rect?,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit,
    modifier: Modifier = Modifier
) {
    val step = TOUR_STEPS[stepIndex]
    val isLast = stepIndex == TOUR_STEPS.lastIndex
    val ringColor = MaterialTheme.colorScheme.primary

    Box(
        modifier = modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                // Swallow touches so the app underneath cannot be used mid-tour.
                awaitEachGesture { awaitFirstDown(requireUnconsumed = false).consume() }
            }
    ) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer(compositingStrategy = CompositingStrategy.Offscreen)
        ) {
            drawRect(Color.Black.copy(alpha = 0.7f))
            if (target != null) {
                val radius = SPOTLIGHT_RADIUS.toPx()
                drawCircle(Color.Transparent, radius = radius, center = target.center, blendMode = BlendMode.Clear)
                drawCircle(ringColor, radius = radius, center = target.center, style = Stroke(width = 3.dp.toPx()))
            }
        }

        Layout(
            content = {
                TourCard(
                    stepIndex = stepIndex,
                    step = step,
                    isLast = isLast,
                    onNext = onNext,
                    onPrevious = onPrevious,
                    onSkip = onSkip
                )
            }
        ) { measurables, constraints ->
            val margin = 16.dp.roundToPx()
            val radius = SPOTLIGHT_RADIUS.roundToPx()
            val card = measurables.first().measure(
                constraints.copy(minWidth = 0, minHeight = 0, maxWidth = (constraints.maxWidth - 2 * margin).coerceAtLeast(0))
            )
            val width = constraints.maxWidth
            val height = constraints.maxHeight
            val (x, y) = when {
                target == null -> (width - card.width) / 2 to (height - card.height) / 2
                // Navigation rail (tablets/landscape): card to the right of the icon.
                target.center.x < width * 0.3f && target.center.y < height * 0.85f ->
                    (target.center.x.toInt() + radius + margin) to (target.center.y.toInt() - card.height / 2)
                // Bottom bar: card above the icon.
                else -> (target.center.x.toInt() - card.width / 2) to (target.center.y.toInt() - radius - margin - card.height)
            }
            layout(width, height) {
                card.place(
                    x.coerceIn(margin, (width - card.width - margin).coerceAtLeast(margin)),
                    y.coerceIn(margin, (height - card.height - margin).coerceAtLeast(margin))
                )
            }
        }
    }
}

@Composable
private fun TourCard(
    stepIndex: Int,
    step: TourStep,
    isLast: Boolean,
    onNext: () -> Unit,
    onPrevious: () -> Unit,
    onSkip: () -> Unit
) {
    Card(
        modifier = Modifier
            .widthIn(max = 400.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 8.dp)
    ) {
        Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(id = R.string.tour_step_counter, stepIndex + 1, TOUR_STEPS.size),
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary
            )
            Text(text = stringResource(id = step.titleResId), style = MaterialTheme.typography.titleLarge)
            Text(text = stringResource(id = step.textResId), style = MaterialTheme.typography.bodyMedium)
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = onSkip) { Text(stringResource(id = R.string.tour_skip)) }
                Spacer(modifier = Modifier.weight(1f))
                if (stepIndex > 0) {
                    TextButton(onClick = onPrevious) { Text(stringResource(id = R.string.back)) }
                }
                Button(onClick = onNext) {
                    Text(stringResource(id = if (isLast) R.string.tour_finish else R.string.tour_next))
                }
            }
        }
    }
}

private val SPOTLIGHT_RADIUS = 36.dp
