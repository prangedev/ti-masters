package br.com.timasters.app.ui.components

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import br.com.timasters.app.R

/**
 * Android 17+ blocks connections to devices on the local network (routers, LAN scan, port scan)
 * unless the user grants "Nearby devices / local network". Shows [content] when access is allowed,
 * otherwise a card that explains why and asks for it. Re-checks when the screen resumes.
 */
@Composable
fun LocalNetworkGate(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    if (Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK) {
        content()
        return
    }
    val context = LocalContext.current
    var granted by remember { mutableStateOf(context.hasLocalNetworkAccess()) }
    var permanentlyDenied by rememberSaveable { mutableStateOf(false) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { result ->
        granted = result
        val activity = context.findActivity()
        permanentlyDenied = !result && activity != null &&
            !ActivityCompat.shouldShowRequestPermissionRationale(activity, ACCESS_LOCAL_NETWORK)
    }
    LifecycleResumeEffect(Unit) {
        granted = context.hasLocalNetworkAccess()
        onPauseOrDispose { }
    }
    if (granted) {
        content()
        return
    }
    Card(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.tertiaryContainer)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                text = stringResource(id = R.string.local_network_title),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Text(
                text = stringResource(id = R.string.local_network_text),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onTertiaryContainer
            )
            Button(onClick = {
                if (permanentlyDenied) {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", context.packageName, null)))
                } else {
                    launcher.launch(ACCESS_LOCAL_NETWORK)
                }
            }) {
                Text(stringResource(id = if (permanentlyDenied) R.string.open_app_settings else R.string.local_network_button))
            }
        }
    }
}

/** Android 17 (API 37) is the first version that enforces local network protection. */
private const val LOCAL_NETWORK_SDK = 37
private const val ACCESS_LOCAL_NETWORK = "android.permission.ACCESS_LOCAL_NETWORK"

fun Context.hasLocalNetworkAccess(): Boolean =
    Build.VERSION.SDK_INT < LOCAL_NETWORK_SDK ||
        ContextCompat.checkSelfPermission(this, ACCESS_LOCAL_NETWORK) == PackageManager.PERMISSION_GRANTED

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
