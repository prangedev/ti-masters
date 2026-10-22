package br.com.timasters.app.ui.screens.tools

import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Cable
import androidx.compose.material.icons.filled.Calculate
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Password
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Route
import androidx.compose.material.icons.filled.SettingsInputAntenna
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Straighten
import androidx.compose.material.icons.filled.Verified
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material.icons.filled.ViewList
import androidx.compose.material.icons.filled.BatteryChargingFull
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.viewmodel.ToolFailure
import kotlinx.coroutines.launch

enum class ToolSection(@param:StringRes val titleResId: Int) {
    NETWORK(R.string.tools_section_network),
    CALCULATORS(R.string.tools_section_calculators),
    REFERENCES(R.string.tools_section_references),
    UTILITIES(R.string.tools_section_utilities)
}

/** Every built-in tool. Network tests need the Advanced plan; the rest are free. */
enum class Tool(
    val section: ToolSection,
    @param:StringRes val titleResId: Int,
    @param:StringRes val descriptionResId: Int,
    val icon: ImageVector,
    /** Needs the Android 17 local network permission (broadcasts to the LAN). */
    val needsLocalNetwork: Boolean = false
) {
    TRACEROUTE(ToolSection.NETWORK, R.string.tool_traceroute, R.string.tool_traceroute_desc, Icons.Default.Route),
    DNS(ToolSection.NETWORK, R.string.tool_dns, R.string.tool_dns_desc, Icons.Default.Dns),
    SITE(ToolSection.NETWORK, R.string.tool_site, R.string.tool_site_desc, Icons.Default.Verified),
    WOL(ToolSection.NETWORK, R.string.tool_wol, R.string.tool_wol_desc, Icons.Default.PowerSettingsNew, needsLocalNetwork = true),
    MTU(ToolSection.NETWORK, R.string.tool_mtu, R.string.tool_mtu_desc, Icons.Default.Straighten),
    SUBNET(ToolSection.CALCULATORS, R.string.tool_subnet, R.string.tool_subnet_desc, Icons.Default.Hub),
    FIBER(ToolSection.CALCULATORS, R.string.tool_fiber, R.string.tool_fiber_desc, Icons.Default.Cable),
    RADIO(ToolSection.CALCULATORS, R.string.tool_radio, R.string.tool_radio_desc, Icons.Default.SettingsInputAntenna),
    CCTV(ToolSection.CALCULATORS, R.string.tool_cctv, R.string.tool_cctv_desc, Icons.Default.Videocam),
    POE(ToolSection.CALCULATORS, R.string.tool_poe, R.string.tool_poe_desc, Icons.Default.Bolt),
    UPS(ToolSection.CALCULATORS, R.string.tool_ups, R.string.tool_ups_desc, Icons.Default.BatteryChargingFull),
    CONVERTERS(ToolSection.CALCULATORS, R.string.tool_converters, R.string.tool_converters_desc, Icons.Default.SwapHoriz),
    RJ45(ToolSection.REFERENCES, R.string.tool_rj45, R.string.tool_rj45_desc, Icons.Default.Calculate),
    PORTS(ToolSection.REFERENCES, R.string.tool_ports, R.string.tool_ports_desc, Icons.Default.ViewList),
    PASSWORD(ToolSection.UTILITIES, R.string.tool_password, R.string.tool_password_desc, Icons.Default.Password);

    val requiresAdvanced: Boolean get() = section == ToolSection.NETWORK
}

@Composable
fun ToolsTab(canUseNetworkTools: Boolean, onNavigateToPaywall: () -> Unit) {
    var open by rememberSaveable { mutableStateOf<Tool?>(null) }
    BackHandler(enabled = open != null) { open = null }
    val tool = open
    if (tool == null) {
        ToolCatalog(canUseNetworkTools, onOpen = { if (it.requiresAdvanced && !canUseNetworkTools) onNavigateToPaywall() else open = it })
    } else {
        ToolScreen(tool, onBack = { open = null })
    }
}

@Composable
private fun ToolCatalog(canUseNetworkTools: Boolean, onOpen: (Tool) -> Unit) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        ToolSection.entries.forEach { section ->
            item {
                Text(stringResource(id = section.titleResId), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            }
            Tool.entries.filter { it.section == section }.forEach { tool ->
                item {
                    val locked = tool.requiresAdvanced && !canUseNetworkTools
                    Card(
                        onClick = { onOpen(tool) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (locked) Icons.Default.Lock else tool.icon, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Column(modifier = Modifier.padding(start = 12.dp).weight(1f)) {
                                Text(stringResource(id = tool.titleResId), style = MaterialTheme.typography.titleSmall)
                                Text(
                                    text = stringResource(id = tool.descriptionResId),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                            if (locked) {
                                Text(
                                    stringResource(id = R.string.tools_locked),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ToolScreen(tool: Tool, onBack: () -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(id = R.string.config_back)) }
            Text(stringResource(id = tool.titleResId), style = MaterialTheme.typography.titleLarge)
        }
        val body: @Composable () -> Unit = {
            Column(
                verticalArrangement = Arrangement.spacedBy(12.dp),
                modifier = Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(start = 16.dp, end = 16.dp, bottom = 24.dp)
            ) {
                when (tool) {
                    Tool.TRACEROUTE -> TracerouteTool()
                    Tool.DNS -> DnsTool()
                    Tool.SITE -> SiteTool()
                    Tool.WOL -> WolTool()
                    Tool.MTU -> MtuTool()
                    Tool.SUBNET -> SubnetTool()
                    Tool.FIBER -> FiberTool()
                    Tool.RADIO -> RadioTool()
                    Tool.CCTV -> CctvTool()
                    Tool.POE -> PoeTool()
                    Tool.UPS -> UpsTool()
                    Tool.CONVERTERS -> ConvertersTool()
                    Tool.RJ45 -> Rj45Tool()
                    Tool.PORTS -> PortsTool()
                    Tool.PASSWORD -> PasswordTool()
                }
            }
        }
        if (tool.needsLocalNetwork) br.com.timasters.app.ui.components.LocalNetworkGate { body() } else body()
    }
}

// region Shared pieces

/** Text field that accepts "1,5" or "1.5". */
@Composable
internal fun NumberField(label: String, value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier, decimal: Boolean = true) {
    OutlinedTextField(
        value = value,
        onValueChange = { text -> onValueChange(text.filter { it.isDigit() || (decimal && (it == ',' || it == '.' || it == '-')) }) },
        label = { Text(label) },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = if (decimal) KeyboardType.Decimal else KeyboardType.Number),
        modifier = modifier.fillMaxWidth()
    )
}

@Composable
internal fun TextField(
    label: String,
    value: String,
    onValueChange: (String) -> Unit,
    keyboardType: KeyboardType = KeyboardType.Text,
    singleLine: Boolean = true,
    placeholder: String? = null
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(label) },
        placeholder = placeholder?.let { { Text(it) } },
        singleLine = singleLine,
        minLines = if (singleLine) 1 else 3,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        modifier = Modifier.fillMaxWidth()
    )
}

internal fun String.toNumber(): Double? = replace(',', '.').trim().toDoubleOrNull()

internal fun Double.br(decimals: Int = 1): String = BrFormatters.formatNumber(this, decimals)

@Composable
internal fun Running(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 8.dp)) {
        CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(20.dp))
        Text(text, modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
internal fun ToolErrorCard(error: ToolFailure, detail: String = "") {
    MessageCard(
        text = when (error) {
            ToolFailure.INVALID_INPUT -> stringResource(id = R.string.tools_error_invalid)
            ToolFailure.UNKNOWN_HOST -> stringResource(id = R.string.tools_error_unknown_host)
            ToolFailure.TIMEOUT -> stringResource(id = R.string.tools_error_timeout)
            ToolFailure.NO_NETWORK -> stringResource(id = R.string.tools_error_network)
            ToolFailure.FAILED -> stringResource(id = R.string.tools_error_failed, detail)
        },
        isError = true
    )
}

@Composable
internal fun CopyButton(text: String) {
    val clipboard = LocalClipboard.current
    val scope = rememberCoroutineScope()
    val label = stringResource(id = R.string.tools_copy)
    IconButton(onClick = { scope.launch { clipboard.setClipEntry(ClipEntry(ClipData.newPlainText(label, text))) } }) {
        Icon(Icons.Default.ContentCopy, contentDescription = label)
    }
}

// endregion
