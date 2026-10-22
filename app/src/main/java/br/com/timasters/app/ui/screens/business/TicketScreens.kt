package br.com.timasters.app.ui.screens.business

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.data.business.OrderKind
import br.com.timasters.app.data.business.TicketPriority
import br.com.timasters.app.data.business.TicketStatus
import br.com.timasters.app.ui.components.AppScreenScaffold
import br.com.timasters.app.ui.components.BackIcon
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.TicketDetailViewModel
import br.com.timasters.app.ui.viewmodel.TicketEditViewModel

@Composable
fun TicketDetailScreen(
    ticketId: String,
    navigate: (BusinessRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TicketDetailViewModel = hiltViewModel(key = "ticket-$ticketId")
) {
    LaunchedEffect(ticketId) { viewModel.load(ticketId) }
    val item by viewModel.ticket.collectAsStateWithLifecycle()
    val timeEntries by viewModel.timeEntries.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val current = item

    AppScreenScaffold(
        title = current?.let { ticketLabel(it.ticket.number, it.ticket.title) }.orEmpty(),
        navigationIcon = { BackIcon(onBack) },
        actions = {
            IconButton(onClick = { navigate(BusinessRoute.TicketEdit(ticketId)) }) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(id = R.string.edit))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.delete))
            }
        },
        modifier = modifier
    ) { innerPadding ->
        if (current == null) return@AppScreenScaffold
        val ticket = current.ticket
        val status = TicketStatus.from(ticket.status)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard(title = ticket.title) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val priority = TicketPriority.from(ticket.priority)
                        Badge(stringResource(id = priority.labelResId), priorityColor(priority))
                        Badge(stringResource(id = status.labelResId), statusColor(status))
                    }
                    TextButton(onClick = { navigate(BusinessRoute.ClientDetail(ticket.clientId)) }) {
                        Text(stringResource(id = R.string.ticket_client_link, current.clientName))
                    }
                    InfoRow(stringResource(id = R.string.ticket_opened_at), BusinessTime.formatDateTime(ticket.createdAt))
                    ticket.dueAt?.let { InfoRow(stringResource(id = R.string.field_due_date), BusinessTime.formatDate(it)) }
                    ticket.closedAt?.let { InfoRow(stringResource(id = R.string.ticket_closed_at), BusinessTime.formatDateTime(it)) }
                    if (ticket.number == null) {
                        Text(
                            text = stringResource(id = R.string.ticket_number_pending_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    ticket.description?.let { Text(text = it, style = MaterialTheme.typography.bodyMedium) }
                }
            }

            item {
                OptionField(
                    label = stringResource(id = R.string.field_status),
                    options = TicketStatus.entries,
                    selected = status,
                    optionLabel = { stringResource(id = it.labelResId) },
                    onSelect = viewModel::setStatus
                )
            }

            item {
                val runningHere = running?.takeIf { it.entry.ticketId == ticketId }
                TimerCard(
                    running = runningHere,
                    onStart = if (running == null && status.isOpen) viewModel::startTimer else null,
                    onStop = { runningHere?.let { viewModel.stopTimer(it.entry.id) } },
                    startLabel = stringResource(id = R.string.timer_start_for_ticket)
                )
            }

            item {
                OutlinedButton(
                    onClick = { navigate(BusinessRoute.AppointmentEdit(null, ticket.clientId, ticket.id)) },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(id = R.string.ticket_schedule_visit)) }
            }

            item {
                SectionHeader(
                    title = stringResource(id = R.string.business_tab_orders),
                    action = stringResource(id = R.string.order_new_quote),
                    onAction = { navigate(BusinessRoute.OrderEdit(null, OrderKind.QUOTE, ticket.clientId, ticket.id)) }
                )
            }
            if (orders.isEmpty()) {
                item {
                    OutlinedButton(
                        onClick = { navigate(BusinessRoute.OrderEdit(null, OrderKind.SERVICE_ORDER, ticket.clientId, ticket.id)) },
                        modifier = Modifier.fillMaxWidth()
                    ) { Text(stringResource(id = R.string.order_new_service_order)) }
                }
            }
            items(orders, key = { "o-${it.order.id}" }) { item ->
                OrderCard(item = item, onClick = { navigate(BusinessRoute.OrderDetail(item.order.id)) }, showClient = false)
            }

            item {
                SectionHeader(
                    title = stringResource(
                        id = R.string.ticket_hours_total,
                        BusinessTime.formatDuration(timeEntries.sumOf { it.entry.durationMillis() })
                    ),
                    action = null,
                    onAction = {}
                )
            }
            items(timeEntries, key = { it.entry.id }) { entry ->
                TimeEntryRow(item = entry, onClick = { navigate(BusinessRoute.TimeEntryEdit(entry.entry.id)) }, showClient = false)
                HorizontalDivider()
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(id = R.string.ticket_delete_title)) },
            text = { Text(stringResource(id = R.string.ticket_delete_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onDeleted = onBack)
                }) { Text(stringResource(id = R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(id = R.string.cancel)) } }
        )
    }
}

@Composable
fun TicketEditScreen(
    ticketId: String?,
    presetClientId: String?,
    session: String,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TicketEditViewModel = hiltViewModel(key = "ticket-edit")
) {
    LaunchedEffect(session) { viewModel.load(ticketId, presetClientId, session) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()

    AppScreenScaffold(
        title = stringResource(id = if (ticketId == null) R.string.ticket_new else R.string.ticket_edit),
        navigationIcon = { BackIcon(onBack) },
        actions = { TextButton(onClick = { viewModel.save(onSaved) }) { Text(stringResource(id = R.string.save)) } },
        modifier = modifier
    ) { innerPadding ->
        if (!form.isLoaded) return@AppScreenScaffold
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .verticalScroll(rememberScrollState())
                .imePadding()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            if (clients.isEmpty()) {
                Text(stringResource(id = R.string.ticket_needs_client), color = MaterialTheme.colorScheme.error)
            }
            ClientPickerField(
                clients = clients,
                selectedId = form.clientId,
                onSelect = { client -> viewModel.update { it.copy(clientId = client.id) } },
                isError = form.showErrors && form.clientError
            )
            FormTextField(
                value = form.title,
                label = stringResource(id = R.string.field_title),
                onValueChange = { v -> viewModel.update { it.copy(title = v.take(200)) } },
                error = stringResource(id = R.string.error_title_required).takeIf { form.showErrors && form.titleError }
            )
            FormTextField(
                value = form.description,
                label = stringResource(id = R.string.field_description),
                onValueChange = { v -> viewModel.update { it.copy(description = v.take(8000)) } },
                singleLine = false,
                capitalization = KeyboardCapitalization.Sentences
            )
            Text(text = stringResource(id = R.string.field_priority), style = MaterialTheme.typography.labelLarge)
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                TicketPriority.entries.forEachIndexed { index, priority ->
                    SegmentedButton(
                        selected = form.priority == priority,
                        onClick = { viewModel.update { it.copy(priority = priority) } },
                        shape = SegmentedButtonDefaults.itemShape(index, TicketPriority.entries.size),
                        icon = {}
                    ) { Text(stringResource(id = priority.labelResId), maxLines = 1) }
                }
            }
            if (ticketId != null) {
                OptionField(
                    label = stringResource(id = R.string.field_status),
                    options = TicketStatus.entries,
                    selected = form.status,
                    optionLabel = { stringResource(id = it.labelResId) },
                    onSelect = { status -> viewModel.update { it.copy(status = status) } }
                )
            }
            DateField(
                label = stringResource(id = R.string.field_due_date_optional),
                date = form.dueDate,
                onDateChange = { date -> viewModel.update { it.copy(dueDate = date) } },
                allowClear = true
            )
        }
    }
}
