package br.com.timasters.app.ui.screens.routers

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.data.routers.config.MikroTikSession
import br.com.timasters.app.data.routers.config.OpenWrtSession
import br.com.timasters.app.data.routers.config.RouterSession
import br.com.timasters.app.data.routers.firmware.BuildStatus
import br.com.timasters.app.data.routers.firmware.FirmwareRules
import br.com.timasters.app.data.routers.firmware.FirstBootSettings
import br.com.timasters.app.data.routers.firmware.FlashCheck
import br.com.timasters.app.data.routers.firmware.IncompatibleReason
import br.com.timasters.app.data.routers.firmware.OverviewProfile
import br.com.timasters.app.data.routers.firmware.PackagePreset
import br.com.timasters.app.data.routers.firmware.SettingsProblem
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.FactoryFirmwareState
import br.com.timasters.app.ui.viewmodel.FirmwareError
import br.com.timasters.app.ui.viewmodel.FirmwareViewModel
import br.com.timasters.app.ui.viewmodel.MikroTikFirmwareState
import br.com.timasters.app.ui.viewmodel.OpenWrtFirmwareState

/** "Firmware" tab: update/flash the connected router, or prepare an OpenWrt install for a stock one. */
@Composable
fun FirmwareTab(onOpenConfigure: () -> Unit, viewModel: FirmwareViewModel = hiltViewModel()) {
    val session by viewModel.session.collectAsStateWithLifecycle()
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        item {
            Text(
                text = stringResource(id = R.string.fw_intro),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        item { ConnectedRouterSection(session, viewModel, onOpenConfigure) }
        item { FactorySection(viewModel) }
    }
}

// region Connected router

@Composable
private fun ConnectedRouterSection(session: RouterSession?, viewModel: FirmwareViewModel, onOpenConfigure: () -> Unit) {
    when (session) {
        is OpenWrtSession -> OpenWrtSection(session, viewModel)
        is MikroTikSession -> MikroTikSection(viewModel)
        else -> SectionCard(title = stringResource(id = R.string.fw_connected_title)) {
            Text(stringResource(id = R.string.fw_connect_first), style = MaterialTheme.typography.bodyMedium)
            OutlinedButton(onClick = onOpenConfigure, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(id = R.string.fw_go_configure))
            }
        }
    }
}

@Composable
private fun OpenWrtSection(session: OpenWrtSession, viewModel: FirmwareViewModel) {
    val state by viewModel.openWrt.collectAsStateWithLifecycle()
    SectionCard(title = "${stringResource(id = R.string.fw_connected_title)}: ${session.info.model}") {
        when (val current = state) {
            OpenWrtFirmwareState.Idle -> Button(onClick = viewModel::checkOpenWrt, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(id = R.string.fw_check))
            }
            OpenWrtFirmwareState.Checking -> Progress(stringResource(id = R.string.fw_checking))
            is OpenWrtFirmwareState.Incompatible -> {
                MessageCard(
                    text = when (current.reason) {
                        IncompatibleReason.NO_BOARD_INFO -> stringResource(id = R.string.fw_incompatible_board)
                        IncompatibleReason.NOT_IN_RELEASE -> stringResource(id = R.string.fw_incompatible_release, current.detail)
                        IncompatibleReason.SNAPSHOT -> stringResource(id = R.string.fw_incompatible_snapshot, current.detail)
                    },
                    isError = true
                )
                StartOver(viewModel::resetOpenWrt)
            }
            is OpenWrtFirmwareState.Ready -> OpenWrtOptions(current.check, onBuild = { presets, keep, settings ->
                viewModel.buildOpenWrt(current.check, presets, keep, settings)
            })
            is OpenWrtFirmwareState.Building -> BuildProgress(current.status)
            is OpenWrtFirmwareState.Downloading -> DownloadProgress(current.progress)
            is OpenWrtFirmwareState.Downloaded -> {
                ImageSummary(current.image.name, current.image.sha256, current.image.bytes.size)
                FlashButton(onFlash = { viewModel.flashOpenWrt(current) })
                OutlinedButton(onClick = viewModel::resetOpenWrt, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(id = R.string.fw_cancel))
                }
            }
            OpenWrtFirmwareState.Flashing -> Progress(stringResource(id = R.string.fw_flashing))
            is OpenWrtFirmwareState.Rebooting -> Progress(stringResource(id = R.string.fw_rebooting))
            is OpenWrtFirmwareState.Finished -> {
                MessageCard(
                    text = current.version?.let { stringResource(id = R.string.fw_finished, it) } ?: stringResource(id = R.string.fw_finished_unverified),
                    isError = false
                )
                StartOver(viewModel::resetOpenWrt)
            }
            is OpenWrtFirmwareState.Failed -> {
                MessageCard(text = errorText(current.error, current.detail), isError = true)
                if (current.nothingWritten) Text(stringResource(id = R.string.fw_nothing_written), style = MaterialTheme.typography.bodySmall)
                StartOver(viewModel::resetOpenWrt)
            }
        }
    }
}

@Composable
private fun OpenWrtOptions(check: FlashCheck.Compatible, onBuild: (Set<PackagePreset>, Boolean, FirstBootSettings) -> Unit) {
    var presets by rememberSaveable { mutableStateOf(setOf(PackagePreset.LUCI_PT_BR)) }
    var keep by rememberSaveable { mutableStateOf(true) }
    var settings by rememberSaveable(stateSaver = FirstBootSaver) { mutableStateOf(FirstBootSettings()) }
    var problems by rememberSaveable { mutableStateOf(emptyList<SettingsProblem>()) }

    InfoRow(stringResource(id = R.string.fw_model), check.model)
    InfoRow(stringResource(id = R.string.fw_board), check.boardName)
    InfoRow(stringResource(id = R.string.fw_current_version), check.currentVersion)
    InfoRow(stringResource(id = R.string.fw_new_version), check.newVersion)
    MessageCard(text = stringResource(id = R.string.fw_compatible), isError = false)
    if (check.currentVersion == check.newVersion) Text(stringResource(id = R.string.fw_same_version), style = MaterialTheme.typography.bodySmall)
    when (val packages = check.installedPackages) {
        null -> MessageCard(text = stringResource(id = R.string.fw_packages_unknown), isError = true)
        else -> if (packages.isNotEmpty()) Text(stringResource(id = R.string.fw_packages_kept, packages.size), style = MaterialTheme.typography.bodySmall)
    }
    PresetPicker(presets) { presets = it }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(id = R.string.fw_keep_settings), modifier = Modifier.weight(1f))
        Switch(checked = keep, onCheckedChange = { keep = it })
    }
    if (!keep) {
        Text(stringResource(id = R.string.fw_keep_settings_off), style = MaterialTheme.typography.bodySmall)
        FirstBootFields(settings, problems) { settings = it }
    }
    Button(
        onClick = {
            problems = if (keep) emptyList() else FirmwareRules.validate(settings)
            if (problems.isEmpty()) onBuild(presets, keep, settings)
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(id = R.string.fw_build)) }
}

@Composable
private fun MikroTikSection(viewModel: FirmwareViewModel) {
    val state by viewModel.mikroTik.collectAsStateWithLifecycle()
    SectionCard(title = stringResource(id = R.string.fw_mikrotik_title)) {
        Text(stringResource(id = R.string.fw_mikrotik_note), style = MaterialTheme.typography.bodySmall)
        when (val current = state) {
            MikroTikFirmwareState.Idle -> Button(onClick = { viewModel.checkMikroTik() }, modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(id = R.string.fw_mikrotik_check))
            }
            MikroTikFirmwareState.Checking -> Progress(stringResource(id = R.string.fw_checking))
            is MikroTikFirmwareState.Checked -> {
                if (current.justUpdated) MessageCard(text = stringResource(id = R.string.fw_mikrotik_updated), isError = false)
                InfoRow(stringResource(id = R.string.fw_current_version), current.update.installed)
                InfoRow(stringResource(id = R.string.fw_mikrotik_latest), current.update.latest.ifBlank { current.update.status })
                InfoRow(stringResource(id = R.string.fw_mikrotik_channel), current.update.channel)
                if (current.update.hasUpdate) {
                    Text(stringResource(id = R.string.fw_flash_warning), style = MaterialTheme.typography.bodySmall)
                    Button(onClick = viewModel::installRouterOs, modifier = Modifier.fillMaxWidth()) {
                        Text(stringResource(id = R.string.fw_mikrotik_install))
                    }
                } else {
                    MessageCard(text = stringResource(id = R.string.fw_mikrotik_up_to_date), isError = false)
                    current.boot?.let { boot ->
                        if (boot.needsUpgrade) {
                            OutlinedButton(onClick = viewModel::upgradeRouterBoot, modifier = Modifier.fillMaxWidth()) {
                                Text(stringResource(id = R.string.fw_routerboot_upgrade, boot.current, boot.available))
                            }
                        } else {
                            Text(stringResource(id = R.string.fw_routerboot_ok, boot.current), style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            MikroTikFirmwareState.Installing -> Progress(stringResource(id = R.string.fw_mikrotik_installing))
            MikroTikFirmwareState.Rebooting -> Progress(stringResource(id = R.string.fw_mikrotik_rebooting))
            is MikroTikFirmwareState.Failed -> {
                MessageCard(text = errorText(current.error, current.detail), isError = true)
                StartOver { viewModel.checkMikroTik() }
            }
        }
    }
}

// endregion

// region Stock router: first OpenWrt install

@Composable
private fun FactorySection(viewModel: FirmwareViewModel) {
    val state by viewModel.factory.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    SectionCard(title = stringResource(id = R.string.fw_factory_title)) {
        Text(stringResource(id = R.string.fw_factory_hint), style = MaterialTheme.typography.bodySmall)
        when (val current = state) {
            FactoryFirmwareState.Idle, FactoryFirmwareState.Loading, is FactoryFirmwareState.Results, is FactoryFirmwareState.Failed -> {
                Field(stringResource(id = R.string.fw_factory_search), query, { query = it })
                Button(
                    onClick = { viewModel.searchDevices(query) },
                    enabled = query.isNotBlank() && current != FactoryFirmwareState.Loading,
                    modifier = Modifier.fillMaxWidth()
                ) { Text(stringResource(id = R.string.fw_factory_search_button)) }
                when (current) {
                    FactoryFirmwareState.Loading -> Progress(stringResource(id = R.string.fw_factory_loading))
                    is FactoryFirmwareState.Results -> {
                        if (current.devices.isEmpty()) Text(stringResource(id = R.string.fw_factory_none), style = MaterialTheme.typography.bodyMedium)
                        current.devices.forEach { device -> DeviceRow(device) { viewModel.selectDevice(device) } }
                    }
                    is FactoryFirmwareState.Failed -> MessageCard(text = errorText(current.error, current.detail), isError = true)
                    else -> Unit
                }
            }
            is FactoryFirmwareState.Selected -> FactoryOptions(
                device = current.device,
                onBuild = { presets, settings -> viewModel.buildFactory(current.device, presets, settings) },
                onBack = viewModel::resetFactory
            )
            is FactoryFirmwareState.Building -> BuildProgress(current.status)
            is FactoryFirmwareState.Downloading -> DownloadProgress(current.progress)
            is FactoryFirmwareState.Saved -> {
                MessageCard(text = stringResource(id = R.string.fw_factory_saved), isError = false)
                ImageSummary(current.file.name, current.sha256, current.file.length().toInt())
                Button(onClick = { context.shareFirmware(current.file) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(id = R.string.fw_factory_share))
                }
                OutlinedButton(onClick = { context.openWiki(current.device) }, modifier = Modifier.fillMaxWidth()) {
                    Text(stringResource(id = R.string.fw_factory_wiki))
                }
                Text(stringResource(id = R.string.fw_factory_steps_title), style = MaterialTheme.typography.titleSmall)
                listOf(R.string.fw_factory_step_1, R.string.fw_factory_step_2, R.string.fw_factory_step_3, R.string.fw_factory_step_4)
                    .forEachIndexed { index, step -> Text("${index + 1}. ${stringResource(id = step)}", style = MaterialTheme.typography.bodyMedium) }
                StartOver(viewModel::resetFactory)
            }
        }
    }
}

@Composable
private fun DeviceRow(device: OverviewProfile, onClick: () -> Unit) {
    Card(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(device.names.joinToString(" / "), style = MaterialTheme.typography.titleSmall)
            Text(device.target, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun FactoryOptions(device: OverviewProfile, onBuild: (Set<PackagePreset>, FirstBootSettings) -> Unit, onBack: () -> Unit) {
    var presets by rememberSaveable { mutableStateOf(setOf(PackagePreset.LUCI_PT_BR)) }
    var settings by rememberSaveable(stateSaver = FirstBootSaver) { mutableStateOf(FirstBootSettings()) }
    var problems by rememberSaveable { mutableStateOf(emptyList<SettingsProblem>()) }
    Text(device.names.joinToString(" / "), style = MaterialTheme.typography.titleMedium)
    MessageCard(text = stringResource(id = R.string.fw_factory_check_label), isError = true)
    PresetPicker(presets) { presets = it }
    FirstBootFields(settings, problems) { settings = it }
    Button(
        onClick = {
            problems = FirmwareRules.validate(settings)
            if (problems.isEmpty()) onBuild(presets, settings)
        },
        modifier = Modifier.fillMaxWidth()
    ) { Text(stringResource(id = R.string.fw_factory_build)) }
    OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.fw_choose_other)) }
}

// endregion

// region Shared pieces

@Composable
private fun PresetPicker(selected: Set<PackagePreset>, onChange: (Set<PackagePreset>) -> Unit) {
    Text(stringResource(id = R.string.fw_presets_title), style = MaterialTheme.typography.titleSmall)
    PackagePreset.entries.forEach { preset ->
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = preset in selected, onCheckedChange = { onChange(if (it) selected + preset else selected - preset) })
            Column {
                Text(stringResource(id = preset.titleResId), style = MaterialTheme.typography.bodyMedium)
                Text(stringResource(id = preset.descriptionResId), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun FirstBootFields(settings: FirstBootSettings, problems: List<SettingsProblem>, onChange: (FirstBootSettings) -> Unit) {
    Text(stringResource(id = R.string.fw_first_boot_title), style = MaterialTheme.typography.titleSmall)
    MessageCard(text = stringResource(id = R.string.fw_first_boot_warning), isError = false)
    Field(stringResource(id = R.string.fw_hostname), settings.hostname, { onChange(settings.copy(hostname = it.trim())) })
    Field(stringResource(id = R.string.fw_lan_ip), settings.lanIp, { onChange(settings.copy(lanIp = it.trim())) }, keyboardType = KeyboardType.Decimal)
    Field(stringResource(id = R.string.fw_wifi_name), settings.wifiName, { onChange(settings.copy(wifiName = it)) })
    Field(stringResource(id = R.string.fw_wifi_password), settings.wifiPassword, { onChange(settings.copy(wifiPassword = it)) }, secret = true)
    problems.forEach { problem ->
        MessageCard(
            text = stringResource(
                id = when (problem) {
                    SettingsProblem.HOSTNAME -> R.string.fw_error_hostname
                    SettingsProblem.LAN_IP -> R.string.fw_error_lan_ip
                    SettingsProblem.WIFI_NAME -> R.string.fw_error_wifi_name
                    SettingsProblem.WIFI_PASSWORD -> R.string.fw_error_wifi_password
                }
            ),
            isError = true
        )
    }
}

@Composable
private fun BuildProgress(status: BuildStatus) {
    Progress(
        when (status) {
            is BuildStatus.Queued -> status.position?.takeIf { it > 0 }
                ?.let { stringResource(id = R.string.fw_queued, it.toString()) }
                ?: stringResource(id = R.string.fw_queued_next)
            else -> stringResource(id = R.string.fw_building)
        }
    )
}

@Composable
private fun DownloadProgress(progress: Float) {
    Text(stringResource(id = R.string.fw_downloading, (progress * 100).toInt()))
    LinearProgressIndicator(progress = { progress }, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ImageSummary(name: String, sha256: String, size: Int) {
    Text(stringResource(id = R.string.fw_ready_title), style = MaterialTheme.typography.titleSmall)
    InfoRow(stringResource(id = R.string.fw_file), name)
    InfoRow(stringResource(id = R.string.fw_size), stringResource(id = R.string.fw_size_value, size / 1_048_576f))
    SelectionContainer {
        Text("SHA-256: $sha256", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
    }
}

/** Flash only after the technician acknowledges the power warning and confirms. */
@Composable
private fun FlashButton(onFlash: () -> Unit) {
    var acknowledged by rememberSaveable { mutableStateOf(false) }
    var confirming by rememberSaveable { mutableStateOf(false) }
    MessageCard(text = stringResource(id = R.string.fw_flash_warning), isError = true)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = acknowledged, onCheckedChange = { acknowledged = it })
        Text(stringResource(id = R.string.fw_flash_ack), style = MaterialTheme.typography.bodyMedium)
    }
    Button(onClick = { confirming = true }, enabled = acknowledged, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(id = R.string.fw_flash))
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(id = R.string.fw_flash_confirm_title)) },
            text = { Text(stringResource(id = R.string.fw_flash_confirm_text)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onFlash()
                }) { Text(stringResource(id = R.string.fw_flash)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(id = R.string.cancel)) } }
        )
    }
}

@Composable
private fun Progress(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Text(text, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun StartOver(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.fw_start_over)) }
}

@Composable
private fun Field(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    secret: Boolean = false,
    keyboardType: KeyboardType = KeyboardType.Text
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (secret) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}

@Composable
private fun errorText(error: FirmwareError, detail: String): String = when (error) {
    FirmwareError.NO_INTERNET -> stringResource(id = R.string.fw_error_no_internet, detail)
    FirmwareError.BUILD_FAILED -> stringResource(id = R.string.fw_error_build, detail)
    FirmwareError.CHECKSUM -> stringResource(id = R.string.fw_error_checksum)
    FirmwareError.NO_FACTORY_IMAGE -> stringResource(id = R.string.fw_error_no_factory)
    FirmwareError.NOT_ENOUGH_MEMORY -> stringResource(id = R.string.fw_error_memory)
    FirmwareError.TRANSFER -> stringResource(id = R.string.fw_error_transfer)
    FirmwareError.REJECTED -> stringResource(id = R.string.fw_error_rejected)
    FirmwareError.ROUTER -> stringResource(id = R.string.fw_error_router, detail)
    FirmwareError.REBOOT_TIMEOUT -> stringResource(id = R.string.fw_error_timeout)
}

/** Keeps the first-boot form across rotation. */
private val FirstBootSaver = androidx.compose.runtime.saveable.listSaver<FirstBootSettings, String>(
    save = { listOf(it.hostname, it.lanIp, it.wifiName, it.wifiPassword) },
    restore = { FirstBootSettings(it[0], it[1], it[2], it[3]) }
)

private fun Context.shareFirmware(file: java.io.File) {
    val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "application/octet-stream"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    startActivity(Intent.createChooser(intent, null))
}

/** OpenWrt's table of hardware page for the device (same link the official firmware selector uses). */
private fun Context.openWiki(device: OverviewProfile) {
    val title = device.titles.firstOrNull()?.let { listOf(it.vendor, it.model).filter(String::isNotBlank).joinToString(" ") }.orEmpty()
    try {
        startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://openwrt.org/start?do=search&id=toh&q=" + Uri.encode("$title @toh"))))
    } catch (_: ActivityNotFoundException) {
        // No browser.
    }
}

// endregion
