package br.com.timasters.app.ui.screens.network

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
import androidx.compose.material.icons.filled.Computer
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Print
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.network.Ports
import br.com.timasters.app.data.lan.LanDevice
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.ScannerNoticeDialog
import br.com.timasters.app.ui.viewmodel.LanScanViewModel

@Composable
fun DevicesTab(
    onScanPorts: (ip: String) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: LanScanViewModel = hiltViewModel()
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
                text = stringResource(id = R.string.lan_intro),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
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
                ) {
                    Text(stringResource(id = if (progress == null) R.string.lan_start else R.string.lan_restart))
                }
            }
        }
        state.error?.let { error ->
            item { MessageCard(text = stringResource(id = error.messageResId), isError = true) }
        }
        if (progress != null) {
            item {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(
                        text = if (progress.isComplete) {
                            stringResource(id = R.string.lan_done, progress.devices.size, progress.subnet)
                        } else {
                            stringResource(id = R.string.lan_progress, progress.scanned, progress.total, progress.subnet)
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
            items(progress.devices, key = { it.ip }) { device ->
                DeviceRow(device = device, onScanPorts = { onScanPorts(device.ip) })
                HorizontalDivider()
            }
            if (progress.isComplete) {
                item {
                    Text(
                        text = stringResource(id = R.string.lan_mac_note),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
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

@Composable
private fun DeviceRow(device: LanDevice, onScanPorts: () -> Unit) {
    val title = when {
        device.isThisDevice -> stringResource(id = R.string.lan_this_device)
        device.isGateway -> stringResource(id = R.string.lan_router)
        else -> device.hostname ?: guessKind(device)?.let { stringResource(id = it) } ?: stringResource(id = R.string.lan_unknown_device)
    }
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Icon(
            imageVector = iconFor(device),
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(28.dp)
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(text = title, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = listOfNotNull(
                    device.ip,
                    device.hostname?.takeIf { title != it },
                    device.latencyMs?.let { "$it ms" }
                ).joinToString(" · "),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            if (device.openPorts.isNotEmpty()) {
                Text(
                    text = device.openPorts.joinToString(", ") { port ->
                        Ports.serviceName(port)?.let { "$port ($it)" } ?: port.toString()
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.tertiary
                )
            }
        }
        if (!device.isThisDevice) {
            TextButton(onClick = onScanPorts) { Text(stringResource(id = R.string.lan_scan_ports)) }
        }
    }
}

/** Best guess from the services a device answers on (Android does not expose MAC vendors). */
private fun guessKind(device: LanDevice): Int? {
    val ports = device.openPorts.toSet()
    return when {
        ports.any { it in setOf(8291, 8728, 8729) } -> R.string.lan_kind_mikrotik
        ports.any { it in setOf(554, 37777) } -> R.string.lan_kind_camera
        ports.any { it in setOf(9100, 631) } -> R.string.lan_kind_printer
        ports.any { it in setOf(3389, 445, 139) } -> R.string.lan_kind_computer
        else -> null
    }
}

private fun iconFor(device: LanDevice): ImageVector {
    val ports = device.openPorts.toSet()
    return when {
        device.isThisDevice -> Icons.Default.Smartphone
        device.isGateway || ports.any { it in setOf(8291, 8728, 8729) } -> Icons.Default.Router
        ports.any { it in setOf(554, 37777) } -> Icons.Default.Videocam
        ports.any { it in setOf(9100, 631) } -> Icons.Default.Print
        ports.any { it in setOf(3389, 445, 139) } -> Icons.Default.Computer
        else -> Icons.Default.Devices
    }
}
