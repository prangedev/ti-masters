package br.com.timasters.app.ui.screens.business

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.DirectionsCar
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.core.validation.DocumentValidator
import br.com.timasters.app.data.business.AppointmentKind
import br.com.timasters.app.data.business.AppointmentStatus
import br.com.timasters.app.data.business.AppointmentWithClient
import br.com.timasters.app.data.business.ClientEntity
import br.com.timasters.app.data.business.TicketPriority
import br.com.timasters.app.data.business.TicketStatus
import br.com.timasters.app.data.business.TicketWithClient
import br.com.timasters.app.data.business.TimeEntryWithClient
import br.com.timasters.app.ui.viewmodel.AgendaViewModel
import br.com.timasters.app.ui.viewmodel.ClientsViewModel
import br.com.timasters.app.ui.viewmodel.TicketFilter
import br.com.timasters.app.ui.viewmodel.TicketsViewModel
import br.com.timasters.app.ui.viewmodel.TimeViewModel
import kotlinx.coroutines.delay

/** Leaves room at the end of lists so the floating "+" button never covers the last item. */
private val LIST_PADDING = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp)

@Composable
private fun SearchField(value: String, onValueChange: (String) -> Unit, label: String) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        modifier = Modifier.fillMaxWidth()
    )
}

// region Tickets

@Composable
fun TicketsTab(onOpen: (String) -> Unit, viewModel: TicketsViewModel = hiltViewModel()) {
    val tickets by viewModel.tickets.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = LIST_PADDING, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item { SearchField(query, viewModel::onQueryChange, stringResource(id = R.string.tickets_search)) }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TicketFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { viewModel.onFilterChange(option) },
                        label = {
                            Text(
                                stringResource(
                                    id = when (option) {
                                        TicketFilter.OPEN -> R.string.tickets_filter_open
                                        TicketFilter.DONE -> R.string.tickets_filter_done
                                        TicketFilter.ALL -> R.string.tickets_filter_all
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }
        if (tickets.isEmpty()) {
            item { EmptyMessage(stringResource(id = R.string.tickets_empty)) }
        }
        items(tickets, key = { it.ticket.id }) { item ->
            TicketCard(item = item, onClick = { onOpen(item.ticket.id) })
        }
    }
}

@Composable
internal fun TicketCard(item: TicketWithClient, onClick: () -> Unit, showClient: Boolean = true) {
    val priority = TicketPriority.from(item.ticket.priority)
    val status = TicketStatus.from(item.ticket.status)
    val dueAt = item.ticket.dueAt
    val overdue = status.isOpen && dueAt != null && dueAt < System.currentTimeMillis()
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(
                text = ticketLabel(item.ticket.number, item.ticket.title),
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            if (showClient) {
                Text(text = item.clientName, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(stringResource(id = priority.labelResId), priorityColor(priority))
                Badge(stringResource(id = status.labelResId), statusColor(status))
                if (dueAt != null) {
                    Text(
                        text = stringResource(id = if (overdue) R.string.ticket_overdue else R.string.ticket_due, BusinessTime.formatDate(dueAt)),
                        style = MaterialTheme.typography.labelSmall,
                        color = if (overdue) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

/** Small colored label (priority, status). */
@Composable
internal fun Badge(text: String, color: Color) {
    Surface(color = color.copy(alpha = 0.15f), shape = MaterialTheme.shapes.small) {
        Text(
            text = text,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
        )
    }
}

// endregion

// region Clients

@Composable
fun ClientsTab(onOpen: (String) -> Unit, viewModel: ClientsViewModel = hiltViewModel()) {
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = LIST_PADDING, modifier = Modifier.fillMaxSize()) {
        item { SearchField(query, viewModel::onQueryChange, stringResource(id = R.string.clients_search)) }
        if (clients.isEmpty()) {
            item { EmptyMessage(stringResource(id = if (query.isBlank()) R.string.clients_empty else R.string.clients_none_found)) }
        }
        items(clients, key = { it.id }) { client ->
            ClientRow(client = client, onClick = { onOpen(client.id) })
            HorizontalDivider()
        }
    }
}

@Composable
private fun ClientRow(client: ClientEntity, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 12.dp)
    ) {
        Text(text = client.name, style = MaterialTheme.typography.bodyLarge)
        Text(
            text = listOfNotNull(
                client.phone?.let(BrFormatters::formatPhone),
                client.city,
                client.docNumber?.let(DocumentValidator::format)
            ).joinToString(" · "),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

// endregion

// region Agenda

@Composable
fun AgendaTab(onOpen: (String) -> Unit, viewModel: AgendaViewModel = hiltViewModel()) {
    val days by viewModel.days.collectAsStateWithLifecycle()

    LazyColumn(contentPadding = LIST_PADDING, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        if (days.isEmpty()) {
            item { EmptyMessage(stringResource(id = R.string.agenda_empty)) }
        }
        days.forEach { (date, appointments) ->
            item(key = date.toString()) {
                Text(
                    text = dayLabel(date),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
            items(appointments, key = { it.appointment.id }) { item ->
                AppointmentCard(item = item, onClick = { onOpen(item.appointment.id) })
            }
        }
    }
}

@Composable
internal fun AppointmentCard(item: AppointmentWithClient, onClick: () -> Unit, showDate: Boolean = false) {
    val appointment = item.appointment
    val kind = AppointmentKind.from(appointment.kind)
    val status = AppointmentStatus.from(appointment.status)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(modifier = Modifier.padding(12.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(
                imageVector = if (kind == AppointmentKind.REMOTE) Icons.Default.Computer else Icons.Default.DirectionsCar,
                contentDescription = stringResource(id = kind.labelResId),
                tint = MaterialTheme.colorScheme.primary
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = (if (showDate) BusinessTime.formatDate(appointment.startsAt) + " " else "") +
                        "${BusinessTime.formatTime(appointment.startsAt)} – ${BusinessTime.formatTime(appointment.endsAt)}",
                    style = MaterialTheme.typography.titleSmall
                )
                Text(text = item.clientName, style = MaterialTheme.typography.bodyMedium)
                val details = listOfNotNull(
                    item.ticketTitle?.let { ticketLabel(item.ticketNumber, it) },
                    appointment.address
                )
                if (details.isNotEmpty()) {
                    Text(
                        text = details.joinToString(" · "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }
            if (status != AppointmentStatus.SCHEDULED) {
                Badge(
                    stringResource(id = status.labelResId),
                    if (status == AppointmentStatus.DONE) statusColor(TicketStatus.RESOLVED) else MaterialTheme.colorScheme.outline
                )
            }
        }
    }
}

// endregion

// region Hours

@Composable
fun HoursTab(onOpen: (String) -> Unit, viewModel: TimeViewModel = hiltViewModel()) {
    val running by viewModel.running.collectAsStateWithLifecycle()
    val days by viewModel.days.collectAsStateWithLifecycle()
    val totals by viewModel.totals.collectAsStateWithLifecycle()
    var choosingClient by rememberSaveable { mutableStateOf(false) }

    LazyColumn(contentPadding = LIST_PADDING, verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxSize()) {
        item {
            TimerCard(
                running = running,
                onStart = { choosingClient = true },
                onStop = { running?.let { viewModel.stop(it.entry.id) } }
            )
        }
        item {
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                TotalTile(stringResource(id = R.string.hours_today), totals.todayMillis)
                TotalTile(stringResource(id = R.string.hours_week), totals.weekMillis)
                TotalTile(stringResource(id = R.string.hours_month), totals.monthMillis)
            }
        }
        if (days.isEmpty()) {
            item { EmptyMessage(stringResource(id = R.string.hours_empty)) }
        }
        days.forEach { (date, entries) ->
            item(key = "day-$date") {
                Row(modifier = Modifier.padding(top = 8.dp)) {
                    Text(
                        text = dayLabel(date),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.weight(1f)
                    )
                    Text(
                        text = BusinessTime.formatDuration(entries.sumOf { it.entry.durationMillis() }),
                        style = MaterialTheme.typography.titleSmall
                    )
                }
            }
            items(entries, key = { it.entry.id }) { item ->
                TimeEntryRow(item = item, onClick = { onOpen(item.entry.id) })
                HorizontalDivider()
            }
        }
    }

    if (choosingClient) {
        StartTimerDialog(
            viewModel = viewModel,
            onDismiss = { choosingClient = false },
            onStart = { clientId, ticketId ->
                viewModel.start(clientId, ticketId)
                choosingClient = false
            }
        )
    }
}

@Composable
private fun TotalTile(label: String, millis: Long) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(text = label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(text = BusinessTime.formatDuration(millis), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    }
}

/** Shows the running stopwatch (updated every second) or a button to start one. */
@Composable
internal fun TimerCard(
    running: TimeEntryWithClient?,
    onStart: (() -> Unit)?,
    onStop: () -> Unit,
    startLabel: String = stringResource(id = R.string.timer_start)
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (running != null) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (running != null) {
                var now by remember { mutableLongStateOf(System.currentTimeMillis()) }
                LaunchedEffect(running.entry.id) {
                    while (true) {
                        now = System.currentTimeMillis()
                        delay(1_000)
                    }
                }
                Text(
                    text = BusinessTime.formatClock(running.entry.durationMillis(now)),
                    style = MaterialTheme.typography.displaySmall,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = listOfNotNull(running.clientName, running.ticketTitle?.let { ticketLabel(running.ticketNumber, it) })
                        .joinToString(" · "),
                    style = MaterialTheme.typography.bodyMedium
                )
                Button(onClick = onStop, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.timer_stop)) }
            } else if (onStart != null) {
                Text(text = stringResource(id = R.string.timer_idle), style = MaterialTheme.typography.bodyMedium)
                OutlinedButton(onClick = onStart, modifier = Modifier.fillMaxWidth()) { Text(startLabel) }
            }
        }
    }
}

@Composable
internal fun TimeEntryRow(item: TimeEntryWithClient, onClick: () -> Unit, showClient: Boolean = true) {
    val entry = item.entry
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = "${BusinessTime.formatTime(entry.startedAt)} – " +
                    (entry.endedAt?.let(BusinessTime::formatTime) ?: stringResource(id = R.string.timer_running)),
                style = MaterialTheme.typography.bodyLarge
            )
            val details = listOfNotNull(
                item.clientName.takeIf { showClient },
                item.ticketTitle?.let { ticketLabel(item.ticketNumber, it) },
                entry.note
            )
            if (details.isNotEmpty()) {
                Text(
                    text = details.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
        Text(text = BusinessTime.formatDuration(entry.durationMillis()), style = MaterialTheme.typography.titleSmall)
    }
}

/** Choose the client (and optionally an open ticket) to start the stopwatch. */
@Composable
private fun StartTimerDialog(
    viewModel: TimeViewModel,
    onDismiss: () -> Unit,
    onStart: (clientId: String, ticketId: String?) -> Unit
) {
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    var clientId by rememberSaveable { mutableStateOf<String?>(null) }
    var ticketId by rememberSaveable { mutableStateOf<String?>(null) }
    val tickets by remember(clientId) { viewModel.openTicketsOf(clientId) }.collectAsStateWithLifecycle(emptyList())

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.timer_start)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (clients.isEmpty()) {
                    Text(stringResource(id = R.string.timer_needs_client))
                } else {
                    ClientPickerField(clients = clients, selectedId = clientId, onSelect = {
                        clientId = it.id
                        ticketId = null
                    })
                    if (tickets.isNotEmpty()) {
                        OptionField(
                            label = stringResource(id = R.string.field_ticket_optional),
                            options = listOf<TicketWithClient?>(null) + tickets,
                            selected = tickets.firstOrNull { it.ticket.id == ticketId },
                            optionLabel = { it?.let { item -> ticketLabel(item.ticket.number, item.ticket.title) } ?: stringResource(id = R.string.no_ticket) },
                            onSelect = { ticketId = it?.ticket?.id }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { clientId?.let { onStart(it, ticketId) } }, enabled = clientId != null) {
                Text(stringResource(id = R.string.timer_start_short))
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.cancel)) } }
    )
}

// endregion
