package br.com.timasters.app.ui.screens.business

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Call
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Map
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.core.validation.DocumentValidator
import br.com.timasters.app.data.business.ClientEntity
import br.com.timasters.app.ui.components.AppScreenScaffold
import br.com.timasters.app.ui.components.BackIcon
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.ClientDetailViewModel
import br.com.timasters.app.ui.viewmodel.ClientEditViewModel
import br.com.timasters.app.ui.viewmodel.ClientForm

@Composable
fun ClientDetailScreen(
    clientId: String,
    navigate: (BusinessRoute) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ClientDetailViewModel = hiltViewModel(key = "client-$clientId")
) {
    LaunchedEffect(clientId) { viewModel.load(clientId) }
    val client by viewModel.client.collectAsStateWithLifecycle()
    val tickets by viewModel.tickets.collectAsStateWithLifecycle()
    val appointments by viewModel.appointments.collectAsStateWithLifecycle()
    val timeEntries by viewModel.timeEntries.collectAsStateWithLifecycle()
    val running by viewModel.running.collectAsStateWithLifecycle()
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    val current = client

    AppScreenScaffold(
        title = current?.name.orEmpty(),
        navigationIcon = { BackIcon(onBack) },
        actions = {
            IconButton(onClick = { navigate(BusinessRoute.ClientEdit(clientId)) }) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(id = R.string.edit))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.delete))
            }
        },
        modifier = modifier
    ) { innerPadding ->
        if (current == null) return@AppScreenScaffold
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item { ClientInfoCard(current) }

            item {
                val runningHere = running?.takeIf { it.entry.clientId == clientId }
                TimerCard(
                    running = runningHere,
                    onStart = if (running == null) viewModel::startTimer else null,
                    onStop = { runningHere?.let { viewModel.stopTimer(it.entry.id) } },
                    startLabel = stringResource(id = R.string.timer_start_for_client)
                )
            }

            item {
                SectionHeader(
                    title = stringResource(id = R.string.business_tab_tickets),
                    action = stringResource(id = R.string.ticket_new),
                    onAction = { navigate(BusinessRoute.TicketEdit(null, clientId)) }
                )
            }
            if (tickets.isEmpty()) item { EmptyMessage(stringResource(id = R.string.tickets_empty)) }
            items(tickets, key = { "t-${it.ticket.id}" }) { item ->
                TicketCard(item = item, onClick = { navigate(BusinessRoute.TicketDetail(item.ticket.id)) }, showClient = false)
            }

            item {
                SectionHeader(
                    title = stringResource(id = R.string.business_tab_agenda),
                    action = stringResource(id = R.string.appointment_new),
                    onAction = { navigate(BusinessRoute.AppointmentEdit(null, clientId)) }
                )
            }
            if (appointments.isEmpty()) item { EmptyMessage(stringResource(id = R.string.agenda_empty_client)) }
            items(appointments.take(RECENT_LIMIT), key = { "a-${it.appointment.id}" }) { item ->
                AppointmentCard(item = item, onClick = { navigate(BusinessRoute.AppointmentEdit(item.appointment.id)) }, showDate = true)
            }

            item {
                SectionHeader(
                    title = stringResource(id = R.string.business_tab_orders),
                    action = stringResource(id = R.string.order_new_quote),
                    onAction = { navigate(BusinessRoute.OrderEdit(null, clientId = clientId)) }
                )
            }
            if (orders.isEmpty()) item { EmptyMessage(stringResource(id = R.string.orders_empty_client)) }
            items(orders.take(RECENT_LIMIT), key = { "o-${it.order.id}" }) { item ->
                OrderCard(item = item, onClick = { navigate(BusinessRoute.OrderDetail(item.order.id)) }, showClient = false)
            }

            item {
                SectionHeader(
                    title = stringResource(
                        id = R.string.client_hours_total,
                        BusinessTime.formatDuration(timeEntries.sumOf { it.entry.durationMillis() })
                    ),
                    action = null,
                    onAction = {}
                )
            }
            items(timeEntries.take(RECENT_LIMIT), key = { "h-${it.entry.id}" }) { item ->
                TimeEntryRow(item = item, onClick = { navigate(BusinessRoute.TimeEntryEdit(item.entry.id)) }, showClient = false)
                HorizontalDivider()
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(id = R.string.client_delete_title)) },
            text = { Text(stringResource(id = R.string.client_delete_text)) },
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

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ClientInfoCard(client: ClientEntity) {
    val context = LocalContext.current
    val address = listOfNotNull(client.address, client.city).joinToString(", ")
    SectionCard(title = stringResource(id = R.string.client_details)) {
        client.docNumber?.let {
            InfoRow(stringResource(id = if (client.docType == "cnpj") R.string.field_cnpj else R.string.field_cpf), DocumentValidator.format(it))
        }
        client.phone?.let { InfoRow(stringResource(id = R.string.field_phone), BrFormatters.formatPhone(it)) }
        client.email?.let { InfoRow(stringResource(id = R.string.field_email), it) }
        if (address.isNotEmpty()) InfoRow(stringResource(id = R.string.field_address), address)
        client.notes?.let { InfoRow(stringResource(id = R.string.field_notes), it) }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            client.phone?.let { phone ->
                ContactChip(Icons.Default.Call, stringResource(id = R.string.contact_call)) {
                    context.open(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone")))
                }
                ContactChip(Icons.AutoMirrored.Filled.Chat, stringResource(id = R.string.contact_whatsapp)) {
                    context.open(Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/${whatsAppNumber(phone)}")))
                }
            }
            client.email?.let { email ->
                ContactChip(Icons.Default.Email, stringResource(id = R.string.contact_email)) {
                    context.open(Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$email")))
                }
            }
            if (address.isNotEmpty()) {
                ContactChip(Icons.Default.Map, stringResource(id = R.string.contact_map)) {
                    context.open(Intent(Intent.ACTION_VIEW, Uri.parse("geo:0,0?q=${Uri.encode(address)}")))
                }
            }
        }
    }
}

/** Brazilian numbers get the country code 55 unless they already have it. */
private fun whatsAppNumber(phone: String): String {
    val digits = phone.filter { it.isDigit() }
    return if (digits.length >= 12 && digits.startsWith("55")) digits else "55$digits"
}

private fun Context.open(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: ActivityNotFoundException) {
        // No app can handle it (e.g. no e-mail app installed); nothing to do.
    }
}

@Composable
private fun ContactChip(icon: ImageVector, label: String, onClick: () -> Unit) {
    AssistChip(onClick = onClick, label = { Text(label) }, leadingIcon = { Icon(icon, contentDescription = null) })
}

@Composable
internal fun SectionHeader(title: String, action: String?, onAction: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
fun ClientEditScreen(
    clientId: String?,
    session: String,
    onSaved: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ClientEditViewModel = hiltViewModel(key = "client-edit")
) {
    LaunchedEffect(session) { viewModel.load(clientId, session) }
    val form by viewModel.form.collectAsStateWithLifecycle()

    AppScreenScaffold(
        title = stringResource(id = if (clientId == null) R.string.client_new else R.string.client_edit),
        navigationIcon = { BackIcon(onBack) },
        actions = { TextButton(onClick = { viewModel.save(onSaved) }) { Text(stringResource(id = R.string.save)) } },
        modifier = modifier
    ) { innerPadding ->
        if (!form.isLoaded) return@AppScreenScaffold
        ClientFormFields(form = form, onChange = viewModel::update, modifier = Modifier.padding(innerPadding))
    }
}

@Composable
private fun ClientFormFields(form: ClientForm, onChange: ((ClientForm) -> ClientForm) -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .imePadding()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FormTextField(
            value = form.name, label = stringResource(id = R.string.field_name),
            onValueChange = { v -> onChange { it.copy(name = v.take(160)) } },
            error = stringResource(id = R.string.error_name_required).takeIf { form.showErrors && form.nameError },
            capitalization = KeyboardCapitalization.Words
        )
        FormTextField(
            value = form.document, label = stringResource(id = R.string.field_document),
            onValueChange = { v -> onChange { it.copy(document = v.take(18)) } },
            error = stringResource(id = R.string.error_document_invalid).takeIf { form.showErrors && form.documentError },
            capitalization = KeyboardCapitalization.Characters
        )
        FormTextField(
            value = form.phone, label = stringResource(id = R.string.field_phone),
            onValueChange = { v -> onChange { it.copy(phone = v.take(20)) } },
            error = stringResource(id = R.string.error_phone_invalid).takeIf { form.showErrors && form.phoneError },
            keyboardType = KeyboardType.Phone
        )
        FormTextField(
            value = form.email, label = stringResource(id = R.string.field_email),
            onValueChange = { v -> onChange { it.copy(email = v.trim().take(254)) } },
            error = stringResource(id = R.string.auth_error_email_invalid).takeIf { form.showErrors && form.emailError },
            keyboardType = KeyboardType.Email
        )
        FormTextField(
            value = form.address, label = stringResource(id = R.string.field_address),
            onValueChange = { v -> onChange { it.copy(address = v.take(300)) } },
            capitalization = KeyboardCapitalization.Words
        )
        FormTextField(
            value = form.city, label = stringResource(id = R.string.field_city),
            onValueChange = { v -> onChange { it.copy(city = v.take(120)) } },
            capitalization = KeyboardCapitalization.Words
        )
        FormTextField(
            value = form.notes, label = stringResource(id = R.string.field_notes),
            onValueChange = { v -> onChange { it.copy(notes = v.take(4000)) } },
            singleLine = false
        )
    }
}

@Composable
internal fun FormTextField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    error: String? = null,
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    capitalization: KeyboardCapitalization = KeyboardCapitalization.Sentences
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(
            capitalization = capitalization,
            keyboardType = keyboardType,
            imeAction = if (singleLine) ImeAction.Next else ImeAction.Default
        ),
        modifier = Modifier.fillMaxWidth()
    )
}

private const val RECENT_LIMIT = 5
