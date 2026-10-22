package br.com.timasters.app.ui.navigation

import androidx.annotation.StringRes
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BusinessCenter
import androidx.compose.material.icons.filled.Router
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Wifi
import androidx.compose.ui.graphics.vector.ImageVector
import br.com.timasters.app.R

/** Top-level destinations. An enum so the selected tab survives rotation via rememberSaveable. */
enum class Screen(
    @param:StringRes val titleResId: Int,
    val icon: ImageVector
) {
    Diagnostics(R.string.nav_diagnostics, Icons.Default.Speed),
    Network(R.string.nav_network, Icons.Default.Wifi),
    Routers(R.string.nav_routers, Icons.Default.Router),
    Business(R.string.nav_business, Icons.Default.BusinessCenter),
    Settings(R.string.nav_settings, Icons.Default.Settings)
}
