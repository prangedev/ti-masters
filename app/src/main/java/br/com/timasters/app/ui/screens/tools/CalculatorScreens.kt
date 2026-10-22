package br.com.timasters.app.ui.screens.tools

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.AssistChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import br.com.timasters.app.R
import br.com.timasters.app.data.tools.BudgetStatus
import br.com.timasters.app.data.tools.CameraPreset
import br.com.timasters.app.data.tools.CctvInput
import br.com.timasters.app.data.tools.CctvStorage
import br.com.timasters.app.data.tools.Converters
import br.com.timasters.app.data.tools.FiberBudget
import br.com.timasters.app.data.tools.FiberBudgetInput
import br.com.timasters.app.data.tools.PoeBudget
import br.com.timasters.app.data.tools.PoeDevice
import br.com.timasters.app.data.tools.RadioLink
import br.com.timasters.app.data.tools.RadioLinkInput
import br.com.timasters.app.data.tools.Splitter
import br.com.timasters.app.data.tools.SubnetCalculator
import br.com.timasters.app.data.tools.SubnetInfo
import br.com.timasters.app.data.tools.UpsInput
import br.com.timasters.app.data.tools.UpsRuntime
import br.com.timasters.app.data.tools.VlsmRequest
import br.com.timasters.app.data.tools.Wavelength
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.screens.business.OptionField

@Composable
internal fun SubnetTool() {
    var input by rememberSaveable { mutableStateOf("192.168.0.10/24") }
    var parts by rememberSaveable { mutableStateOf("4") }
    var vlsm by rememberSaveable { mutableStateOf("") }
    val parsed = SubnetCalculator.parse(input)

    TextField(stringResource(id = R.string.subnet_input), input, { input = it }, KeyboardType.Uri)
    if (parsed == null) {
        if (input.isNotBlank()) ToolErrorCard(br.com.timasters.app.ui.viewmodel.ToolFailure.INVALID_INPUT)
        return
    }
    val info = SubnetCalculator.calculate(parsed.first, parsed.second)
    SectionCard(title = info.cidr) { SubnetDetails(info) }

    SectionCard(title = stringResource(id = R.string.subnet_split)) {
        NumberField(stringResource(id = R.string.subnet_split), parts, { parts = it }, decimal = false)
        val split = parts.toIntOrNull()?.let { SubnetCalculator.split(parsed.first, parsed.second, it) }
        when {
            parts.isBlank() -> Unit
            split == null -> MessageCard(text = stringResource(id = R.string.subnet_split_impossible), isError = true)
            else -> split.forEach { subnet ->
                Text("${subnet.cidr}  ·  ${subnet.firstHost} – ${subnet.lastHost}", fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    SectionCard(title = stringResource(id = R.string.subnet_vlsm)) {
        TextField(
            label = stringResource(id = R.string.subnet_vlsm_label),
            value = vlsm,
            onValueChange = { vlsm = it },
            singleLine = false,
            placeholder = stringResource(id = R.string.subnet_vlsm_example)
        )
        val requests = vlsm.lines().mapNotNull { line ->
            val hosts = line.trim().substringAfterLast(' ').toIntOrNull() ?: return@mapNotNull null
            VlsmRequest(line.trim().substringBeforeLast(' ').ifBlank { "Rede" }, hosts)
        }
        if (requests.isNotEmpty()) {
            val result = SubnetCalculator.vlsm(info.network, info.prefix, requests)
            if (result == null) {
                MessageCard(text = stringResource(id = R.string.subnet_vlsm_impossible), isError = true)
            } else {
                result.forEachIndexed { index, allocation ->
                    if (index > 0) HorizontalDivider()
                    Text(stringResource(id = R.string.subnet_vlsm_row, allocation.request.name, allocation.request.hosts), fontWeight = FontWeight.Bold)
                    Text(
                        "${allocation.subnet.cidr}  ·  ${allocation.subnet.firstHost} – ${allocation.subnet.lastHost}  ·  ${allocation.subnet.mask}",
                        fontFamily = FontFamily.Monospace,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }
    }
}

@Composable
private fun SubnetDetails(info: SubnetInfo) {
    InfoRow(stringResource(id = R.string.subnet_network), info.network)
    InfoRow(stringResource(id = R.string.subnet_broadcast), info.broadcast)
    InfoRow(stringResource(id = R.string.subnet_mask), "${info.mask} (/${info.prefix})")
    InfoRow(stringResource(id = R.string.subnet_wildcard), info.wildcard)
    InfoRow(stringResource(id = R.string.subnet_range), "${info.firstHost} – ${info.lastHost}")
    InfoRow(stringResource(id = R.string.subnet_hosts), info.usableHosts.toString())
    Text(stringResource(id = if (info.isPrivate) R.string.subnet_private else R.string.subnet_public), style = MaterialTheme.typography.bodySmall)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun FiberTool() {
    var tx by rememberSaveable { mutableStateOf("3") }
    var length by rememberSaveable { mutableStateOf("5") }
    var wavelength by rememberSaveable { mutableStateOf(Wavelength.NM1490) }
    var connectors by rememberSaveable { mutableStateOf("4") }
    var splices by rememberSaveable { mutableStateOf("2") }
    var splitters by rememberSaveable { mutableStateOf(listOf(Splitter.S1X8, Splitter.S1X8)) }
    var sensitivity by rememberSaveable { mutableStateOf("-27") }

    NumberField(stringResource(id = R.string.fiber_tx), tx, { tx = it })
    NumberField(stringResource(id = R.string.fiber_length), length, { length = it })
    OptionField(label = stringResource(id = R.string.fiber_wavelength), options = Wavelength.entries, selected = wavelength, optionLabel = { "${it.nm} nm (${it.dbPerKm.br(2)} dB/km)" }, onSelect = { wavelength = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(id = R.string.fiber_connectors), connectors, { connectors = it }, Modifier.weight(1f), decimal = false)
        NumberField(stringResource(id = R.string.fiber_splices), splices, { splices = it }, Modifier.weight(1f), decimal = false)
    }
    Text(stringResource(id = R.string.fiber_splitters), style = MaterialTheme.typography.labelLarge)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Splitter.entries.forEach { splitter -> AssistChip(onClick = { splitters = splitters + splitter }, label = { Text("+ ${splitter.label}") }) }
    }
    if (splitters.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(splitters.joinToString(" + ") { it.label }, modifier = Modifier.weight(1f))
            TextButton(onClick = { splitters = emptyList() }) { Text(stringResource(id = R.string.fiber_clear_splitters)) }
        }
    }
    NumberField(stringResource(id = R.string.fiber_sensitivity), sensitivity, { sensitivity = it })

    val km = length.toNumber() ?: return
    val result = FiberBudget.calculate(
        FiberBudgetInput(
            txDbm = tx.toNumber() ?: 3.0,
            lengthKm = km,
            wavelength = wavelength,
            connectors = connectors.toIntOrNull() ?: 0,
            splices = splices.toIntOrNull() ?: 0,
            splitters = splitters,
            rxSensitivityDbm = sensitivity.toNumber() ?: -27.0
        )
    )
    SectionCard(title = stringResource(id = R.string.tool_fiber)) {
        InfoRow(stringResource(id = R.string.fiber_total_loss), "${result.totalLoss.br(2)} dB")
        Text(
            stringResource(id = R.string.fiber_breakdown, result.fiberLoss.br(2), result.connectorsLoss.br(2), result.splicesLoss.br(2), result.splittersLoss.br(2)),
            style = MaterialTheme.typography.bodySmall
        )
        InfoRow(stringResource(id = R.string.fiber_expected_rx), "${result.expectedRxDbm.br(2)} dBm")
        InfoRow(stringResource(id = R.string.fiber_margin), "${result.marginDb.br(2)} dB")
        MessageCard(
            text = stringResource(
                id = when (result.status) {
                    BudgetStatus.OK -> R.string.fiber_ok
                    BudgetStatus.AT_LIMIT -> R.string.fiber_limit
                    BudgetStatus.FAIL -> R.string.fiber_fail
                    BudgetStatus.OVERLOAD -> R.string.fiber_overload
                }
            ),
            isError = result.status != BudgetStatus.OK
        )
    }
}

@Composable
internal fun RadioTool() {
    var frequency by rememberSaveable { mutableStateOf("5,8") }
    var distance by rememberSaveable { mutableStateOf("5") }
    var tx by rememberSaveable { mutableStateOf("20") }
    var gainA by rememberSaveable { mutableStateOf("23") }
    var gainB by rememberSaveable { mutableStateOf("23") }
    var cable by rememberSaveable { mutableStateOf("0") }
    var sensitivity by rememberSaveable { mutableStateOf("-80") }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(id = R.string.radio_frequency), frequency, { frequency = it }, Modifier.weight(1f))
        NumberField(stringResource(id = R.string.radio_distance), distance, { distance = it }, Modifier.weight(1f))
    }
    NumberField(stringResource(id = R.string.radio_tx), tx, { tx = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(id = R.string.radio_gain_tx), gainA, { gainA = it }, Modifier.weight(1f))
        NumberField(stringResource(id = R.string.radio_gain_rx), gainB, { gainB = it }, Modifier.weight(1f))
    }
    NumberField(stringResource(id = R.string.radio_cable), cable, { cable = it })
    NumberField(stringResource(id = R.string.radio_sensitivity), sensitivity, { sensitivity = it })

    val f = frequency.toNumber()?.takeIf { it > 0 } ?: return
    val d = distance.toNumber()?.takeIf { it > 0 } ?: return
    val result = RadioLink.calculate(
        RadioLinkInput(f, d, tx.toNumber() ?: 0.0, gainA.toNumber() ?: 0.0, gainB.toNumber() ?: 0.0, cable.toNumber() ?: 0.0, sensitivity.toNumber() ?: -80.0)
    )
    SectionCard(title = stringResource(id = R.string.tool_radio)) {
        InfoRow(stringResource(id = R.string.radio_fspl), "${result.freeSpaceLossDb.br(1)} dB")
        InfoRow(stringResource(id = R.string.radio_rssi), "${result.expectedRssiDbm.br(1)} dBm")
        InfoRow(stringResource(id = R.string.radio_margin), "${result.fadeMarginDb.br(1)} dB")
        InfoRow(stringResource(id = R.string.radio_fresnel), stringResource(id = R.string.radio_meters, result.fresnelRadiusM.br(1)))
        InfoRow(stringResource(id = R.string.radio_clearance), stringResource(id = R.string.radio_meters, result.minClearanceM.br(1)))
        val good = result.fadeMarginDb >= 15
        MessageCard(text = stringResource(id = if (good) R.string.radio_good else R.string.radio_weak), isError = !good)
    }
}

@Composable
internal fun CctvTool() {
    var cameras by rememberSaveable { mutableStateOf("8") }
    var preset by rememberSaveable { mutableStateOf(CameraPreset.FHD_1080) }
    var h265 by rememberSaveable { mutableStateOf(true) }
    var bitrate by rememberSaveable { mutableStateOf(CameraPreset.FHD_1080.h265Mbps.br(1)) }
    var hours by rememberSaveable { mutableStateOf("24") }
    var days by rememberSaveable { mutableStateOf("30") }
    var percent by rememberSaveable { mutableStateOf("100") }
    var disk by rememberSaveable { mutableStateOf("4") }
    fun applyPreset() {
        bitrate = (if (h265) preset.h265Mbps else preset.h264Mbps).br(1)
    }

    NumberField(stringResource(id = R.string.cctv_cameras), cameras, { cameras = it }, decimal = false)
    OptionField(label = stringResource(id = R.string.cctv_preset), options = CameraPreset.entries, selected = preset, optionLabel = { it.label }, onSelect = {
        preset = it
        applyPreset()
    })
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(stringResource(id = R.string.cctv_codec_h265), modifier = Modifier.weight(1f))
        Switch(checked = h265, onCheckedChange = {
            h265 = it
            applyPreset()
        })
    }
    NumberField(stringResource(id = R.string.cctv_bitrate), bitrate, { bitrate = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(id = R.string.cctv_hours), hours, { hours = it }, Modifier.weight(1f))
        NumberField(stringResource(id = R.string.cctv_days), days, { days = it }, Modifier.weight(1f), decimal = false)
    }
    NumberField(stringResource(id = R.string.cctv_percent), percent, { percent = it })

    val input = CctvInput(
        cameras = cameras.toIntOrNull() ?: return,
        bitrateMbps = bitrate.toNumber() ?: return,
        hoursPerDay = hours.toNumber()?.coerceIn(0.0, 24.0) ?: 24.0,
        days = days.toIntOrNull() ?: 30,
        recordingPercent = percent.toNumber()?.coerceIn(1.0, 100.0) ?: 100.0
    )
    val result = CctvStorage.calculate(input)
    SectionCard(title = stringResource(id = R.string.tool_cctv)) {
        InfoRow(stringResource(id = R.string.cctv_required), "${result.requiredTb.br(2)} TB")
        InfoRow(stringResource(id = R.string.cctv_daily), "${result.dailyGb.br(1)} GB")
        result.suggestedDiskTb?.let { InfoRow(stringResource(id = R.string.cctv_suggested), "$it TB") }
            ?: MessageCard(text = stringResource(id = R.string.cctv_too_big), isError = true)
        NumberField(stringResource(id = R.string.cctv_disk), disk, { disk = it })
        disk.toNumber()?.let { Text(stringResource(id = R.string.cctv_days_fit, CctvStorage.daysFor(it, input).br(0))) }
        Text(stringResource(id = R.string.cctv_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun PoeTool() {
    var budget by rememberSaveable { mutableStateOf("120") }
    var names by rememberSaveable { mutableStateOf(listOf("Câmera IP", "Access point")) }
    var counts by rememberSaveable { mutableStateOf(listOf("8", "2")) }
    var watts by rememberSaveable { mutableStateOf(listOf("6,5", "12")) }

    NumberField(stringResource(id = R.string.poe_budget), budget, { budget = it })
    names.indices.forEach { index ->
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            androidx.compose.foundation.layout.Box(modifier = Modifier.weight(2f)) {
                TextField(stringResource(id = R.string.poe_device), names[index], { value -> names = names.toMutableList().also { it[index] = value } })
            }
            NumberField(stringResource(id = R.string.poe_count), counts[index], { value -> counts = counts.toMutableList().also { it[index] = value } }, Modifier.weight(1f), decimal = false)
            NumberField(stringResource(id = R.string.poe_watts), watts[index], { value -> watts = watts.toMutableList().also { it[index] = value } }, Modifier.weight(1f))
            IconButton(onClick = {
                names = names.filterIndexed { i, _ -> i != index }
                counts = counts.filterIndexed { i, _ -> i != index }
                watts = watts.filterIndexed { i, _ -> i != index }
            }) { Icon(Icons.Default.Close, contentDescription = null) }
        }
    }
    OutlinedButton(onClick = {
        names = names + ""
        counts = counts + "1"
        watts = watts + ""
    }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.poe_add)) }

    val devices = names.indices.mapNotNull { i ->
        val count = counts[i].toIntOrNull() ?: return@mapNotNull null
        val w = watts[i].toNumber() ?: return@mapNotNull null
        PoeDevice(names[i], count, w)
    }
    val result = PoeBudget.calculate(budget.toNumber() ?: 0.0, devices)
    SectionCard(title = stringResource(id = R.string.tool_poe)) {
        InfoRow(stringResource(id = R.string.poe_total), "${result.totalWatts.br(1)} W")
        InfoRow(stringResource(id = R.string.poe_remaining), "${result.remainingWatts.br(1)} W")
        InfoRow(stringResource(id = R.string.poe_usage), "${result.usagePercent.br(0)}%")
        MessageCard(
            text = stringResource(id = when {
                !result.fits -> R.string.poe_over
                result.comfortable -> R.string.poe_ok
                else -> R.string.poe_tight
            }),
            isError = !result.comfortable
        )
        Text(stringResource(id = R.string.poe_standards), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun UpsTool() {
    var load by rememberSaveable { mutableStateOf("60") }
    var ah by rememberSaveable { mutableStateOf("7") }
    var volts by rememberSaveable { mutableStateOf("12") }
    var batteries by rememberSaveable { mutableStateOf("1") }
    var capacity by rememberSaveable { mutableStateOf("") }

    NumberField(stringResource(id = R.string.ups_load), load, { load = it })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        NumberField(stringResource(id = R.string.ups_battery_ah), ah, { ah = it }, Modifier.weight(1f))
        NumberField(stringResource(id = R.string.ups_battery_v), volts, { volts = it }, Modifier.weight(1f))
    }
    NumberField(stringResource(id = R.string.ups_batteries), batteries, { batteries = it }, decimal = false)
    NumberField(stringResource(id = R.string.ups_capacity), capacity, { capacity = it })

    val watts = load.toNumber()?.takeIf { it > 0 } ?: return
    val result = UpsRuntime.calculate(
        UpsInput(watts, ah.toNumber() ?: return, volts.toNumber() ?: 12.0, batteries.toIntOrNull() ?: 1, upsCapacityVa = capacity.toNumber())
    )
    SectionCard(title = stringResource(id = R.string.tool_ups)) {
        InfoRow(stringResource(id = R.string.ups_runtime), stringResource(id = R.string.ups_minutes, result.runtimeMinutes.br(0)))
        result.loadPercent?.let { InfoRow(stringResource(id = R.string.ups_load_percent), "${it.br(0)}%") }
        if (result.overloaded) MessageCard(text = stringResource(id = R.string.ups_overloaded), isError = true)
        Text(stringResource(id = R.string.ups_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun ConvertersTool() {
    var dbm by rememberSaveable { mutableStateOf("20") }
    var mw by rememberSaveable { mutableStateOf("100") }
    var mbps by rememberSaveable { mutableStateOf("300") }
    var size by rememberSaveable { mutableStateOf("4") }

    SectionCard(title = "dBm ↔ mW") {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            NumberField(stringResource(id = R.string.conv_dbm), dbm, { value ->
                dbm = value
                value.toNumber()?.let { mw = Converters.dbmToMw(it).br(3) }
            }, Modifier.weight(1f))
            NumberField(stringResource(id = R.string.conv_mw), mw, { value ->
                mw = value
                value.toNumber()?.takeIf { it > 0 }?.let { dbm = Converters.mwToDbm(it).br(2) }
            }, Modifier.weight(1f))
        }
    }
    SectionCard(title = "Mbps ↔ MB/s") {
        NumberField(stringResource(id = R.string.conv_mbps), mbps, { mbps = it })
        val speed = mbps.toNumber()
        speed?.let { Text(stringResource(id = R.string.conv_mbytes, Converters.mbpsToMegabytesPerSecond(it).br(1))) }
        NumberField(stringResource(id = R.string.conv_size), size, { size = it })
        val seconds = size.toNumber()?.let { gb -> speed?.let { Converters.transferSeconds(gb, it) } }
        seconds?.takeIf { it.isFinite() }?.let { Text(stringResource(id = R.string.conv_time, formatDuration(it))) }
    }
}

private fun formatDuration(seconds: Double): String {
    val total = seconds.toLong()
    val h = total / 3600
    val m = total % 3600 / 60
    val s = total % 60
    return when {
        h > 0 -> "${h}h ${m}min"
        m > 0 -> "${m}min ${s}s"
        else -> "${s}s"
    }
}

