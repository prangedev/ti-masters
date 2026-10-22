package br.com.timasters.app.ui.screens.routers

import android.content.ClipData
import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.network.SubnetUtils
import br.com.timasters.app.data.routers.config.ApplyResult
import br.com.timasters.app.data.routers.config.ConfigPlan
import br.com.timasters.app.data.routers.config.ConfigTask
import br.com.timasters.app.data.routers.config.ConfigValidation
import br.com.timasters.app.data.routers.config.OpenWrtExecutor
import br.com.timasters.app.data.routers.config.RouterPlatform
import br.com.timasters.app.data.routers.config.RouterSession
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.screens.business.OptionField
import br.com.timasters.app.ui.viewmodel.ConfigConnection
import br.com.timasters.app.ui.viewmodel.ConfigStage
import br.com.timasters.app.ui.viewmodel.RouterConfigViewModel
import br.com.timasters.app.ui.viewmodel.TaskKind
import br.com.timasters.app.ui.viewmodel.parseHost
import kotlinx.coroutines.launch

/** "Configurar" tab: log in to a MikroTik/OpenWrt, choose a task, review, apply and undo. */
@Composable
fun ConfigureTab(viewModel: RouterConfigViewModel = hiltViewModel()) {
    val connection by viewModel.connection.collectAsStateWithLifecycle()
    val stage by viewModel.stage.collectAsStateWithLifecycle()
    val session = (connection as? ConfigConnection.Connected)?.session

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        when (val current = connection) {
            is ConfigConnection.Connected, is ConfigConnection.Manual -> {
                item { ConnectedHeader(session, (current as? ConfigConnection.Manual)?.unsupported == true, viewModel::disconnect) }
                item { StageContent(stage, session, viewModel) }
            }
            else -> item { ConnectCard(current, viewModel) }
        }
    }
}

@Composable
private fun ConnectCard(connection: ConfigConnection, viewModel: RouterConfigViewModel) {
    var host by rememberSaveable { mutableStateOf(viewModel.defaultHost()) }
    var user by rememberSaveable { mutableStateOf("admin") }
    var password by rememberSaveable { mutableStateOf("") }
    var https by rememberSaveable { mutableStateOf(false) }
    val connecting = connection == ConfigConnection.Connecting

    SectionCard(title = stringResource(id = R.string.config_connect_title)) {
        Text(
            text = stringResource(id = R.string.config_connect_hint),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        TextInput(stringResource(id = R.string.config_host), host, { host = it }, keyboardType = KeyboardType.Uri)
        TextInput(stringResource(id = R.string.config_user), user, { user = it })
        TextInput(stringResource(id = R.string.config_password), password, { password = it }, secret = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = https, onCheckedChange = { https = it })
            Text(stringResource(id = R.string.config_https))
        }
        when (connection) {
            ConfigConnection.WrongPassword -> MessageCard(text = stringResource(id = R.string.config_wrong_password), isError = true)
            is ConfigConnection.Unreachable -> MessageCard(text = stringResource(id = R.string.config_unreachable, connection.message), isError = true)
            else -> Unit
        }
        Button(
            onClick = { viewModel.connect(host, https, user, password) },
            enabled = !connecting && SubnetUtils.isValidIpv4(parseHost(host).first),
            modifier = Modifier.fillMaxWidth()
        ) {
            if (connecting) {
                CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                Text(stringResource(id = R.string.config_connecting), modifier = Modifier.padding(start = 8.dp))
            } else {
                Text(stringResource(id = R.string.config_connect))
            }
        }
        OutlinedButton(onClick = viewModel::useManualMode, enabled = !connecting, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.config_manual_mode))
        }
    }
}

@Composable
private fun ConnectedHeader(session: RouterSession?, unsupported: Boolean, onDisconnect: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant), modifier = Modifier.fillMaxWidth()) {
            Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                Text(
                    text = session?.let { stringResource(id = R.string.config_connected, platformLabel(it.info.platform), it.info.model, it.info.version) }
                        ?: stringResource(id = R.string.config_platform_manual),
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier.padding(start = 12.dp).weight(1f)
                )
                TextButton(onClick = onDisconnect) { Text(stringResource(id = R.string.config_disconnect)) }
            }
        }
        if (unsupported) MessageCard(text = stringResource(id = R.string.config_unsupported), isError = false)
    }
}

@Composable
private fun platformLabel(platform: RouterPlatform): String = stringResource(
    id = if (platform == RouterPlatform.MIKROTIK) R.string.config_platform_mikrotik else R.string.config_platform_openwrt
)

@Composable
private fun StageContent(stage: ConfigStage, session: RouterSession?, viewModel: RouterConfigViewModel) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when (stage) {
            ConfigStage.Tasks -> TaskList(session, viewModel::openTask)
            is ConfigStage.Form -> TaskForm(stage.kind, session, onReview = viewModel::review, onBack = viewModel::backToTasks)
            ConfigStage.Reading -> Progress(R.string.config_reading)
            is ConfigStage.Preview -> PreviewCard(stage.plan, session, onApply = { viewModel.apply(stage.plan) }, onBack = viewModel::backToTasks)
            is ConfigStage.Manual -> ManualSteps(stage.task, onBack = viewModel::backToTasks)
            is ConfigStage.Applying -> Progress(R.string.config_applying)
            is ConfigStage.Done -> DoneCard(stage.result, canUndo = viewModel.canUndo, onUndo = viewModel::undo, onNext = viewModel::backToTasks)
            ConfigStage.Undoing -> Progress(R.string.config_undoing)
            is ConfigStage.Undone -> {
                val ok = stage.result is ApplyResult.Success
                MessageCard(text = stringResource(id = if (ok) R.string.config_undone else R.string.config_undo_failed), isError = !ok)
                Button(onClick = viewModel::backToTasks, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.config_other_task)) }
            }
            is ConfigStage.Error -> {
                MessageCard(text = stringResource(id = R.string.config_failed, stage.message), isError = true)
                OutlinedButton(onClick = viewModel::backToTasks, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.config_back)) }
            }
        }
    }
}

@Composable
private fun Progress(@StringRes text: Int) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(8.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Text(stringResource(id = text), modifier = Modifier.padding(start = 12.dp))
    }
}

@Composable
private fun TaskList(session: RouterSession?, onOpen: (TaskKind) -> Unit) {
    Text(stringResource(id = R.string.config_choose_task), style = MaterialTheme.typography.titleMedium)
    TaskKind.entries.forEach { kind ->
        val available = when (kind) {
            TaskKind.MESH -> session?.info?.supportsMesh != false
            TaskKind.BRIDGE -> session?.info?.supportsBridge != false
            else -> true
        }
        val (title, description) = when (kind) {
            TaskKind.PPPOE -> R.string.config_task_pppoe to R.string.config_task_pppoe_desc
            TaskKind.DHCP -> R.string.config_task_dhcp to R.string.config_task_dhcp_desc
            TaskKind.BRIDGE -> R.string.config_task_bridge to R.string.config_task_bridge_desc
            TaskKind.MESH -> R.string.config_task_mesh to R.string.config_task_mesh_desc
        }
        Card(
            onClick = { onOpen(kind) },
            enabled = available,
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text(stringResource(id = title), style = MaterialTheme.typography.titleSmall)
                Text(
                    text = stringResource(id = if (available) description else R.string.config_task_unavailable),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

// region Forms

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TaskForm(kind: TaskKind, session: RouterSession?, onReview: (ConfigTask) -> Unit, onBack: () -> Unit) {
    val info = session?.info
    val isOpenWrt = info?.platform == RouterPlatform.OPENWRT
    val ports = info?.ports.orEmpty()
    val lans = info?.bridges.orEmpty()
    val (lanIp, lanPrefix) = session?.lanAddress ?: ("192.168.88.1" to 24)
    val ipBase = lanIp.substringBeforeLast('.')

    // One set of fields per task; rememberSaveable keys keep them across rotation.
    var port by rememberSaveable(kind) { mutableStateOf(session?.wanPort ?: ports.firstOrNull() ?: "ether1") }
    var vlan by rememberSaveable(kind) { mutableStateOf("") }
    var user by rememberSaveable(kind) { mutableStateOf("") }
    var secret by rememberSaveable(kind) { mutableStateOf("") }
    var lan by rememberSaveable(kind) { mutableStateOf(lans.firstOrNull { it == "lan" || it.startsWith("bridge") } ?: lans.firstOrNull() ?: "bridge") }
    var gateway by rememberSaveable(kind) { mutableStateOf(lanIp) }
    var prefix by rememberSaveable(kind) { mutableStateOf(lanPrefix.toString()) }
    var rangeStart by rememberSaveable(kind) { mutableStateOf("$ipBase.100") }
    var rangeEnd by rememberSaveable(kind) { mutableStateOf("$ipBase.200") }
    var dns by rememberSaveable(kind) { mutableStateOf("1.1.1.1,8.8.8.8") }
    var lease by rememberSaveable(kind) { mutableStateOf("12") }
    var bridgeName by rememberSaveable(kind) { mutableStateOf("bridge") }
    var bridgePorts by rememberSaveable(kind) { mutableStateOf(if (isOpenWrt) listOfNotNull(session?.wanPort) else emptyList()) }
    var upstream by rememberSaveable(kind) { mutableStateOf("") }
    var meshName by rememberSaveable(kind) { mutableStateOf("") }
    var radio by rememberSaveable(kind) { mutableStateOf(info?.radios?.firstOrNull().orEmpty()) }
    var errors by rememberSaveable(kind) { mutableStateOf(emptyList<Int>()) }

    val task: ConfigTask = when (kind) {
        TaskKind.PPPOE -> ConfigTask.Pppoe(port.trim(), vlan.trim().toIntOrNull(), user.trim(), secret)
        TaskKind.DHCP -> ConfigTask.Dhcp(lan, gateway.trim(), prefix.trim().toIntOrNull() ?: 0, rangeStart.trim(), rangeEnd.trim(), dns.trim(), lease.trim().toIntOrNull() ?: 0)
        TaskKind.BRIDGE -> ConfigTask.Bridge(if (isOpenWrt) "br-lan" else bridgeName.trim(), if (session == null) listOf("lan") else bridgePorts, upstream.trim().ifBlank { null })
        TaskKind.MESH -> ConfigTask.Mesh(meshName.trim(), secret, radio.ifBlank { null }, lan)
    }

    SectionCard(title = stringResource(id = taskTitle(kind))) {
        when (kind) {
            TaskKind.PPPOE -> {
                if (session != null) PortPicker(stringResource(id = R.string.config_port), ports, port) { port = it }
                TextInput(stringResource(id = R.string.config_vlan), vlan, { vlan = it.filter(Char::isDigit) }, keyboardType = KeyboardType.Number)
                TextInput(stringResource(id = R.string.config_pppoe_user), user, { user = it })
                TextInput(stringResource(id = R.string.config_pppoe_password), secret, { secret = it }, secret = true)
            }
            TaskKind.DHCP -> {
                if (session != null) PortPicker(stringResource(id = R.string.config_lan), lans, lan) { lan = it }
                TextInput(stringResource(id = R.string.config_gateway), gateway, { gateway = it }, keyboardType = KeyboardType.Decimal)
                TextInput(stringResource(id = R.string.config_prefix), prefix, { prefix = it.filter(Char::isDigit) }, keyboardType = KeyboardType.Number)
                TextInput(stringResource(id = R.string.config_range_start), rangeStart, { rangeStart = it }, keyboardType = KeyboardType.Decimal)
                TextInput(stringResource(id = R.string.config_range_end), rangeEnd, { rangeEnd = it }, keyboardType = KeyboardType.Decimal)
                TextInput(stringResource(id = R.string.config_dns), dns, { dns = it }, keyboardType = KeyboardType.Uri)
                TextInput(stringResource(id = R.string.config_lease), lease, { lease = it.filter(Char::isDigit) }, keyboardType = KeyboardType.Number)
            }
            TaskKind.BRIDGE -> {
                if (!isOpenWrt) TextInput(stringResource(id = R.string.config_bridge_name), bridgeName, { bridgeName = it })
                if (session != null) {
                    Text(stringResource(id = R.string.config_bridge_ports), style = MaterialTheme.typography.labelLarge)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        ports.forEach { name ->
                            FilterChip(
                                selected = name in bridgePorts,
                                onClick = { bridgePorts = if (name in bridgePorts) bridgePorts - name else bridgePorts + name },
                                label = { Text(name) }
                            )
                        }
                    }
                }
                if (isOpenWrt || session == null) {
                    TextInput(stringResource(id = R.string.config_upstream), upstream, { upstream = it }, keyboardType = KeyboardType.Decimal)
                }
            }
            TaskKind.MESH -> {
                TextInput(stringResource(id = if (isOpenWrt) R.string.config_mesh_id else R.string.config_mesh_ssid), meshName, { meshName = it })
                TextInput(stringResource(id = R.string.config_mesh_password), secret, { secret = it }, secret = true)
                if (session != null) PortPicker(stringResource(id = R.string.config_lan), lans, lan) { lan = it }
                if (isOpenWrt && !info?.radios.isNullOrEmpty()) PortPicker(stringResource(id = R.string.config_radio), info?.radios.orEmpty(), radio) { radio = it }
            }
        }
        errors.forEach { MessageCard(text = stringResource(id = it), isError = true) }
        Button(
            onClick = {
                errors = ConfigValidation.validate(task)
                if (errors.isEmpty()) onReview(task)
            },
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(id = R.string.config_review)) }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.config_back)) }
    }
}

@StringRes
private fun taskTitle(kind: TaskKind): Int = when (kind) {
    TaskKind.PPPOE -> R.string.config_task_pppoe
    TaskKind.DHCP -> R.string.config_task_dhcp
    TaskKind.BRIDGE -> R.string.config_task_bridge
    TaskKind.MESH -> R.string.config_task_mesh
}

@Composable
private fun PortPicker(label: String, options: List<String>, selected: String, onSelect: (String) -> Unit) {
    if (options.isEmpty()) {
        TextInput(label, selected, onSelect)
    } else {
        OptionField(label = label, options = options, selected = selected, optionLabel = { it }, onSelect = onSelect)
    }
}

@Composable
private fun TextInput(
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
        visualTransformation = if (secret) PasswordVisualTransformation() else androidx.compose.ui.text.input.VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (secret) KeyboardType.Password else keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}

// endregion

// region Preview and result

@Composable
private fun PreviewCard(plan: ConfigPlan, session: RouterSession?, onApply: () -> Unit, onBack: () -> Unit) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    plan.warnings.forEach { warning ->
        MessageCard(text = stringResource(id = warning.resId, *warning.args.toTypedArray()), isError = false)
    }
    SectionCard(title = stringResource(id = R.string.config_preview_title)) {
        if (plan.isEmpty) {
            Text(stringResource(id = R.string.config_no_changes), style = MaterialTheme.typography.bodyMedium)
        } else {
            Text(
                text = stringResource(id = R.string.config_preview_hint),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            CodeBox(plan.ops.joinToString("\n") { it.command })
            Text(
                text = stringResource(
                    id = if (session?.info?.platform == RouterPlatform.OPENWRT) R.string.config_safety_openwrt else R.string.config_safety_mikrotik
                ),
                style = MaterialTheme.typography.bodySmall
            )
            Button(onClick = { confirming = true }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.config_apply)) }
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.config_back)) }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text(stringResource(id = R.string.config_confirm_title)) },
            text = { Text(stringResource(id = R.string.config_confirm_text, plan.ops.size)) },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onApply()
                }) { Text(stringResource(id = R.string.config_apply)) }
            },
            dismissButton = { TextButton(onClick = { confirming = false }) { Text(stringResource(id = R.string.cancel)) } }
        )
    }
}

@Composable
private fun DoneCard(result: ApplyResult, canUndo: Boolean, onUndo: () -> Unit, onNext: () -> Unit) {
    when (result) {
        is ApplyResult.Success -> {
            MessageCard(text = stringResource(id = R.string.config_success), isError = false)
            result.backupName?.let { Text(stringResource(id = R.string.config_backup_saved, it), style = MaterialTheme.typography.bodySmall) }
            if (canUndo) OutlinedButton(onClick = onUndo, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.config_undo)) }
        }
        is ApplyResult.Failed -> {
            val text = if (result.message == OpenWrtExecutor.CONFIRM_TIMEOUT) stringResource(id = R.string.config_confirm_timeout)
            else stringResource(id = R.string.config_failed, result.message)
            MessageCard(text = text, isError = true)
            MessageCard(
                text = stringResource(id = if (result.rolledBack) R.string.config_failed_rolled_back else R.string.config_failed_partial),
                isError = !result.rolledBack
            )
        }
    }
    Button(onClick = onNext, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.config_other_task)) }
}

/** Steps with the technician's values, for routers without automatic access. */
@Composable
private fun ManualSteps(task: ConfigTask, onBack: () -> Unit) {
    val steps = when (task) {
        is ConfigTask.Pppoe -> listOfNotNull(
            stringResource(id = R.string.config_manual_pppoe_1),
            stringResource(id = R.string.config_manual_pppoe_2),
            stringResource(id = R.string.config_manual_pppoe_3, task.user),
            task.vlanId?.let { stringResource(id = R.string.config_manual_pppoe_vlan, it) },
            stringResource(id = R.string.config_manual_pppoe_4)
        )
        is ConfigTask.Dhcp -> listOf(
            stringResource(id = R.string.config_manual_dhcp_1, task.gateway, task.prefix),
            stringResource(id = R.string.config_manual_dhcp_2, task.rangeStart, task.rangeEnd),
            stringResource(id = R.string.config_manual_dhcp_3, task.dns, task.leaseHours),
            stringResource(id = R.string.config_manual_dhcp_4)
        )
        is ConfigTask.Bridge -> listOfNotNull(
            stringResource(id = R.string.config_manual_bridge_1),
            stringResource(id = R.string.config_manual_bridge_2),
            stringResource(id = R.string.config_manual_bridge_3),
            task.upstreamGateway?.let { stringResource(id = R.string.config_manual_bridge_gateway, it) }
        )
        is ConfigTask.Mesh -> listOf(
            stringResource(id = R.string.config_manual_mesh_1),
            stringResource(id = R.string.config_manual_mesh_2, task.network),
            stringResource(id = R.string.config_manual_mesh_3),
            stringResource(id = R.string.config_manual_mesh_4)
        )
    }
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(id = R.string.config_manual_title)
    val text = steps.mapIndexed { index, step -> "${index + 1}. $step" }.joinToString("\n")
    SectionCard(title = label) {
        steps.forEachIndexed { index, step ->
            Row {
                Text("${index + 1}.", modifier = Modifier.padding(end = 8.dp), style = MaterialTheme.typography.titleSmall)
                Text(step, style = MaterialTheme.typography.bodyMedium)
            }
        }
        OutlinedButton(
            onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) } },
            modifier = Modifier.fillMaxWidth()
        ) {
            Icon(Icons.Default.ContentCopy, contentDescription = null, modifier = Modifier.size(18.dp))
            Text(stringResource(id = R.string.config_copy_steps), modifier = Modifier.padding(start = 8.dp))
        }
        OutlinedButton(onClick = onBack, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.config_back)) }
    }
}

@Composable
private fun CodeBox(text: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
            .padding(8.dp)
            .horizontalScroll(rememberScrollState())
    ) {
        SelectionContainer {
            Text(text = text, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
        }
    }
}

// endregion
