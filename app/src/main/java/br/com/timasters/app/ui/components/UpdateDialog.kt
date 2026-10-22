package br.com.timasters.app.ui.components

import android.content.ActivityNotFoundException
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.SystemUpdate
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.core.format.BrFormatters
import br.com.timasters.app.data.update.UpdateFailure
import br.com.timasters.app.data.update.UpdatePhase
import br.com.timasters.app.ui.viewmodel.UpdateViewModel

/** Asks to install a newer version; shown over the whole app whenever one is available. */
@Composable
fun UpdateDialog(viewModel: UpdateViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val release = state.available ?: return
    if (!state.showDialog(viewModel.installedVersionCode)) return
    val context = LocalContext.current
    val mandatory = release.isMandatoryFor(viewModel.installedVersionCode)
    val phase = state.phase

    // Opens Android's installer as soon as the verified APK is ready.
    LaunchedEffect((phase as? UpdatePhase.ReadyToInstall)?.apkPath) {
        val ready = phase as? UpdatePhase.ReadyToInstall ?: return@LaunchedEffect
        runCatching { context.startActivity(viewModel.installIntent(ready.apkPath)) }
    }

    AlertDialog(
        onDismissRequest = { if (!mandatory && phase !is UpdatePhase.Downloading) viewModel.postpone() },
        properties = DialogProperties(dismissOnClickOutside = false),
        icon = { Icon(Icons.Default.SystemUpdate, contentDescription = null) },
        title = { Text(stringResource(id = R.string.update_title)) },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text(
                    text = stringResource(
                        id = R.string.update_versions,
                        release.versionName,
                        viewModel.installedVersionName,
                        BrFormatters.formatNumber(release.sizeBytes / 1_048_576.0)
                    ),
                    style = MaterialTheme.typography.bodyMedium
                )
                if (mandatory) {
                    Text(
                        text = stringResource(id = R.string.update_mandatory),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 8.dp)
                    )
                }
                if (release.notes.isNotBlank()) {
                    Text(
                        text = stringResource(id = R.string.update_whats_new),
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp)
                    )
                    Text(
                        text = release.notes.trim(),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.heightIn(max = 220.dp)
                    )
                }
                PhaseDetails(phase)
            }
        },
        confirmButton = {
            when (phase) {
                is UpdatePhase.Downloading -> Button(onClick = {}, enabled = false) {
                    Text(stringResource(id = R.string.update_downloading, (phase.progress * 100).toInt()))
                }
                UpdatePhase.NeedsPermission -> Button(onClick = {
                    try {
                        context.startActivity(viewModel.installPermissionIntent())
                    } catch (_: ActivityNotFoundException) {
                    }
                }) { Text(stringResource(id = R.string.update_allow)) }
                is UpdatePhase.ReadyToInstall -> Button(onClick = {
                    runCatching { context.startActivity(viewModel.installIntent(phase.apkPath)) }
                }) { Text(stringResource(id = R.string.update_install)) }
                is UpdatePhase.Failed -> Button(onClick = viewModel::startUpdate) {
                    Text(stringResource(id = R.string.update_retry))
                }
                UpdatePhase.Idle -> Button(onClick = viewModel::startUpdate) {
                    Text(stringResource(id = R.string.update_now))
                }
            }
        },
        dismissButton = if (mandatory) null else {
            { TextButton(onClick = viewModel::postpone) { Text(stringResource(id = R.string.update_later)) } }
        }
    )
}

@Composable
private fun PhaseDetails(phase: UpdatePhase) {
    when (phase) {
        is UpdatePhase.Downloading -> LinearProgressIndicator(
            progress = { phase.progress },
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp)
        )
        UpdatePhase.NeedsPermission -> Text(
            text = stringResource(id = R.string.update_permission_explain),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp)
        )
        is UpdatePhase.ReadyToInstall -> Text(
            text = stringResource(id = R.string.update_ready),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 12.dp)
        )
        is UpdatePhase.Failed -> Text(
            text = stringResource(
                id = when (phase.reason) {
                    UpdateFailure.NETWORK -> R.string.update_failed_network
                    UpdateFailure.CORRUPTED -> R.string.update_failed_corrupted
                    UpdateFailure.STORAGE -> R.string.update_failed_storage
                }
            ),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.padding(top = 12.dp)
        )
        UpdatePhase.Idle -> Unit
    }
}
