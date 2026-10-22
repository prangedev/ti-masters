package br.com.timasters.app.ui.screens.business

import android.graphics.BitmapFactory
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Inventory2
import androidx.compose.material.icons.filled.Warehouse
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.MoneyInput
import br.com.timasters.app.data.business.ItemKind
import br.com.timasters.app.ui.components.AppScreenScaffold
import br.com.timasters.app.ui.components.BackIcon
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.CatalogItemEditViewModel
import br.com.timasters.app.ui.viewmodel.CatalogViewModel
import br.com.timasters.app.ui.viewmodel.CompanySettingsState
import br.com.timasters.app.ui.viewmodel.CompanySettingsViewModel

/** Brand colors offered for quotes, service orders and the app; null = TI Masters blue. */
private val BRAND_COLORS = listOf(
    "#1565C0", "#0277BD", "#00838F", "#2E7D32", "#558B2F", "#F9A825",
    "#EF6C00", "#D84315", "#C62828", "#AD1457", "#6A1B9A", "#4527A0",
    "#37474F", "#212121"
)

// region Company settings

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CompanySettingsScreen(
    session: String,
    onOpenCatalog: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CompanySettingsViewModel = hiltViewModel(key = "company-settings")
) {
    LaunchedEffect(session) { viewModel.load(session) }
    val state by viewModel.state.collectAsStateWithLifecycle()
    val form by viewModel.form.collectAsStateWithLifecycle()
    val logo by viewModel.logo.collectAsStateWithLifecycle()
    val logoError by viewModel.logoError.collectAsStateWithLifecycle()
    val pickLogo = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let(viewModel::pickLogo)
    }

    AppScreenScaffold(
        title = stringResource(id = R.string.company_settings_title),
        navigationIcon = { BackIcon(onBack) },
        actions = {
            if (state == CompanySettingsState.Ready) {
                TextButton(onClick = { viewModel.save(onBack) }) { Text(stringResource(id = R.string.save)) }
            }
        },
        modifier = modifier
    ) { innerPadding ->
        when (state) {
            CompanySettingsState.Loading -> Box(
                Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
                contentAlignment = Alignment.Center
            ) { CircularProgressIndicator() }
            CompanySettingsState.NotSynced -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(stringResource(id = R.string.company_settings_not_synced))
                Button(onClick = {
                    viewModel.retry()
                    viewModel.load(session + "-retry-" + System.currentTimeMillis())
                }) { Text(stringResource(id = R.string.retry)) }
            }
            CompanySettingsState.Ready -> Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding)
                    .verticalScroll(rememberScrollState())
                    .imePadding()
                    .padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                SectionCard(title = stringResource(id = R.string.company_brand)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        LogoPreview(path = logo?.first, version = logo?.second ?: 0)
                        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            OutlinedButton(onClick = {
                                pickLogo.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
                            }) { Text(stringResource(id = if (logo == null) R.string.company_logo_choose else R.string.company_logo_change)) }
                            if (logo != null) {
                                TextButton(onClick = viewModel::removeLogo) { Text(stringResource(id = R.string.company_logo_remove)) }
                            }
                        }
                    }
                    if (logoError) MessageCard(text = stringResource(id = R.string.company_logo_error), isError = true)
                    Text(stringResource(id = R.string.company_color), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        ColorSwatch(
                            hex = null,
                            selected = form.primaryColor == null,
                            label = stringResource(id = R.string.company_color_default),
                            onClick = { viewModel.update { it.copy(primaryColor = null) } }
                        )
                        BRAND_COLORS.forEach { hex ->
                            ColorSwatch(
                                hex = hex,
                                selected = form.primaryColor.equals(hex, ignoreCase = true),
                                label = hex,
                                onClick = { viewModel.update { it.copy(primaryColor = hex) } }
                            )
                        }
                    }
                    Text(
                        text = stringResource(id = R.string.company_brand_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SectionCard(title = stringResource(id = R.string.company_contacts)) {
                    FormTextField(
                        value = form.name, label = stringResource(id = R.string.field_company_name),
                        onValueChange = { v -> viewModel.update { it.copy(name = v.take(120)) } },
                        error = stringResource(id = R.string.error_name_required).takeIf { form.showErrors && form.nameError },
                        capitalization = KeyboardCapitalization.Words
                    )
                    FormTextField(
                        value = form.document, label = stringResource(id = R.string.field_document),
                        onValueChange = { v -> viewModel.update { it.copy(document = v.take(18)) } },
                        error = stringResource(id = R.string.error_document_invalid).takeIf { form.showErrors && form.documentError },
                        capitalization = KeyboardCapitalization.Characters
                    )
                    FormTextField(
                        value = form.phone, label = stringResource(id = R.string.field_phone),
                        onValueChange = { v -> viewModel.update { it.copy(phone = v.take(20)) } },
                        error = stringResource(id = R.string.error_phone_invalid).takeIf { form.showErrors && form.phoneError },
                        keyboardType = KeyboardType.Phone
                    )
                    FormTextField(
                        value = form.email, label = stringResource(id = R.string.field_email),
                        onValueChange = { v -> viewModel.update { it.copy(email = v.trim().take(254)) } },
                        error = stringResource(id = R.string.auth_error_email_invalid).takeIf { form.showErrors && form.emailError },
                        keyboardType = KeyboardType.Email
                    )
                    FormTextField(
                        value = form.address, label = stringResource(id = R.string.field_address),
                        onValueChange = { v -> viewModel.update { it.copy(address = v.take(300)) } },
                        capitalization = KeyboardCapitalization.Words
                    )
                    FormTextField(
                        value = form.city, label = stringResource(id = R.string.field_city),
                        onValueChange = { v -> viewModel.update { it.copy(city = v.take(120)) } },
                        capitalization = KeyboardCapitalization.Words
                    )
                }

                SectionCard(title = stringResource(id = R.string.company_prices)) {
                    MoneyField(
                        value = form.hourlyRate, label = stringResource(id = R.string.field_hourly_rate),
                        onValueChange = { v -> viewModel.update { it.copy(hourlyRate = v.take(12)) } },
                        error = stringResource(id = R.string.error_money_invalid).takeIf { form.showErrors && form.hourlyRateError }
                    )
                    MoneyField(
                        value = form.minimumCharge, label = stringResource(id = R.string.field_minimum_charge),
                        onValueChange = { v -> viewModel.update { it.copy(minimumCharge = v.take(12)) } },
                        error = stringResource(id = R.string.error_money_invalid).takeIf { form.showErrors && form.minimumChargeError }
                    )
                    MoneyField(
                        value = form.travelFee, label = stringResource(id = R.string.field_travel_fee),
                        onValueChange = { v -> viewModel.update { it.copy(travelFee = v.take(12)) } },
                        error = stringResource(id = R.string.error_money_invalid).takeIf { form.showErrors && form.travelFeeError }
                    )
                    MoneyField(
                        value = form.urgencyPercent, label = stringResource(id = R.string.field_urgency_percent),
                        onValueChange = { v -> viewModel.update { it.copy(urgencyPercent = v.filter(Char::isDigit).take(3)) } },
                        error = stringResource(id = R.string.error_percent_invalid).takeIf { form.showErrors && form.urgencyError },
                        keyboardType = KeyboardType.Number
                    )
                    MoneyField(
                        value = form.quoteValidityDays, label = stringResource(id = R.string.field_quote_validity_days),
                        onValueChange = { v -> viewModel.update { it.copy(quoteValidityDays = v.filter(Char::isDigit).take(3)) } },
                        error = stringResource(id = R.string.error_validity_invalid).takeIf { form.showErrors && form.validityError },
                        keyboardType = KeyboardType.Number
                    )
                    Text(
                        text = stringResource(id = R.string.company_prices_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                SectionCard(title = stringResource(id = R.string.company_documents)) {
                    FormTextField(
                        value = form.paymentTerms, label = stringResource(id = R.string.field_payment_terms),
                        onValueChange = { v -> viewModel.update { it.copy(paymentTerms = v.take(500)) } },
                        singleLine = false
                    )
                    FormTextField(
                        value = form.footerNotes, label = stringResource(id = R.string.field_footer_notes),
                        onValueChange = { v -> viewModel.update { it.copy(footerNotes = v.take(1000)) } },
                        singleLine = false
                    )
                }

                OutlinedButton(onClick = onOpenCatalog, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Default.Inventory2, contentDescription = null)
                    Text(stringResource(id = R.string.catalog_manage), modifier = Modifier.padding(start = 8.dp))
                }
            }
        }
    }
}

@Composable
private fun LogoPreview(path: String?, version: Int) {
    val bitmap = remember(path, version) { path?.let { BitmapFactory.decodeFile(it) } }
    Box(
        modifier = Modifier
            .size(72.dp)
            .clip(RoundedCornerShape(8.dp))
            .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant),
        contentAlignment = Alignment.Center
    ) {
        if (bitmap != null) {
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = stringResource(id = R.string.company_logo),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(4.dp)
            )
        } else {
            Text(
                text = stringResource(id = R.string.company_logo_none),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

@Composable
private fun ColorSwatch(hex: String?, selected: Boolean, label: String, onClick: () -> Unit) {
    val color = hex?.let { Color(android.graphics.Color.parseColor(it)) } ?: MaterialTheme.colorScheme.primary
    Box(
        modifier = Modifier
            .size(40.dp)
            .clip(CircleShape)
            .background(color)
            .border(
                width = if (selected) 3.dp else 1.dp,
                color = if (selected) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape
            )
            .clickable(role = Role.RadioButton, onClick = onClick)
            .semantics {
                contentDescription = label
                this.selected = selected
            },
        contentAlignment = Alignment.Center
    ) {
        if (selected) Icon(Icons.Default.Check, contentDescription = null, tint = Color.White)
        if (hex == null && !selected) Text("TI", color = Color.White, style = MaterialTheme.typography.labelSmall)
    }
}

// endregion

// region Catalog

@Composable
fun CatalogScreen(
    onOpenItem: (String?) -> Unit,
    onOpenStock: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogViewModel = hiltViewModel()
) {
    val items by viewModel.items.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    AppScreenScaffold(
        title = stringResource(id = R.string.catalog_title),
        navigationIcon = { BackIcon(onBack) },
        actions = {
            IconButton(onClick = onOpenStock) {
                Icon(Icons.Default.Warehouse, contentDescription = stringResource(id = R.string.stock_title))
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onOpenItem(null) },
                icon = { Icon(Icons.Default.Inventory2, contentDescription = null) },
                text = { Text(stringResource(id = R.string.catalog_new_item)) }
            )
        },
        modifier = modifier
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
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
            if (items.isEmpty()) item { EmptyMessage(stringResource(id = R.string.catalog_empty_hint)) }
            items(items, key = { it.id }) { entry ->
                Card(
                    onClick = { onOpenItem(entry.id) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(entry.name, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(
                                text = stringResource(id = ItemKind.from(entry.kind).labelResId) +
                                    (if (entry.trackStock) " · " + stringResource(id = R.string.catalog_stock_controlled) else "") +
                                    if (entry.active) "" else " · " + stringResource(id = R.string.catalog_inactive),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Text(
                            text = "${MoneyInput.formatCents(entry.priceCents)} / ${entry.unit}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun CatalogItemEditScreen(
    itemId: String?,
    session: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
    viewModel: CatalogItemEditViewModel = hiltViewModel(key = "catalog-item-edit")
) {
    LaunchedEffect(session) { viewModel.load(itemId, session) }
    val form by viewModel.form.collectAsStateWithLifecycle()
    var confirmDelete by rememberSaveable { mutableStateOf(false) }

    AppScreenScaffold(
        title = stringResource(id = if (itemId == null) R.string.catalog_new_item else R.string.catalog_edit_item),
        navigationIcon = { BackIcon(onBack) },
        actions = {
            if (form.isExisting) {
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
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                ItemKind.entries.forEachIndexed { index, kind ->
                    SegmentedButton(
                        selected = form.kind == kind,
                        onClick = { viewModel.update { it.copy(kind = kind) } },
                        shape = SegmentedButtonDefaults.itemShape(index, ItemKind.entries.size),
                        icon = {}
                    ) { Text(stringResource(id = kind.labelResId)) }
                }
            }
            FormTextField(
                value = form.name, label = stringResource(id = R.string.field_name),
                onValueChange = { v -> viewModel.update { it.copy(name = v.take(160)) } },
                error = stringResource(id = R.string.error_name_required).takeIf { form.showErrors && form.nameError }
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoneyField(
                    value = form.price, label = stringResource(id = R.string.field_price),
                    onValueChange = { v -> viewModel.update { it.copy(price = v.take(12)) } },
                    error = stringResource(id = R.string.error_money_invalid).takeIf { form.showErrors && form.priceError },
                    modifier = Modifier.weight(2f)
                )
                OutlinedTextField(
                    value = form.unit,
                    onValueChange = { v -> viewModel.update { it.copy(unit = v.take(10)) } },
                    label = { Text(stringResource(id = R.string.field_unit)) },
                    supportingText = { Text(stringResource(id = R.string.field_unit_hint)) },
                    singleLine = true,
                    modifier = Modifier.weight(1f)
                )
            }
            FormTextField(
                value = form.description, label = stringResource(id = R.string.field_item_description),
                onValueChange = { v -> viewModel.update { it.copy(description = v.take(1000)) } },
                singleLine = false
            )
            if (form.kind == ItemKind.PRODUCT) {
                MoneyField(
                    value = form.cost,
                    label = stringResource(id = R.string.field_cost_optional),
                    onValueChange = { v -> viewModel.update { it.copy(cost = v.take(12)) } },
                    error = stringResource(id = R.string.error_money_invalid).takeIf { form.showErrors && form.costError }
                )
                Text(
                    text = stringResource(id = R.string.field_cost_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                CheckRow(
                    checked = form.trackStock,
                    label = stringResource(id = R.string.catalog_track_stock),
                    onCheckedChange = { checked -> viewModel.update { it.copy(trackStock = checked) } }
                )
                if (form.trackStock) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (!form.isExisting) {
                            MoneyField(
                                value = form.initialQuantity,
                                label = stringResource(id = R.string.stock_initial_quantity),
                                onValueChange = { v -> viewModel.update { it.copy(initialQuantity = v.take(10)) } },
                                error = stringResource(id = R.string.error_quantity_invalid).takeIf { form.showErrors && form.initialQuantityError },
                                modifier = Modifier.weight(1f)
                            )
                        }
                        MoneyField(
                            value = form.minStock,
                            label = stringResource(id = R.string.stock_minimum_label),
                            onValueChange = { v -> viewModel.update { it.copy(minStock = v.take(10)) } },
                            error = stringResource(id = R.string.error_quantity_invalid).takeIf { form.showErrors && form.minStockError },
                            modifier = Modifier.weight(1f)
                        )
                    }
                    Text(
                        text = stringResource(id = R.string.catalog_track_stock_hint),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            CheckRow(
                checked = form.active,
                label = stringResource(id = R.string.catalog_active),
                onCheckedChange = { checked -> viewModel.update { it.copy(active = checked) } }
            )
        }
    }

    if (confirmDelete) {
        AlertDialog(
            onDismissRequest = { confirmDelete = false },
            title = { Text(stringResource(id = R.string.catalog_delete_title)) },
            confirmButton = {
                TextButton(onClick = {
                    confirmDelete = false
                    viewModel.delete(onBack)
                }) { Text(stringResource(id = R.string.delete)) }
            },
            dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(stringResource(id = R.string.cancel)) } }
        )
    }
}

// endregion
