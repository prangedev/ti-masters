package br.com.timasters.app.ui.screens.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.data.update.ManualCheckResult
import br.com.timasters.app.ui.viewmodel.UpdateViewModel

/** Installed version and a manual "check for updates" button (test channel only). */
@Composable
fun AppVersionSection(viewModel: UpdateViewModel = hiltViewModel()) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(20.dp)) {
            Text(text = stringResource(id = R.string.app_version_title), style = MaterialTheme.typography.titleMedium)
            Text(
                text = stringResource(id = R.string.app_version_installed, viewModel.installedVersionName),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 4.dp)
            )
            if (!viewModel.isSupported) return@Column

            val checking = state.manualCheck == ManualCheckResult.CHECKING
            OutlinedButton(
                onClick = viewModel::checkNow,
                enabled = !checking,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
            ) {
                if (checking) {
                    CircularProgressIndicator(strokeWidth = 2.dp, modifier = Modifier.size(18.dp))
                } else {
                    Text(text = stringResource(id = R.string.app_version_check))
                }
            }
            val message = when (state.manualCheck) {
                ManualCheckResult.UP_TO_DATE -> stringResource(id = R.string.app_version_up_to_date)
                ManualCheckResult.FAILED -> stringResource(id = R.string.app_version_check_failed)
                ManualCheckResult.FOUND -> state.available?.let { stringResource(id = R.string.app_version_found, it.versionName) }
                else -> null
            }
            message?.let {
                Text(
                    text = it,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.manualCheck == ManualCheckResult.FAILED) {
                        MaterialTheme.colorScheme.error
                    } else {
                        MaterialTheme.colorScheme.onSurfaceVariant
                    },
                    modifier = Modifier.padding(top = 8.dp)
                )
            }
        }
    }
}
