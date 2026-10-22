package br.com.timasters.app.ui.screens.business

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.core.format.MoneyInput
import br.com.timasters.app.data.business.DashboardSummary
import br.com.timasters.app.data.business.MonthRevenue
import br.com.timasters.app.data.business.StockLevel
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.DashboardViewModel
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle

private val MONTH_TITLE = DateTimeFormatter.ofPattern("MMMM 'de' yyyy", BrFormatters.LOCALE_PT_BR)

private fun YearMonth.title(): String = MONTH_TITLE.format(this).replaceFirstChar { it.uppercase() }
private fun YearMonth.short(): String = month.getDisplayName(TextStyle.SHORT, BrFormatters.LOCALE_PT_BR).removeSuffix(".")

/** Monthly numbers: revenue, profit, open work, quotes, hours, best clients and low stock. */
@Composable
fun DashboardTab(onOpenStock: () -> Unit, viewModel: DashboardViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val summary = state.summary ?: return

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                IconButton(onClick = viewModel::previousMonth) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = stringResource(id = R.string.dashboard_previous_month))
                }
                Text(
                    text = summary.month.title(),
                    style = MaterialTheme.typography.titleLarge,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f)
                )
                IconButton(onClick = viewModel::nextMonth, enabled = !state.isCurrentMonth) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = stringResource(id = R.string.dashboard_next_month))
                }
            }
        }

        item { RevenueCard(summary) }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = stringResource(id = R.string.dashboard_done_orders),
                    value = summary.doneCount.toString(),
                    detail = stringResource(id = R.string.dashboard_average_ticket, MoneyInput.formatCents(summary.averageTicketCents)),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(id = R.string.dashboard_open_orders),
                    value = summary.openOrdersCount.toString(),
                    detail = MoneyInput.formatCents(summary.openOrdersCents),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item {
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                StatCard(
                    label = stringResource(id = R.string.dashboard_quotes),
                    value = "${summary.quotesApproved}/${summary.quotesSent}",
                    detail = summary.approvalPercent?.let { stringResource(id = R.string.dashboard_approval, it) }
                        ?: stringResource(id = R.string.dashboard_no_quotes),
                    modifier = Modifier.weight(1f)
                )
                StatCard(
                    label = stringResource(id = R.string.dashboard_hours),
                    value = BusinessTime.formatDuration(summary.minutesWorked * 60_000),
                    detail = stringResource(id = R.string.dashboard_hours_detail),
                    modifier = Modifier.weight(1f)
                )
            }
        }

        item { BreakdownCard(summary) }

        item {
            SectionCard(title = stringResource(id = R.string.dashboard_history)) {
                RevenueBars(summary.history)
            }
        }

        if (summary.topClients.isNotEmpty()) {
            item {
                SectionCard(title = stringResource(id = R.string.dashboard_top_clients)) {
                    summary.topClients.forEachIndexed { index, client ->
                        InfoRow(
                            label = "${index + 1}. ${client.name} · " +
                                stringResource(id = R.string.dashboard_client_orders, client.orders),
                            value = MoneyInput.formatCents(client.revenueCents)
                        )
                    }
                }
            }
        }

        item { LowStockCard(state.lowStock, onOpenStock) }

        item {
            Text(
                text = stringResource(id = R.string.dashboard_footnote),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun RevenueCard(summary: DashboardSummary) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                stringResource(id = R.string.dashboard_revenue),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                MoneyInput.formatCents(summary.revenueCents),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            val growth = summary.growthPercent
            Text(
                text = when {
                    growth == null -> stringResource(id = R.string.dashboard_no_previous)
                    growth >= 0 -> stringResource(id = R.string.dashboard_growth_up, growth)
                    else -> stringResource(id = R.string.dashboard_growth_down, -growth)
                },
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
            Text(
                text = stringResource(id = R.string.dashboard_gross_profit, MoneyInput.formatCents(summary.grossProfitCents)),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.padding(top = 8.dp)
            )
        }
    }
}

@Composable
private fun StatCard(label: String, value: String, detail: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2)
        }
    }
}

@Composable
private fun BreakdownCard(summary: DashboardSummary) {
    SectionCard(title = stringResource(id = R.string.dashboard_breakdown)) {
        InfoRow(stringResource(id = R.string.dashboard_labor), MoneyInput.formatCents(summary.laborCents))
        InfoRow(stringResource(id = R.string.dashboard_items), MoneyInput.formatCents(summary.itemsCents))
        if (summary.otherCents != 0L) {
            InfoRow(stringResource(id = R.string.dashboard_other), MoneyInput.formatCents(summary.otherCents))
        }
        InfoRow(stringResource(id = R.string.dashboard_parts_cost), "- " + MoneyInput.formatCents(summary.partsCostCents))
        InfoRow(stringResource(id = R.string.dashboard_profit_line), MoneyInput.formatCents(summary.grossProfitCents))
    }
}

/** Revenue of the last months as bars; the selected month is highlighted. */
@Composable
private fun RevenueBars(history: List<MonthRevenue>) {
    val max = history.maxOfOrNull { it.revenueCents }?.coerceAtLeast(1) ?: 1
    val highlight = MaterialTheme.colorScheme.primary
    val normal = MaterialTheme.colorScheme.primary.copy(alpha = 0.35f)
    val description = history.joinToString { "${it.month.short()}: ${MoneyInput.formatCents(it.revenueCents)}" }
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(120.dp)
            .semantics { contentDescription = description }
    ) {
        val slot = size.width / history.size
        val barWidth = slot * 0.6f
        history.forEachIndexed { index, month ->
            val height = (month.revenueCents.toFloat() / max) * size.height
            drawRoundRect(
                color = if (index == history.lastIndex) highlight else normal,
                topLeft = Offset(index * slot + (slot - barWidth) / 2, size.height - height),
                size = Size(barWidth, height.coerceAtLeast(2f)),
                cornerRadius = CornerRadius(6f, 6f)
            )
        }
    }
    Row(modifier = Modifier.fillMaxWidth()) {
        history.forEach { month ->
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.weight(1f)) {
                Text(month.month.short(), style = MaterialTheme.typography.labelSmall)
                Text(
                    compactMoney(month.revenueCents),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

/** "R$ 12,3 mil" for the small labels under the bars. */
private fun compactMoney(cents: Long): String {
    val reais = cents / 100.0
    return if (reais >= 1000) "R$ ${BrFormatters.formatNumber(reais / 1000, 1)} mil" else "R$ ${BrFormatters.formatNumber(reais, 0)}"
}

@Composable
private fun LowStockCard(lowStock: List<StockLevel>, onOpenStock: () -> Unit) {
    SectionCard(title = stringResource(id = R.string.dashboard_stock)) {
        if (lowStock.isEmpty()) {
            Text(stringResource(id = R.string.dashboard_stock_ok), style = MaterialTheme.typography.bodyMedium)
        } else {
            lowStock.take(5).forEach { level ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        Icons.Default.Warning,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(end = 8.dp)
                    )
                    InfoRow(level.item.name, "${MoneyInput.formatQuantity(level.quantity)} ${level.item.unit}")
                }
            }
            if (lowStock.size > 5) {
                Text(pluralStringResource(id = R.plurals.dashboard_stock_more, lowStock.size - 5, lowStock.size - 5), style = MaterialTheme.typography.bodySmall)
            }
        }
        TextButton(onClick = onOpenStock) { Text(stringResource(id = R.string.stock_open)) }
    }
}
