package br.com.timasters.app.ui.screens.business

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Checklist
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.MoneyInput
import br.com.timasters.app.data.business.StockLevel
import br.com.timasters.app.data.business.StockMovementKind
import br.com.timasters.app.data.business.StockMovementWithOrder
import br.com.timasters.app.ui.components.AppScreenScaffold
import br.com.timasters.app.ui.components.BackIcon
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.StockAction
import br.com.timasters.app.ui.viewmodel.StockItemViewModel
import br.com.timasters.app.ui.viewmodel.StockViewModel

// region List

@Composable
fun StockScreen(
    onOpenItem: (String) -> Unit,
    onOpenCatalog: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StockViewModel = hiltViewModel()
) {
    val levels by viewModel.levels.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val onlyLow by viewModel.onlyLow.collectAsStateWithLifecycle()

    AppScreenScaffold(
        title = stringResource(id = R.string.stock_title),
        navigationIcon = { BackIcon(onBack) },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            item {
                OutlinedTextField(
                    value = query,
                    onValueChange = viewModel::onQueryChange,
                    label = { Text(stringResource(id = R.string.catalog_search)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
            item {
                FilterChip(
                    selected = onlyLow,
                    onClick = { viewModel.onOnlyLowChange(!onlyLow) },
                    label = { Text(stringResource(id = R.string.stock_only_low)) }
                )
            }
            if (levels.isEmpty()) {
                item {
                    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                        EmptyMessage(stringResource(id = if (onlyLow) R.string.stock_none_low else R.string.stock_empty))
                        if (!onlyLow) TextButton(onClick = onOpenCatalog) { Text(stringResource(id = R.string.catalog_manage)) }
                    }
                }
            }
            items(levels, key = { it.item.id }) { level -> StockCard(level, onClick = { onOpenItem(level.item.id) }) }
        }
    }
}

@Composable
private fun StockCard(level: StockLevel, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (level.isLow) MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(modifier = Modifier.weight(1f)) {
                Text(level.item.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (level.isLow) stringResource(id = R.string.stock_low_badge)
                    else stringResource(id = R.string.stock_minimum, MoneyInput.formatQuantity(level.item.minStock), level.item.unit),
                    style = MaterialTheme.typography.bodySmall,
                    color = if (level.isLow) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "${MoneyInput.formatQuantity(level.quantity)} ${level.item.unit}",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold
            )
        }
    }
}

// endregion

// region Item

@Composable
fun StockItemScreen(
    itemId: String,
    onEditItem: () -> Unit,
    onOpenOrder: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: StockItemViewModel = hiltViewModel(key = "stock-$itemId")
) {
    LaunchedEffect(itemId) { viewModel.load(itemId) }
    val level by viewModel.level.collectAsStateWithLifecycle()
    val history by viewModel.history.collectAsStateWithLifecycle()
    val defaultCost by viewModel.defaultCost.collectAsStateWithLifecycle()
    var action by rememberSaveable { mutableStateOf<StockAction?>(null) }

    val current = level
    AppScreenScaffold(
        title = current?.item?.name.orEmpty(),
        navigationIcon = { BackIcon(onBack) },
        actions = { TextButton(onClick = onEditItem) { Text(stringResource(id = R.string.edit)) } },
        modifier = modifier
    ) { innerPadding ->
        if (current == null) return@AppScreenScaffold
        val unit = current.item.unit
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard(title = stringResource(id = R.string.stock_current)) {
                    Text(
                        text = "${MoneyInput.formatQuantity(current.quantity)} $unit",
                        style = MaterialTheme.typography.headlineMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (current.isLow) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface
                    )
                    if (current.isLow) Text(stringResource(id = R.string.stock_low_badge), color = MaterialTheme.colorScheme.error)
                    InfoRow(stringResource(id = R.string.stock_minimum_label), "${MoneyInput.formatQuantity(current.item.minStock)} $unit")
                    if (current.item.costCents > 0) {
                        InfoRow(stringResource(id = R.string.field_cost), MoneyInput.formatCents(current.item.costCents))
                        InfoRow(
                            stringResource(id = R.string.stock_value),
                            MoneyInput.formatCents(Math.round(current.quantity.coerceAtLeast(0.0) * current.item.costCents))
                        )
                    }
                    InfoRow(stringResource(id = R.string.field_price), MoneyInput.formatCents(current.item.priceCents))
                }
            }
            item {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { action = StockAction.IN }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(id = R.string.stock_action_in), modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                    }
                    OutlinedButton(onClick = { action = StockAction.OUT }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Remove, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(id = R.string.stock_action_out), modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                    }
                    OutlinedButton(onClick = { action = StockAction.COUNT }, modifier = Modifier.weight(1f)) {
                        Icon(Icons.Default.Checklist, contentDescription = null, modifier = Modifier.size(18.dp))
                        Text(stringResource(id = R.string.stock_action_count), modifier = Modifier.padding(start = 4.dp), maxLines = 1)
                    }
                }
            }
            item { Text(stringResource(id = R.string.stock_history), style = MaterialTheme.typography.titleMedium) }
            if (history.isEmpty()) item { EmptyMessage(stringResource(id = R.string.stock_history_empty)) }
            items(history, key = { it.movement.id }) { entry ->
                MovementRow(entry, unit, onOpenOrder = onOpenOrder, onUndo = { viewModel.undo(entry.movement.id) })
            }
        }
    }

    action?.let { current ->
        StockActionDialog(
            action = current,
            unit = level?.item?.unit.orEmpty(),
            defaultCost = defaultCost,
            onDismiss = { action = null },
            onConfirm = { quantity, cost, note -> viewModel.apply(current, quantity, cost, note).also { if (it) action = null } }
        )
    }
}

@Composable
private fun MovementRow(entry: StockMovementWithOrder, unit: String, onOpenOrder: (String) -> Unit, onUndo: () -> Unit) {
    val movement = entry.movement
    val kind = movement.movementKind
    var confirmUndo by rememberSaveable { mutableStateOf(false) }
    val sign = if (movement.quantity > 0) "+" else ""
    ListItem(
        headlineContent = {
            Text(
                if (kind == StockMovementKind.SERVICE_ORDER && entry.orderNumber != null) {
                    stringResource(id = R.string.stock_kind_service_order_number, entry.orderNumber)
                } else {
                    stringResource(id = kind.labelResId)
                }
            )
        },
        supportingContent = {
            Text(listOfNotNull(BusinessTime.formatDateTime(movement.createdAt), movement.note).joinToString(" · "))
        },
        trailingContent = {
            Text(
                "$sign${MoneyInput.formatQuantity(movement.quantity)} $unit",
                fontWeight = FontWeight.Bold,
                color = if (movement.quantity < 0) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
            )
        },
        modifier = Modifier.fillMaxWidth(),
        tonalElevation = 1.dp
    )
    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
        if (kind == StockMovementKind.SERVICE_ORDER) {
            movement.orderId?.let { orderId ->
                TextButton(onClick = { onOpenOrder(orderId) }) { Text(stringResource(id = R.string.stock_open_order)) }
            }
        } else {
            TextButton(onClick = { confirmUndo = true }) { Text(stringResource(id = R.string.stock_undo)) }
        }
    }
    if (confirmUndo) {
        AlertDialog(
            onDismissRequest = { confirmUndo = false },
            title = { Text(stringResource(id = R.string.stock_undo_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmUndo = false
                    onUndo()
                }) { Text(stringResource(id = R.string.stock_undo)) }
            },
            dismissButton = { TextButton(onClick = { confirmUndo = false }) { Text(stringResource(id = R.string.cancel)) } }
        )
    }
}

@Composable
private fun StockActionDialog(
    action: StockAction,
    unit: String,
    defaultCost: String,
    onDismiss: () -> Unit,
    onConfirm: (String, String, String) -> Boolean
) {
    var quantity by rememberSaveable { mutableStateOf("") }
    var cost by rememberSaveable { mutableStateOf(defaultCost) }
    var note by rememberSaveable { mutableStateOf("") }
    var invalid by rememberSaveable { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                stringResource(
                    id = when (action) {
                        StockAction.IN -> R.string.stock_dialog_in
                        StockAction.OUT -> R.string.stock_dialog_out
                        StockAction.COUNT -> R.string.stock_dialog_count
                    }
                )
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                if (action == StockAction.COUNT) {
                    Text(stringResource(id = R.string.stock_count_hint), style = MaterialTheme.typography.bodySmall)
                }
                MoneyField(
                    value = quantity,
                    label = stringResource(
                        id = if (action == StockAction.COUNT) R.string.stock_counted_quantity else R.string.field_quantity
                    ) + " ($unit)",
                    onValueChange = { quantity = it.take(10) },
                    error = stringResource(id = R.string.error_quantity_invalid).takeIf { invalid }
                )
                if (action == StockAction.IN) {
                    MoneyField(value = cost, label = stringResource(id = R.string.stock_unit_cost_optional), onValueChange = { cost = it.take(12) })
                }
                OutlinedTextField(
                    value = note,
                    onValueChange = { note = it.take(300) },
                    label = { Text(stringResource(id = R.string.stock_note_optional)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { invalid = !onConfirm(quantity, if (action == StockAction.IN) cost else "", note) }) {
                Text(stringResource(id = R.string.save))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.cancel)) } }
    )
}

// endregion
