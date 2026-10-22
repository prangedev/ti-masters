package br.com.timasters.app.ui.screens.business

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CalendarMonth
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import br.com.timasters.app.R
import br.com.timasters.app.data.business.ClientEntity
import java.time.LocalDate
import java.time.LocalTime

/** Read-only text field that runs [onClick] when tapped (for pickers). */
@Composable
private fun ClickableField(
    value: String,
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    supportingText: String? = null,
    trailing: @Composable (() -> Unit)? = null
) {
    val interactionSource = remember { MutableInteractionSource() }
    LaunchedEffect(interactionSource) {
        interactionSource.interactions.collect { if (it is PressInteraction.Release) onClick() }
    }
    OutlinedTextField(
        value = value,
        onValueChange = {},
        readOnly = true,
        label = { Text(label) },
        isError = isError,
        supportingText = supportingText?.let { { Text(it) } },
        trailingIcon = trailing,
        interactionSource = interactionSource,
        singleLine = true,
        modifier = modifier.fillMaxWidth()
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DateField(
    label: String,
    date: LocalDate?,
    onDateChange: (LocalDate?) -> Unit,
    modifier: Modifier = Modifier,
    allowClear: Boolean = false
) {
    var open by rememberSaveable { mutableStateOf(false) }
    ClickableField(
        value = date?.let { BusinessTime.formatDate(BusinessTime.toMillis(it, LocalTime.NOON)) }.orEmpty(),
        label = label,
        onClick = { open = true },
        modifier = modifier,
        trailing = {
            if (allowClear && date != null) {
                IconButton(onClick = { onDateChange(null) }) {
                    Icon(Icons.Default.Clear, contentDescription = stringResource(id = R.string.clear))
                }
            } else {
                Icon(Icons.Default.CalendarMonth, contentDescription = null)
            }
        }
    )
    if (open) {
        val state = rememberDatePickerState(
            initialSelectedDateMillis = BusinessTime.dateToPickerMillis(date ?: LocalDate.now(BusinessTime.zone))
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onDateChange(BusinessTime.pickerMillisToDate(it)) }
                    open = false
                }) { Text(stringResource(id = R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(id = R.string.cancel)) } }
        ) {
            DatePicker(state = state)
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeField(
    label: String,
    time: LocalTime,
    onTimeChange: (LocalTime) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false
) {
    var open by rememberSaveable { mutableStateOf(false) }
    ClickableField(
        value = "%02d:%02d".format(time.hour, time.minute),
        label = label,
        onClick = { open = true },
        modifier = modifier,
        isError = isError,
        trailing = { Icon(Icons.Default.Schedule, contentDescription = null) }
    )
    if (open) {
        val state = rememberTimePickerState(initialHour = time.hour, initialMinute = time.minute, is24Hour = true)
        AlertDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    onTimeChange(LocalTime.of(state.hour, state.minute))
                    open = false
                }) { Text(stringResource(id = R.string.ok)) }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text(stringResource(id = R.string.cancel)) } },
            text = { TimePicker(state = state) }
        )
    }
}

/** Searchable client selector: typing filters the list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ClientPickerField(
    clients: List<ClientEntity>,
    selectedId: String?,
    onSelect: (ClientEntity) -> Unit,
    modifier: Modifier = Modifier,
    isError: Boolean = false,
    enabled: Boolean = true
) {
    val selected = clients.firstOrNull { it.id == selectedId }
    var expanded by remember { mutableStateOf(false) }
    var query by rememberSaveable(selected?.id) { mutableStateOf(selected?.name.orEmpty()) }
    val filtered = clients.filter { query.isBlank() || query == selected?.name || it.name.contains(query, ignoreCase = true) }

    ExposedDropdownMenuBox(
        expanded = expanded && enabled,
        onExpandedChange = { if (enabled) expanded = it },
        modifier = modifier
    ) {
        OutlinedTextField(
            value = query,
            onValueChange = {
                query = it
                expanded = true
            },
            label = { Text(stringResource(id = R.string.field_client)) },
            singleLine = true,
            enabled = enabled,
            isError = isError,
            supportingText = if (isError) {
                { Text(stringResource(id = R.string.error_client_required)) }
            } else {
                null
            },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryEditable, enabled)
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            if (filtered.isEmpty()) {
                DropdownMenuItem(text = { Text(stringResource(id = R.string.clients_none_found)) }, onClick = {}, enabled = false)
            }
            filtered.take(MAX_SUGGESTIONS).forEach { client ->
                DropdownMenuItem(
                    text = { Text(client.name) },
                    onClick = {
                        onSelect(client)
                        query = client.name
                        expanded = false
                    }
                )
            }
        }
    }
}

/** Read-only dropdown for a fixed list of options. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun <T> OptionField(
    label: String,
    options: List<T>,
    selected: T,
    optionLabel: @Composable (T) -> String,
    onSelect: (T) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = expanded && enabled, onExpandedChange = { if (enabled) expanded = it }, modifier = modifier) {
        OutlinedTextField(
            value = optionLabel(selected),
            onValueChange = {},
            readOnly = true,
            enabled = enabled,
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(MenuAnchorType.PrimaryNotEditable, enabled)
        )
        ExposedDropdownMenu(expanded = expanded && enabled, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(optionLabel(option)) },
                    onClick = {
                        onSelect(option)
                        expanded = false
                    }
                )
            }
        }
    }
}

private const val MAX_SUGGESTIONS = 30
