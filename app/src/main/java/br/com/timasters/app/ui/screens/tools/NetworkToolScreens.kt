package br.com.timasters.app.ui.screens.tools

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.data.tools.CertificateInfo
import br.com.timasters.app.data.tools.DnsType
import br.com.timasters.app.ui.components.InfoRow
import br.com.timasters.app.ui.components.MessageCard
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.screens.business.OptionField
import br.com.timasters.app.ui.viewmodel.ToolState
import br.com.timasters.app.ui.viewmodel.ToolsViewModel

@Composable
internal fun TracerouteTool(viewModel: ToolsViewModel = hiltViewModel()) {
    val state by viewModel.trace.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf("google.com") }
    Text(stringResource(id = R.string.trace_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextField(stringResource(id = R.string.tools_host), host, { host = it }, KeyboardType.Uri)
    if (state.running) {
        OutlinedButton(onClick = viewModel::stopTrace, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.tools_stop)) }
    } else {
        Button(onClick = { viewModel.startTrace(host) }, enabled = host.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.tools_run))
        }
    }
    state.error?.let { ToolErrorCard(it) }
    if (state.hops.isNotEmpty()) {
        SectionCard(title = stringResource(id = R.string.tool_traceroute)) {
            state.hops.forEach { hop ->
                Row(modifier = Modifier.fillMaxWidth()) {
                    Text("${hop.ttl}", fontWeight = FontWeight.Bold, modifier = Modifier.width(32.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(hop.address ?: stringResource(id = R.string.trace_hop_silent), fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium)
                        hop.hostname?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    }
                    hop.timeMs?.let {
                        Text(
                            if (hop.isDestination) "${it.br()} ms" else stringResource(id = R.string.trace_time_approx, it.br(0)),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            if (state.running) Running(stringResource(id = R.string.tool_traceroute))
            state.hops.lastOrNull()?.takeIf { it.isDestination }?.let {
                MessageCard(text = pluralStringResource(id = R.plurals.trace_reached, it.ttl, it.ttl), isError = false)
            }
        }
    }
}

@Composable
internal fun DnsTool(viewModel: ToolsViewModel = hiltViewModel()) {
    val lookup by viewModel.dns.collectAsStateWithLifecycle()
    val benchmark by viewModel.benchmark.collectAsStateWithLifecycle()
    var name by rememberSaveable { mutableStateOf("google.com") }
    var type by rememberSaveable { mutableStateOf(DnsType.A) }
    var server by rememberSaveable { mutableStateOf("") }

    TextField(stringResource(id = R.string.dns_name), name, { name = it }, KeyboardType.Uri)
    OptionField(label = stringResource(id = R.string.dns_type), options = DnsType.entries, selected = type, optionLabel = { it.name }, onSelect = { type = it })
    TextField(stringResource(id = R.string.dns_server), server, { server = it }, KeyboardType.Decimal)
    Button(onClick = { viewModel.lookup(name, type, server) }, enabled = name.isNotBlank() && lookup != ToolState.Running, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(id = R.string.dns_lookup))
    }
    when (val current = lookup) {
        ToolState.Running -> Running(stringResource(id = R.string.dns_lookup))
        is ToolState.Error -> ToolErrorCard(current.kind, current.detail)
        is ToolState.Done -> SectionCard(title = stringResource(id = R.string.dns_result, current.value.server, current.value.timeMs.toInt())) {
            val answer = current.value.answer
            when {
                answer.rcode == 3 -> MessageCard(text = stringResource(id = R.string.dns_nxdomain), isError = true)
                answer.rcode != 0 -> MessageCard(text = stringResource(id = R.string.dns_rcode, answer.rcode), isError = true)
                answer.records.isEmpty() -> Text(stringResource(id = R.string.dns_no_records))
                else -> answer.records.forEach { record ->
                    Row(modifier = Modifier.fillMaxWidth()) {
                        Text(record.type.name, fontWeight = FontWeight.Bold, modifier = Modifier.width(64.dp))
                        Text(record.value, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                        Text("${record.ttl}s", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
        ToolState.Idle -> Unit
    }

    SectionCard(title = stringResource(id = R.string.dns_benchmark)) {
        Text(stringResource(id = R.string.dns_benchmark_hint), style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = viewModel::runBenchmark, enabled = benchmark != ToolState.Running, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(id = R.string.dns_benchmark))
        }
        when (val current = benchmark) {
            ToolState.Running -> Running(stringResource(id = R.string.dns_benchmark_running))
            is ToolState.Error -> ToolErrorCard(current.kind, current.detail)
            is ToolState.Done -> current.value.forEachIndexed { index, score ->
                if (index > 0) HorizontalDivider()
                Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("${index + 1}. ${score.name}", fontWeight = if (index == 0) FontWeight.Bold else FontWeight.Normal)
                        Text(score.address, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.bodySmall)
                    }
                    Column {
                        Text(score.medianMs?.let { "$it ms" } ?: stringResource(id = R.string.dns_benchmark_failed))
                        if (score.failures > 0) Text(stringResource(id = R.string.dns_benchmark_failures, score.failures), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
            ToolState.Idle -> Unit
        }
    }
}

@Composable
internal fun SiteTool(viewModel: ToolsViewModel = hiltViewModel()) {
    val site by viewModel.site.collectAsStateWithLifecycle()
    val certificate by viewModel.certificate.collectAsStateWithLifecycle()
    var url by rememberSaveable { mutableStateOf("") }
    var host by rememberSaveable { mutableStateOf("") }
    var port by rememberSaveable { mutableStateOf("443") }

    TextField(stringResource(id = R.string.site_url), url, { url = it }, KeyboardType.Uri)
    Text(stringResource(id = R.string.site_https_only), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Button(onClick = { viewModel.checkSite(url) }, enabled = url.isNotBlank() && site != ToolState.Running, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(id = R.string.site_check))
    }
    when (val current = site) {
        ToolState.Running -> Running(stringResource(id = R.string.site_check))
        is ToolState.Error -> ToolErrorCard(current.kind, current.detail)
        is ToolState.Done -> SectionCard(title = current.value.url) {
            InfoRow(stringResource(id = R.string.site_status), current.value.status.toString())
            InfoRow(stringResource(id = R.string.site_time), "${current.value.timeMs} ms")
            current.value.server?.let { InfoRow(stringResource(id = R.string.site_server), it) }
            if (current.value.redirects.isNotEmpty()) {
                Text(stringResource(id = R.string.site_redirects), style = MaterialTheme.typography.labelLarge)
                (current.value.redirects + current.value.url).forEach { Text("→ $it", style = MaterialTheme.typography.bodySmall) }
            }
            current.value.certificate?.let { CertificateDetails(it) }
        }
        ToolState.Idle -> Unit
    }

    SectionCard(title = stringResource(id = R.string.cert_title)) {
        Text(stringResource(id = R.string.cert_hint), style = MaterialTheme.typography.bodySmall)
        TextField(stringResource(id = R.string.tools_host), host, { host = it }, KeyboardType.Uri)
        NumberField(stringResource(id = R.string.cert_port), port, { port = it }, decimal = false)
        OutlinedButton(
            onClick = { viewModel.checkCertificate(host, port) },
            enabled = host.isNotBlank() && certificate != ToolState.Running,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(id = R.string.cert_check)) }
        when (val current = certificate) {
            ToolState.Running -> Running(stringResource(id = R.string.cert_check))
            is ToolState.Error -> ToolErrorCard(current.kind, current.detail)
            is ToolState.Done -> CertificateDetails(current.value)
            ToolState.Idle -> Unit
        }
    }
}

@Composable
private fun CertificateDetails(certificate: CertificateInfo) {
    MessageCard(
        text = stringResource(id = if (certificate.trusted) R.string.cert_trusted else R.string.cert_untrusted),
        isError = !certificate.trusted
    )
    InfoRow(stringResource(id = R.string.cert_subject), certificate.subject)
    InfoRow(stringResource(id = R.string.cert_issuer), certificate.issuer)
    InfoRow(
        stringResource(id = R.string.cert_expires),
        BrFormatters.formatDate(certificate.notAfterMillis) + " · " +
            if (certificate.daysLeft < 0) stringResource(id = R.string.cert_expired) else stringResource(id = R.string.cert_days_left, certificate.daysLeft.toInt())
    )
    certificate.tlsVersion?.let { InfoRow(stringResource(id = R.string.cert_tls), it) }
    if (certificate.alternativeNames.isNotEmpty()) InfoRow(stringResource(id = R.string.cert_names), certificate.alternativeNames.joinToString(", "))
    if (certificate.daysLeft in 0..29) MessageCard(text = stringResource(id = R.string.cert_expiring), isError = true)
}

@Composable
internal fun WolTool(viewModel: ToolsViewModel = hiltViewModel()) {
    val state by viewModel.wol.collectAsStateWithLifecycle()
    var mac by rememberSaveable { mutableStateOf("") }
    var broadcast by rememberSaveable { mutableStateOf("") }
    LaunchedEffect(Unit) { if (broadcast.isBlank()) broadcast = viewModel.defaultBroadcast() }
    Text(stringResource(id = R.string.wol_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextField(stringResource(id = R.string.wol_mac), mac, { mac = it })
    TextField(stringResource(id = R.string.wol_broadcast), broadcast, { broadcast = it }, KeyboardType.Decimal)
    Button(onClick = { viewModel.wake(mac, broadcast) }, enabled = mac.isNotBlank() && broadcast.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(id = R.string.wol_send))
    }
    when (val current = state) {
        ToolState.Running -> Running(stringResource(id = R.string.wol_send))
        is ToolState.Error -> ToolErrorCard(current.kind, current.detail)
        is ToolState.Done -> MessageCard(text = stringResource(id = R.string.wol_sent, current.value), isError = false)
        ToolState.Idle -> Unit
    }
}

@Composable
internal fun MtuTool(viewModel: ToolsViewModel = hiltViewModel()) {
    val state by viewModel.mtu.collectAsStateWithLifecycle()
    var host by rememberSaveable { mutableStateOf("1.1.1.1") }
    Text(stringResource(id = R.string.mtu_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    TextField(stringResource(id = R.string.tools_host), host, { host = it }, KeyboardType.Uri)
    Button(onClick = { viewModel.discoverMtu(host) }, enabled = host.isNotBlank() && state != ToolState.Running, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(id = R.string.mtu_run))
    }
    when (val current = state) {
        ToolState.Running -> Running(stringResource(id = R.string.mtu_running))
        is ToolState.Error -> ToolErrorCard(current.kind, current.detail)
        is ToolState.Done -> current.value?.let { MessageCard(text = stringResource(id = R.string.mtu_result, it), isError = false) }
            ?: MessageCard(text = stringResource(id = R.string.mtu_blocked), isError = true)
        ToolState.Idle -> Unit
    }
}
