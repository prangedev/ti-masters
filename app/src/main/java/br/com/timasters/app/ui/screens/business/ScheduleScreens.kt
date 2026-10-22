package br.com.timasters.app.ui.screens.business

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.data.business.AppointmentKind
import br.com.timasters.app.data.business.AppointmentStatus
import br.com.timasters.app.data.business.ReminderOption
import br.com.timasters.app.data.business.TicketWithClient
import br.com.timasters.app.ui.components.AppScreenScaffold
import br.com.timasters.app.ui.components.BackIcon
import br.com.timasters.app.ui.viewmodel.AppointmentEditViewModel
import br.com.timasters.app.ui.viewmodel.TimeEntryEditViewModel
import java.time.Duration
import java.time.LocalTime

@Composable
fun AppointmentEditScreen(
    appointmentId: String?,
    presetClientId: String?,
    presetTicketId: String?,
    session: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: AppointmentEditViewModel = hiltViewModel(key = "appointment-edit")
) {
    LaunchedEffect(session) { viewModel.load(appointmentId, presetClientId, presetTicketId, session) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val tickets by viewModel.clientTickets.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val context = LocalContext.current
    // Reminders are notifications: Android 13+ asks the user once. Saving does not wait for the answer.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun save() {
        val needsPermission = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            form.reminder != ReminderOption.NONE &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        if (needsPermission && form.isValid) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        viewModel.save(onSaved = onBack)
    }

    AppScreenScaffold(
        title = stringResource(id = if (appointmentId == null) R.string.appointment_new else R.string.appointment_edit),
        navigationIcon = { BackIcon(onBack) },
        actions = {
            if (appointmentId != null) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.delete))
                }
            }
            TextButton(onClick = ::save) { Text(stringResource(id = R.string.save)) }
        },
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
            if (form.isExisting && form.status != AppointmentStatus.SCHEDULED) {
                Text(
                    text = stringResource(id = R.string.appointment_status_label, stringResource(id = form.status.labelResId)),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary
                )
            }
            ClientPickerField(
                clients = clients,
                selectedId = form.clientId,
                onSelect = viewModel::selectClient,
                isError = form.showErrors && form.clientError
            )
            TicketOptionField(tickets, form.ticketId) { id -> viewModel.update { it.copy(ticketId = id) } }
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                AppointmentKind.entries.forEachIndexed { index, kind ->
                    SegmentedButton(
                        selected = form.kind == kind,
                        onClick = { viewModel.update { it.copy(kind = kind) } },
                        shape = SegmentedButtonDefaults.itemShape(index, AppointmentKind.entries.size)
                    ) { Text(stringResource(id = kind.labelResId)) }
                }
            }
            DateField(
                label = stringResource(id = R.string.field_date),
                date = form.date,
                onDateChange = { date -> if (date != null) viewModel.update { it.copy(date = date) } }
            )
            TimeRangeFields(
                start = form.start,
                end = form.end,
                showError = form.showErrors && form.timeError,
                onStartChange = { time ->
                    // Moving the start keeps the same duration.
                    viewModel.update {
                        val duration = Duration.between(it.start, it.end)
                        it.copy(start = time, end = time.plus(duration).takeIf { end -> end.isAfter(time) } ?: it.end)
                    }
                },
                onEndChange = { time -> viewModel.update { it.copy(end = time) } }
            )
            if (form.kind == AppointmentKind.ON_SITE) {
                FormTextField(
                    value = form.address,
                    label = stringResource(id = R.string.field_address),
                    onValueChange = { v -> viewModel.update { it.copy(address = v.take(300)) } },
                    capitalization = KeyboardCapitalization.Words
                )
            }
            FormTextField(
                value = form.notes,
                label = stringResource(id = R.string.field_notes),
                onValueChange = { v -> viewModel.update { it.copy(notes = v.take(4000)) } },
                singleLine = false
            )
            OptionField(
                label = stringResource(id = R.string.field_reminder),
                options = ReminderOption.entries,
                selected = form.reminder,
                optionLabel = { stringResource(id = it.labelResId) },
                onSelect = { option -> viewModel.update { it.copy(reminder = option) } }
            )
            if (form.isExisting) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    if (form.status != AppointmentStatus.DONE) {
                        OutlinedButton(
                            onClick = { viewModel.setStatus(AppointmentStatus.DONE, onBack) },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(id = R.string.appointment_mark_done)) }
                    }
                    if (form.status != AppointmentStatus.CANCELED) {
                        OutlinedButton(
                            onClick = { viewModel.setStatus(AppointmentStatus.CANCELED, onBack) },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(id = R.string.appointment_cancel)) }
                    }
                    if (form.status != AppointmentStatus.SCHEDULED) {
                        OutlinedButton(
                            onClick = { viewModel.setStatus(AppointmentStatus.SCHEDULED, onBack) },
                            modifier = Modifier.weight(1f)
                        ) { Text(stringResource(id = R.string.appointment_reopen)) }
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            title = stringResource(id = R.string.appointment_delete_title),
            onConfirm = {
                confirmDelete = false
                viewModel.delete(onBack)
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
fun TimeEntryEditScreen(
    entryId: String?,
    session: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: TimeEntryEditViewModel = hiltViewModel(key = "time-entry-edit")
) {
    LaunchedEffect(session) { viewModel.load(entryId, session) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val tickets by viewModel.clientTickets.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    AppScreenScaffold(
        title = stringResource(id = if (entryId == null) R.string.time_entry_new else R.string.time_entry_edit),
        navigationIcon = { BackIcon(onBack) },
        actions = {
            if (entryId != null) {
                IconButton(onClick = { confirmDelete = true }) {
                    Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.delete))
                }
            }
            TextButton(onClick = { viewModel.save(onBack) }) { Text(stringResource(id = R.string.save)) }
        },
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
            ClientPickerField(
                clients = clients,
                selectedId = form.clientId,
                onSelect = { client ->
                    viewModel.update { it.copy(clientId = client.id, ticketId = if (it.clientId == client.id) it.ticketId else null) }
                },
                isError = form.showErrors && form.clientError
            )
            TicketOptionField(tickets, form.ticketId) { id -> viewModel.update { it.copy(ticketId = id) } }
            DateField(
                label = stringResource(id = R.string.field_date),
                date = form.date,
                onDateChange = { date -> if (date != null) viewModel.update { it.copy(date = date) } }
            )
            TimeRangeFields(
                start = form.start,
                end = form.end,
                showError = form.showErrors && form.timeError,
                onStartChange = { time -> viewModel.update { it.copy(start = time) } },
                onEndChange = { time -> viewModel.update { it.copy(end = time) } }
            )
            if (form.end.isAfter(form.start)) {
                Text(
                    text = stringResource(
                        id = R.string.time_entry_duration,
                        BusinessTime.formatDuration(Duration.between(form.start, form.end).toMillis())
                    ),
                    style = MaterialTheme.typography.titleSmall
                )
            }
            FormTextField(
                value = form.note,
                label = stringResource(id = R.string.field_what_was_done),
                onValueChange = { v -> viewModel.update { it.copy(note = v.take(2000)) } },
                singleLine = false
            )
        }
    }

    if (confirmDelete) {
        DeleteDialog(
            title = stringResource(id = R.string.time_entry_delete_title),
            onConfirm = {
                confirmDelete = false
                viewModel.delete(onBack)
            },
            onDismiss = { confirmDelete = false }
        )
    }
}

@Composable
private fun TicketOptionField(tickets: List<TicketWithClient>, selectedId: String?, onSelect: (String?) -> Unit) {
    if (tickets.isEmpty()) return
    OptionField(
        label = stringResource(id = R.string.field_ticket_optional),
        options = listOf<TicketWithClient?>(null) + tickets,
        selected = tickets.firstOrNull { it.ticket.id == selectedId },
        optionLabel = { it?.let { item -> ticketLabel(item.ticket.number, item.ticket.title) } ?: stringResource(id = R.string.no_ticket) },
        onSelect = { onSelect(it?.ticket?.id) }
    )
}

@Composable
private fun TimeRangeFields(
    start: LocalTime,
    end: LocalTime,
    showError: Boolean,
    onStartChange: (LocalTime) -> Unit,
    onEndChange: (LocalTime) -> Unit
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TimeField(stringResource(id = R.string.field_start), start, onStartChange, Modifier.weight(1f))
        TimeField(stringResource(id = R.string.field_end), end, onEndChange, Modifier.weight(1f), isError = showError)
    }
    if (showError) {
        Text(
            text = stringResource(id = R.string.error_end_before_start),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}

@Composable
private fun DeleteDialog(title: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(stringResource(id = R.string.delete)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.cancel)) } }
    )
}
