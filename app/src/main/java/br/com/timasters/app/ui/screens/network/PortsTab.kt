package br.com.timasters.app.ui.screens.network

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
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.network.Ports
import br.com.timasters.app.ui.components.LockedFeatureCard
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.ScannerNoticeDialog
import br.com.timasters.app.ui.viewmodel.PortScanMode
import br.com.timasters.app.ui.viewmodel.PortScanViewModel

@Composable
fun PortsTab(
    canScanPorts: Boolean,
    onNavigateToPaywall: () -> Unit,
    viewModel: PortScanViewModel,
    modifier: Modifier = Modifier
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val noticeAccepted by viewModel.noticeAccepted.collectAsStateWithLifecycle()
    var showNotice by rememberSaveable { mutableStateOf(false) }
    val progress = state.progress

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Text(
                text = stringResource(id = R.string.ports_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        if (!canScanPorts) {
            item {
                LockedFeatureCard(
                    title = stringResource(id = R.string.ports_locked_title),
                    description = stringResource(id = R.string.ports_locked_desc),
                    onUnlock = onNavigateToPaywall
                )
            }
            return@LazyColumn
        }

        item {
            OutlinedTextField(
                value = state.host,
                onValueChange = viewModel::onHostChange,
                label = { Text(stringResource(id = R.string.ports_host_label)) },
                singleLine = true,
                enabled = !state.isRunning,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next),
                modifier = Modifier.fillMaxWidth()
            )
        }
        item {
            SingleChoiceSegmentedButtonRow(modifier = Modifier.fillMaxWidth()) {
                PortScanMode.entries.forEachIndexed { index, mode ->
                    SegmentedButton(
                        selected = state.mode == mode,
                        onClick = { viewModel.onModeChange(mode) },
                        enabled = !state.isRunning,
                        shape = SegmentedButtonDefaults.itemShape(index = index, count = PortScanMode.entries.size)
                    ) {
                        Text(
                            stringResource(
                                id = when (mode) {
                                    PortScanMode.COMMON -> R.string.ports_mode_common
                                    PortScanMode.CUSTOM -> R.string.ports_mode_custom
                                }
                            )
                        )
                    }
                }
            }
        }
        item {
            if (state.mode == PortScanMode.CUSTOM) {
                OutlinedTextField(
                    value = state.customPorts,
                    onValueChange = viewModel::onCustomPortsChange,
                    label = { Text(stringResource(id = R.string.ports_custom_label)) },
                    supportingText = { Text(stringResource(id = R.string.ports_custom_hint, Ports.MAX_PORTS_PER_SCAN)) },
                    singleLine = true,
                    enabled = !state.isRunning,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth()
                )
            } else {
                Text(
                    text = stringResource(id = R.string.ports_common_hint, Ports.COMMON_SERVICES.size),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        item {
            if (state.isRunning) {
                OutlinedButton(onClick = viewModel::stop, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(id = R.string.stop))
                }
            } else {
                Button(
                    onClick = { if (noticeAccepted == true) viewModel.start() else showNotice = true },
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(id = R.string.ports_start)) }
            }
        }
        state.error?.let { error ->
            item { MessageCard(text = stringResource(id = error.messageResId), isError = true) }
        }
        if (progress != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = when {
                            !progress.isComplete -> stringResource(
                                id = R.string.ports_progress, progress.scanned, progress.total, progress.address
                            )
                            progress.openPorts.isEmpty() -> stringResource(id = R.string.ports_none_open, progress.total, progress.address)
                            else -> stringResource(id = R.string.ports_done, progress.openPorts.size, progress.address)
                        },
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (!progress.isComplete) {
                        LinearProgressIndicator(
                            progress = { if (progress.total == 0) 0f else progress.scanned.toFloat() / progress.total },
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                }
            }
            items(progress.openPorts, key = { it }) { port ->
                Row(modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 6.dp)) {
                    Text(
                        text = port.toString(),
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.weight(0.3f)
                    )
                    Text(
                        text = Ports.serviceName(port) ?: stringResource(id = R.string.ports_unknown_service),
                        style = MaterialTheme.typography.bodyLarge,
                        modifier = Modifier.weight(0.7f)
                    )
                }
                HorizontalDivider()
            }
        }
    }

    if (showNotice) {
        ScannerNoticeDialog(
            onAccept = {
                showNotice = false
                viewModel.acceptNotice()
                viewModel.start()
            },
            onDismiss = { showNotice = false }
        )
    }
}
