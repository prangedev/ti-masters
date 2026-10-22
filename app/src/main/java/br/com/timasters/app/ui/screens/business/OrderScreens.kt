package br.com.timasters.app.ui.screens.business

import android.content.ActivityNotFoundException
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Chat
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.PictureAsPdf
import androidx.compose.material.icons.filled.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.MoneyInput
import br.com.timasters.app.data.business.OrderItem
import br.com.timasters.app.data.business.OrderKind
import br.com.timasters.app.data.business.OrderStatus
import br.com.timasters.app.data.business.ServiceItemEntity
import br.com.timasters.app.data.business.ServiceOrderEntity
import br.com.timasters.app.data.business.ServiceOrderWithClient
import br.com.timasters.app.ui.components.AppScreenScaffold
import br.com.timasters.app.ui.components.BackIcon
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.OrderDetailViewModel
import br.com.timasters.app.ui.viewmodel.OrderEditViewModel
import br.com.timasters.app.ui.viewmodel.OrderEvent
import br.com.timasters.app.ui.viewmodel.OrderFilter
import br.com.timasters.app.ui.viewmodel.OrderForm
import br.com.timasters.app.ui.viewmodel.OrdersViewModel
import br.com.timasters.app.ui.viewmodel.SendChannel

// region Shared pieces

/** "Orçamento nº 12", or "Orçamento" + hint while the server has not numbered it yet. */
@Composable
internal fun orderTitle(order: ServiceOrderEntity): String {
    val kind = stringResource(id = order.orderKind.labelResId)
    return order.number?.let { stringResource(id = R.string.order_name_with_number, kind, it) }
        ?: stringResource(id = R.string.order_number_pending, kind)
}

@Composable
internal fun orderStatusColor(status: OrderStatus) = when (status) {
    OrderStatus.DRAFT, OrderStatus.OPEN -> MaterialTheme.colorScheme.primary
    OrderStatus.SENT, OrderStatus.IN_PROGRESS -> MaterialTheme.colorScheme.tertiary
    OrderStatus.APPROVED, OrderStatus.DONE -> androidx.compose.ui.graphics.Color(0xFF2E7D32)
    OrderStatus.REJECTED, OrderStatus.CANCELED -> MaterialTheme.colorScheme.outline
}

@Composable
internal fun OrderCard(item: ServiceOrderWithClient, onClick: () -> Unit, showClient: Boolean = true) {
    val order = item.order
    val status = OrderStatus.from(order.status)
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = orderTitle(order),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.weight(1f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = MoneyInput.formatCents(order.totalCents),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            if (showClient) {
                Text(text = item.clientName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                Badge(stringResource(id = status.labelResId), orderStatusColor(status))
                Text(
                    text = BusinessTime.formatDate(order.createdAt),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// endregion

// region List tab

@Composable
fun OrdersTab(onOpen: (String) -> Unit, viewModel: OrdersViewModel = hiltViewModel()) {
    val orders by viewModel.orders.collectAsStateWithLifecycle()
    val filter by viewModel.filter.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            OutlinedTextField(
                value = query,
                onValueChange = viewModel::onQueryChange,
                label = { Text(stringResource(id = R.string.orders_search)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OrderFilter.entries.forEach { option ->
                    FilterChip(
                        selected = filter == option,
                        onClick = { viewModel.onFilterChange(option) },
                        label = {
                            Text(
                                stringResource(
                                    id = when (option) {
                                        OrderFilter.QUOTES -> R.string.orders_filter_quotes
                                        OrderFilter.SERVICE_ORDERS -> R.string.orders_filter_service_orders
                                        OrderFilter.ALL -> R.string.tickets_filter_all
                                    }
                                )
                            )
                        }
                    )
                }
            }
        }
        if (orders.isEmpty()) item { EmptyMessage(stringResource(id = R.string.orders_empty)) }
        items(orders, key = { it.order.id }) { item -> OrderCard(item = item, onClick = { onOpen(item.order.id) }) }
    }
}

// endregion

// region Detail

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun OrderDetailScreen(
    orderId: String,
    navigate: (BusinessRoute) -> Unit,
    onOpenOther: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrderDetailViewModel = hiltViewModel(key = "order-$orderId")
) {
    LaunchedEffect(orderId) { viewModel.load(orderId) }
    val item by viewModel.order.collectAsStateWithLifecycle()
    val busy by viewModel.busy.collectAsStateWithLifecycle()
    val photos by viewModel.photos.collectAsStateWithLifecycle()
    val signature by viewModel.signature.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }
    var message by rememberSaveable { mutableStateOf<Int?>(null) }
    var photoMessage by rememberSaveable { mutableStateOf<Int?>(null) }
    val context = LocalContext.current

    LaunchedEffect(viewModel) {
        viewModel.events.collect { event ->
            when (event) {
                is OrderEvent.Launch -> try {
                    context.startActivity(event.intent)
                } catch (_: ActivityNotFoundException) {
                    message = R.string.order_no_app
                }
                is OrderEvent.OpenOrder -> onOpenOther(event.id)
                OrderEvent.NoWhatsApp -> message = R.string.order_no_whatsapp
                OrderEvent.NoPhone -> message = R.string.order_no_phone
                OrderEvent.PdfFailed -> message = R.string.order_pdf_failed
                OrderEvent.PhotoInvalid -> photoMessage = R.string.photos_invalid
                OrderEvent.PhotoLimit -> photoMessage = R.string.photos_limit
            }
        }
    }

    val current = item
    AppScreenScaffold(
        title = current?.let { orderTitle(it.order) }.orEmpty(),
        navigationIcon = { BackIcon(onBack) },
        actions = {
            IconButton(onClick = { navigate(BusinessRoute.OrderEdit(orderId)) }) {
                Icon(Icons.Default.Edit, contentDescription = stringResource(id = R.string.edit))
            }
            IconButton(onClick = { confirmDelete = true }) {
                Icon(Icons.Default.Delete, contentDescription = stringResource(id = R.string.delete))
            }
        },
        modifier = modifier
    ) { innerPadding ->
        if (current == null) return@AppScreenScaffold
        val order = current.order
        val status = OrderStatus.from(order.status)
        val chooserTitle = stringResource(id = R.string.order_share_title)
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                SectionCard(title = current.clientName) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Badge(stringResource(id = order.orderKind.labelResId), MaterialTheme.colorScheme.secondary)
                        Badge(stringResource(id = status.labelResId), orderStatusColor(status))
                    }
                    TextButton(onClick = { navigate(BusinessRoute.ClientDetail(order.clientId)) }) {
                        Text(stringResource(id = R.string.ticket_client_link, current.clientName))
                    }
                    if (order.ticketId != null && current.ticketTitle != null) {
                        TextButton(onClick = { navigate(BusinessRoute.TicketDetail(order.ticketId)) }) {
                            Text(stringResource(id = R.string.order_ticket_link, ticketLabel(current.ticketNumber, current.ticketTitle)))
                        }
                    }
                    InfoRow(stringResource(id = R.string.order_created_at), BusinessTime.formatDateTime(order.createdAt))
                    order.validUntil?.let { InfoRow(stringResource(id = R.string.field_valid_until), BusinessTime.formatDate(it)) }
                    order.sentAt?.let { InfoRow(stringResource(id = R.string.order_sent_at), BusinessTime.formatDateTime(it)) }
                    if (order.number == null) {
                        Text(
                            text = stringResource(id = R.string.order_number_pending_hint),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            item { OrderValuesCard(order) }

            item {
                OrderPhotosSection(
                    photos = photos,
                    newCameraFile = viewModel::newCameraFile,
                    cameraUri = viewModel::cameraUri,
                    onAdd = { stage, uri, temp -> photoMessage = null; viewModel.addPhoto(stage, uri, temp) },
                    onDelete = viewModel::deletePhoto,
                    onCameraMissing = { photoMessage = R.string.photos_no_camera }
                )
            }
            photoMessage?.let { text -> item { MessageCard(text = stringResource(id = text), isError = true) } }

            item {
                OrderSignatureSection(
                    signature = signature,
                    signedBy = order.signedBy,
                    signedAt = order.signedAt,
                    defaultSigner = current.clientName,
                    onSave = viewModel::saveSignature,
                    onRemove = viewModel::removeSignature
                )
            }

            item {
                OptionField(
                    label = stringResource(id = R.string.field_status),
                    options = OrderStatus.of(order.orderKind),
                    selected = status,
                    optionLabel = { stringResource(id = it.labelResId) },
                    onSelect = viewModel::setStatus
                )
            }

            item {
                SectionCard(title = stringResource(id = R.string.order_send_title)) {
                    if (busy) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                            Text(stringResource(id = R.string.order_generating_pdf))
                        }
                    }
                    Button(
                        onClick = {
                            message = null
                            viewModel.send(SendChannel.WHATSAPP_PDF, chooserTitle)
                        },
                        enabled = !busy,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Icon(Icons.AutoMirrored.Filled.Chat, contentDescription = null)
                        Text(stringResource(id = R.string.order_send_whatsapp_pdf), modifier = Modifier.padding(start = 8.dp))
                    }
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = { message = null; viewModel.send(SendChannel.WHATSAPP_TEXT, chooserTitle) }, enabled = !busy) {
                            Text(stringResource(id = R.string.order_send_whatsapp_text))
                        }
                        OutlinedButton(onClick = { message = null; viewModel.send(SendChannel.SHARE, chooserTitle) }, enabled = !busy) {
                            Icon(Icons.Default.Share, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(id = R.string.order_share_other), modifier = Modifier.padding(start = 6.dp))
                        }
                        OutlinedButton(onClick = { message = null; viewModel.send(SendChannel.VIEW, chooserTitle) }, enabled = !busy) {
                            Icon(Icons.Default.PictureAsPdf, contentDescription = null, modifier = Modifier.size(18.dp))
                            Text(stringResource(id = R.string.order_view_pdf), modifier = Modifier.padding(start = 6.dp))
                        }
                    }
                    message?.let { text -> MessageCard(text = stringResource(id = text), isError = true) }
                    Text(
                        text = stringResource(
                            id = if (order.pdfPath != null && !order.pdfPendingUpload) R.string.order_cloud_copy_saved
                            else R.string.order_cloud_copy_hint
                        ),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            if (order.orderKind == OrderKind.QUOTE && status != OrderStatus.REJECTED) {
                item {
                    OutlinedButton(onClick = viewModel::convertToServiceOrder, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Default.Description, contentDescription = null)
                        Text(stringResource(id = R.string.order_convert_to_os), modifier = Modifier.padding(start = 8.dp))
                    }
                }
            }
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(id = R.string.order_delete_title)) },
            text = { Text(stringResource(id = R.string.order_delete_text)) },
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
private fun OrderValuesCard(order: ServiceOrderEntity) {
    SectionCard(title = stringResource(id = R.string.order_values)) {
        order.items.forEach { item ->
            InfoRow(
                "${MoneyInput.formatQuantity(item.quantity)} ${item.unit} × ${item.description}",
                MoneyInput.formatCents(item.totalCents)
            )
        }
        if (order.laborCents > 0) {
            InfoRow(
                stringResource(id = R.string.order_labor_line, MoneyInput.minutesToInput(order.laborMinutes), MoneyInput.formatCents(order.hourlyRateCents)),
                MoneyInput.formatCents(order.laborCents)
            )
        }
        if (order.urgencyCents > 0) {
            InfoRow(stringResource(id = R.string.order_urgency_line, order.urgencyPercent), MoneyInput.formatCents(order.urgencyCents))
        }
        if (order.travelFeeCents > 0) InfoRow(stringResource(id = R.string.pdf_travel_fee), MoneyInput.formatCents(order.travelFeeCents))
        if (order.discountCents > 0) InfoRow(stringResource(id = R.string.field_discount), "- " + MoneyInput.formatCents(order.discountCents))
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(id = R.string.order_total), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(MoneyInput.formatCents(order.totalCents), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
        order.notes?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
    }
}

// endregion

// region Form

@Composable
fun OrderEditScreen(
    route: BusinessRoute.OrderEdit,
    onSaved: (String) -> Unit,
    onOpenCatalog: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: OrderEditViewModel = hiltViewModel(key = "order-edit")
) {
    LaunchedEffect(route.session) { viewModel.load(route.id, route.kind, route.clientId, route.ticketId, route.session) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    val clients by viewModel.clients.collectAsStateWithLifecycle()
    val catalog by viewModel.catalog.collectAsStateWithLifecycle()
    val tickets by viewModel.clientTickets.collectAsStateWithLifecycle()
    val ticketMinutes by viewModel.ticketMinutes.collectAsStateWithLifecycle()
    val stock by viewModel.stock.collectAsStateWithLifecycle()
    /** -1 = new item, otherwise the index being edited. */
    var editingItem by rememberSaveable { mutableStateOf<Int?>(null) }
    var pickingFromCatalog by rememberSaveable { mutableStateOf(false) }

    val titleRes = when {
        route.id != null -> R.string.order_edit
        form.kind == OrderKind.QUOTE -> R.string.order_new_quote
        else -> R.string.order_new_service_order
    }
    AppScreenScaffold(
        title = stringResource(id = titleRes),
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
            if (!form.isExisting) {
                SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                    OrderKind.entries.forEachIndexed { index, kind ->
                        SegmentedButton(
                            selected = form.kind == kind,
                            onClick = {
                                viewModel.update {
                                    it.copy(kind = kind, status = OrderStatus.initial(kind), validUntil = if (kind == OrderKind.QUOTE) it.validUntil else null)
                                }
                            },
                            shape = SegmentedButtonDefaults.itemShape(index, OrderKind.entries.size),
                            icon = {}
                        ) { Text(stringResource(id = kind.labelResId), maxLines = 1) }
                    }
                }
            }
            if (clients.isEmpty()) Text(stringResource(id = R.string.ticket_needs_client), color = MaterialTheme.colorScheme.error)
            ClientPickerField(
                clients = clients,
                selectedId = form.clientId,
                onSelect = viewModel::selectClient,
                isError = form.showErrors && form.clientError
            )
            if (tickets.isNotEmpty()) {
                OptionField(
                    label = stringResource(id = R.string.field_ticket_optional),
                    options = listOf<String?>(null) + tickets.map { it.ticket.id },
                    selected = form.ticketId,
                    optionLabel = { id ->
                        tickets.firstOrNull { it.ticket.id == id }?.let { ticketLabel(it.ticket.number, it.ticket.title) }
                            ?: stringResource(id = R.string.no_ticket)
                    },
                    onSelect = { id -> viewModel.update { it.copy(ticketId = id) } }
                )
            }

            SectionHeader(title = stringResource(id = R.string.order_items), action = null, onAction = {})
            form.items.forEachIndexed { index, item ->
                ListItem(
                    headlineContent = { Text(item.description) },
                    supportingContent = {
                        Text("${MoneyInput.formatQuantity(item.quantity)} ${item.unit} × ${MoneyInput.formatCents(item.unitPriceCents)}")
                    },
                    trailingContent = {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(MoneyInput.formatCents(item.totalCents), fontWeight = FontWeight.Bold)
                            IconButton(onClick = { viewModel.removeItem(index) }) {
                                Icon(Icons.Default.Close, contentDescription = stringResource(id = R.string.order_item_remove))
                            }
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(onClickLabel = stringResource(id = R.string.order_item_edit)) { editingItem = index },
                    tonalElevation = 1.dp
                )
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { pickingFromCatalog = true }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Inventory2, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(id = R.string.order_add_from_catalog), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                }
                OutlinedButton(onClick = { editingItem = -1 }, modifier = Modifier.weight(1f)) {
                    Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Text(stringResource(id = R.string.order_add_custom_item), modifier = Modifier.padding(start = 6.dp), maxLines = 1)
                }
            }

            SectionHeader(title = stringResource(id = R.string.order_labor), action = null, onAction = {})
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(
                    value = form.laborHours,
                    label = stringResource(id = R.string.field_labor_hours),
                    onValueChange = { v -> viewModel.update { it.copy(laborHours = v.take(8)) } },
                    error = stringResource(id = R.string.error_hours_invalid).takeIf { form.showErrors && form.laborError },
                    keyboardType = KeyboardType.Text,
                    modifier = Modifier.weight(1f)
                )
                MoneyField(
                    value = form.hourlyRate,
                    label = stringResource(id = R.string.field_hourly_rate),
                    onValueChange = { v -> viewModel.update { it.copy(hourlyRate = v.take(12)) } },
                    error = stringResource(id = R.string.error_money_invalid).takeIf { form.showErrors && form.hourlyRateError },
                    modifier = Modifier.weight(1f)
                )
            }
            if (form.ticketId != null && ticketMinutes > 0) {
                TextButton(onClick = viewModel::useTicketHours) {
                    Text(stringResource(id = R.string.order_use_ticket_hours, BusinessTime.formatDuration(ticketMinutes * 60_000L)))
                }
            }
            if (form.minimumChargeCents > 0) {
                Text(
                    text = stringResource(id = R.string.order_minimum_hint, MoneyInput.formatCents(form.minimumChargeCents)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            CheckRow(
                checked = form.useUrgency,
                label = stringResource(id = R.string.order_apply_urgency),
                onCheckedChange = { checked -> viewModel.update { it.copy(useUrgency = checked) } }
            )
            if (form.useUrgency) {
                MoneyField(
                    value = form.urgencyPercent,
                    label = stringResource(id = R.string.field_urgency_percent),
                    onValueChange = { v -> viewModel.update { it.copy(urgencyPercent = v.filter(Char::isDigit).take(3)) } },
                    error = stringResource(id = R.string.error_percent_invalid).takeIf { form.showErrors && form.urgencyError },
                    keyboardType = KeyboardType.Number
                )
            }
            CheckRow(
                checked = form.useTravelFee,
                label = stringResource(id = R.string.order_apply_travel_fee),
                onCheckedChange = { checked -> viewModel.update { it.copy(useTravelFee = checked) } }
            )
            if (form.useTravelFee) {
                MoneyField(
                    value = form.travelFee,
                    label = stringResource(id = R.string.field_travel_fee),
                    onValueChange = { v -> viewModel.update { it.copy(travelFee = v.take(12)) } },
                    error = stringResource(id = R.string.error_money_invalid).takeIf { form.showErrors && form.travelFeeError }
                )
            }
            MoneyField(
                value = form.discount,
                label = stringResource(id = R.string.field_discount_optional),
                onValueChange = { v -> viewModel.update { it.copy(discount = v.take(12)) } },
                error = stringResource(id = R.string.error_money_invalid).takeIf { form.showErrors && form.discountError }
            )
            if (form.kind == OrderKind.QUOTE) {
                DateField(
                    label = stringResource(id = R.string.field_valid_until),
                    date = form.validUntil,
                    onDateChange = { date -> viewModel.update { it.copy(validUntil = date) } },
                    allowClear = true
                )
            }
            FormTextField(
                value = form.notes,
                label = stringResource(id = R.string.field_order_notes),
                onValueChange = { v -> viewModel.update { it.copy(notes = v.take(4000)) } },
                singleLine = false
            )
            if (form.showErrors && form.emptyError) {
                Text(stringResource(id = R.string.error_order_empty), color = MaterialTheme.colorScheme.error)
            }
            TotalPreview(form)
        }
    }

    editingItem?.let { index ->
        ItemDialog(
            initial = form.items.getOrNull(index),
            onDismiss = { editingItem = null },
            onConfirm = { item ->
                if (index >= 0) viewModel.replaceItem(index, item) else viewModel.addItem(item)
                editingItem = null
            }
        )
    }
    if (pickingFromCatalog) {
        CatalogPickerDialog(
            items = catalog,
            stock = stock,
            onPick = { picked ->
                viewModel.addItem(
                    OrderItem(
                        catalogId = picked.id,
                        description = picked.name,
                        unit = picked.unit,
                        quantity = 1.0,
                        unitPriceCents = picked.priceCents,
                        unitCostCents = picked.costCents
                    )
                )
                pickingFromCatalog = false
            },
            onOpenCatalog = {
                pickingFromCatalog = false
                onOpenCatalog()
            },
            onDismiss = { pickingFromCatalog = false }
        )
    }
}

@Composable
private fun TotalPreview(form: OrderForm) {
    val preview = form.preview
    SectionCard(title = stringResource(id = R.string.order_total_preview)) {
        if (preview.itemsCents > 0) InfoRow(stringResource(id = R.string.order_items), MoneyInput.formatCents(preview.itemsCents))
        if (preview.laborCents > 0) InfoRow(stringResource(id = R.string.order_labor), MoneyInput.formatCents(preview.laborCents))
        if (preview.urgencyCents > 0) {
            InfoRow(stringResource(id = R.string.order_urgency_line, form.urgency ?: 0), MoneyInput.formatCents(preview.urgencyCents))
        }
        if (preview.travelFeeCents > 0) InfoRow(stringResource(id = R.string.pdf_travel_fee), MoneyInput.formatCents(preview.travelFeeCents))
        if (preview.discountCents > 0) InfoRow(stringResource(id = R.string.field_discount), "- " + MoneyInput.formatCents(preview.discountCents))
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(id = R.string.order_total), style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            Text(MoneyInput.formatCents(preview.totalCents), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
internal fun CheckRow(checked: Boolean, label: String, onCheckedChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onCheckedChange)
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Text field for money, quantities, hours and percentages (number keyboard by default). */
@Composable
internal fun MoneyField(
    value: String,
    label: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    error: String? = null,
    keyboardType: KeyboardType = KeyboardType.Decimal
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
private fun ItemDialog(initial: OrderItem?, onDismiss: () -> Unit, onConfirm: (OrderItem) -> Unit) {
    var description by rememberSaveable { mutableStateOf(initial?.description.orEmpty()) }
    var quantity by rememberSaveable { mutableStateOf(initial?.quantity?.let(MoneyInput::formatQuantity) ?: "1") }
    var unit by rememberSaveable { mutableStateOf(initial?.unit ?: "un") }
    var price by rememberSaveable { mutableStateOf(initial?.unitPriceCents?.let(MoneyInput::centsToInput).orEmpty()) }
    var showErrors by rememberSaveable { mutableStateOf(false) }
    val parsedQuantity = MoneyInput.parseQuantity(quantity)?.takeIf { it > 0 }
    val parsedPrice = MoneyInput.parseCents(price)

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = if (initial == null) R.string.order_add_custom_item else R.string.order_item_edit)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it.take(300) },
                    label = { Text(stringResource(id = R.string.field_item_description)) },
                    isError = showErrors && description.isBlank(),
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    MoneyField(
                        value = quantity, label = stringResource(id = R.string.field_quantity),
                        onValueChange = { quantity = it.take(8) },
                        error = stringResource(id = R.string.error_quantity_invalid).takeIf { showErrors && parsedQuantity == null },
                        modifier = Modifier.weight(1f)
                    )
                    OutlinedTextField(
                        value = unit,
                        onValueChange = { unit = it.take(10) },
                        label = { Text(stringResource(id = R.string.field_unit)) },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                }
                MoneyField(
                    value = price, label = stringResource(id = R.string.field_unit_price),
                    onValueChange = { price = it.take(12) },
                    error = stringResource(id = R.string.error_money_invalid).takeIf { showErrors && parsedPrice == null }
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                if (description.isBlank() || parsedQuantity == null || parsedPrice == null) {
                    showErrors = true
                } else {
                    onConfirm(
                        OrderItem(
                            catalogId = initial?.catalogId,
                            description = description.trim(),
                            unit = unit.trim().ifEmpty { "un" },
                            quantity = parsedQuantity,
                            unitPriceCents = parsedPrice,
                            unitCostCents = initial?.unitCostCents ?: 0
                        )
                    )
                }
            }) { Text(stringResource(id = R.string.ok)) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.cancel)) } }
    )
}

@Composable
private fun CatalogPickerDialog(
    items: List<ServiceItemEntity>,
    stock: Map<String, Double>,
    onPick: (ServiceItemEntity) -> Unit,
    onOpenCatalog: () -> Unit,
    onDismiss: () -> Unit
) {
    var query by remember { mutableStateOf("") }
    val filtered = items.filter { query.isBlank() || it.name.contains(query.trim(), ignoreCase = true) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(id = R.string.order_add_from_catalog)) },
        text = {
            Column {
                if (items.isEmpty()) {
                    Text(stringResource(id = R.string.catalog_empty_hint))
                } else {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        label = { Text(stringResource(id = R.string.catalog_search)) },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    LazyColumn(modifier = Modifier.padding(top = 8.dp)) {
                        items(filtered, key = { it.id }) { entry ->
                            ListItem(
                                headlineContent = { Text(entry.name) },
                                supportingContent = {
                                    val price = "${MoneyInput.formatCents(entry.priceCents)} / ${entry.unit}"
                                    val inStock = stock[entry.id]
                                    Text(
                                        if (inStock == null) price
                                        else price + " · " + stringResource(id = R.string.stock_in_stock, MoneyInput.formatQuantity(inStock), entry.unit)
                                    )
                                },
                                modifier = Modifier.fillMaxWidth(),
                                trailingContent = {
                                    TextButton(onClick = { onPick(entry) }) { Text(stringResource(id = R.string.add)) }
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpenCatalog) { Text(stringResource(id = R.string.catalog_manage)) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(id = R.string.cancel)) } }
    )
}

// endregion
