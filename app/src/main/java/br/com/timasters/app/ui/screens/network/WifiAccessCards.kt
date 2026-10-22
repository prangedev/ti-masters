package br.com.timasters.app.ui.screens.network

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import br.com.timasters.app.R
import br.com.timasters.app.data.wifi.WifiAccess

/**
 * Explains and fixes whatever blocks Wi-Fi names and scans: location permission (Android requires
 * it to read Wi-Fi data), the phone's location switch, or Wi-Fi itself. Re-checks on resume.
 */
@Composable
fun WifiAccessCards(
    access: WifiAccess,
    onAccessMayHaveChanged: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { results ->
        onAccessMayHaveChanged()
        val granted = results[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val activity = context.findActivity()
        permanentlyDenied = !granted && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.ACCESS_FINE_LOCATION)
    }

    LifecycleResumeEffect(Unit) {
        onAccessMayHaveChanged()
        onPauseOrDispose { }
    }

    Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(12.dp)) {
        when {
            !access.hasLocationPermission -> AccessCard(
                title = stringResource(id = R.string.wifi_permission_title),
                text = stringResource(id = R.string.wifi_permission_text),
                button = stringResource(
                    id = if (permanentlyDenied) R.string.open_app_settings else R.string.wifi_permission_button
                ),
                onClick = {
                    if (permanentlyDenied) {
                        context.startActivity(
                            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null))
                        )
                    } else {
                        permissionLauncher.launch(
                            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
                        )
                    }
                }
            )
            !access.isLocationEnabled -> AccessCard(
                title = stringResource(id = R.string.wifi_location_off_title),
                text = stringResource(id = R.string.wifi_location_off_text),
                button = stringResource(id = R.string.wifi_location_off_button),
                onClick = { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
            )
            !access.isWifiEnabled -> AccessCard(
                title = stringResource(id = R.string.wifi_off_title),
                text = stringResource(id = R.string.wifi_off_text),
                button = stringResource(id = R.string.wifi_off_button),
                onClick = {
                    val action = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        Settings.Panel.ACTION_WIFI
                    } else {
                        Settings.ACTION_WIFI_SETTINGS
                    }
                    context.startActivity(Intent(action))
                }
            )
        }
    }
}

@Composable
private fun AccessCard(title: String, text: String, button: String, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(text = title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Text(text = text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onTertiaryContainer)
            Button(onClick = onClick) { Text(button) }
        }
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
