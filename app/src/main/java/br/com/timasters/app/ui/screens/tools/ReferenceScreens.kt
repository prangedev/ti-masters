package br.com.timasters.app.ui.screens.tools

import android.graphics.Bitmap
import androidx.annotation.StringRes
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import br.com.timasters.app.R
import br.com.timasters.app.data.tools.PasswordOptions
import br.com.timasters.app.data.tools.PasswordTools
import br.com.timasters.app.ui.components.SectionCard
import br.com.timasters.app.ui.viewmodel.ToolsViewModel
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import kotlin.math.roundToInt

// region RJ45

/** Wire colors: base color plus whether it's the white/striped wire of the pair. */
private enum class Wire(@param:StringRes val nameResId: Int, val color: Color, val striped: Boolean) {
    WHITE_ORANGE(R.string.color_white_orange, Color(0xFFF57C00), true),
    ORANGE(R.string.color_orange, Color(0xFFF57C00), false),
    WHITE_GREEN(R.string.color_white_green, Color(0xFF2E7D32), true),
    BLUE(R.string.color_blue, Color(0xFF1565C0), false),
    WHITE_BLUE(R.string.color_white_blue, Color(0xFF1565C0), true),
    GREEN(R.string.color_green, Color(0xFF2E7D32), false),
    WHITE_BROWN(R.string.color_white_brown, Color(0xFF6D4C41), true),
    BROWN(R.string.color_brown, Color(0xFF6D4C41), false)
}

private val T568B = listOf(Wire.WHITE_ORANGE, Wire.ORANGE, Wire.WHITE_GREEN, Wire.BLUE, Wire.WHITE_BLUE, Wire.GREEN, Wire.WHITE_BROWN, Wire.BROWN)
private val T568A = listOf(Wire.WHITE_GREEN, Wire.GREEN, Wire.WHITE_ORANGE, Wire.BLUE, Wire.WHITE_BLUE, Wire.ORANGE, Wire.WHITE_BROWN, Wire.BROWN)

@Composable
internal fun Rj45Tool() {
    SectionCard(title = stringResource(id = R.string.rj45_t568b)) { Pinout(T568B) }
    SectionCard(title = stringResource(id = R.string.rj45_t568a)) { Pinout(T568A) }
    listOf(R.string.rj45_straight, R.string.rj45_crossover, R.string.rj45_poe).forEach {
        Text(stringResource(id = it), style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Pinout(wires: List<Wire>) {
    wires.forEachIndexed { index, wire ->
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(vertical = 2.dp)) {
            Text(stringResource(id = R.string.rj45_pin, index + 1), modifier = Modifier.width(64.dp))
            WireSwatch(wire)
            Text(stringResource(id = wire.nameResId), modifier = Modifier.padding(start = 12.dp), style = MaterialTheme.typography.bodyMedium)
        }
    }
}

/** Solid bar for plain wires, white bar with a colored stripe for the white wire of each pair. */
@Composable
private fun WireSwatch(wire: Wire) {
    val shape = RoundedCornerShape(4.dp)
    Box(
        modifier = Modifier
            .size(width = 56.dp, height = 18.dp)
            .background(if (wire.striped) Color.White else wire.color, shape)
            .border(1.dp, MaterialTheme.colorScheme.outline, shape),
        contentAlignment = Alignment.Center
    ) {
        if (wire.striped) Box(modifier = Modifier.fillMaxWidth().height(6.dp).background(wire.color))
    }
}

// endregion

// region Ports

@Composable
internal fun PortsTool(viewModel: ToolsViewModel = hiltViewModel()) {
    LaunchedEffect(Unit) { viewModel.loadPorts() }
    val ports by viewModel.ports.collectAsStateWithLifecycle()
    var query by rememberSaveable { mutableStateOf("") }
    TextField(stringResource(id = R.string.ports_search), query, { query = it })
    val words = query.lowercase().split(' ').filter { it.isNotBlank() }
    ports.filter { entry -> words.all { word -> "${entry.port} ${entry.proto} ${entry.service} ${entry.notes}".lowercase().contains(word) } }
        .forEachIndexed { index, entry ->
            if (index > 0) HorizontalDivider()
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Column(modifier = Modifier.width(96.dp)) {
                    Text(entry.port, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                    Text(entry.proto, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Column(modifier = Modifier.weight(1f)) {
                    Text(entry.service, style = MaterialTheme.typography.titleSmall)
                    Text(entry.notes, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
}

// endregion

// region Passwords and Wi-Fi QR

@Composable
internal fun PasswordTool() {
    var length by rememberSaveable { mutableStateOf(16f) }
    var lower by rememberSaveable { mutableStateOf(true) }
    var upper by rememberSaveable { mutableStateOf(true) }
    var digits by rememberSaveable { mutableStateOf(true) }
    var symbols by rememberSaveable { mutableStateOf(true) }
    var avoidAmbiguous by rememberSaveable { mutableStateOf(true) }
    var password by rememberSaveable { mutableStateOf("") }
    var ssid by rememberSaveable { mutableStateOf("") }
    var wifiPassword by rememberSaveable { mutableStateOf("") }
    var hidden by rememberSaveable { mutableStateOf(false) }
    val anySet = lower || upper || digits || symbols

    SectionCard(title = stringResource(id = R.string.password_generate)) {
        Text(stringResource(id = R.string.password_length, length.roundToInt()))
        Slider(value = length, onValueChange = { length = it }, valueRange = 8f..40f, steps = 31)
        Option(R.string.password_lower, lower) { lower = it }
        Option(R.string.password_upper, upper) { upper = it }
        Option(R.string.password_digits, digits) { digits = it }
        Option(R.string.password_symbols, symbols) { symbols = it }
        Option(R.string.password_ambiguous, avoidAmbiguous) { avoidAmbiguous = it }
        Button(
            onClick = { password = PasswordTools.generate(PasswordOptions(length.roundToInt(), lower, upper, digits, symbols, avoidAmbiguous)) },
            enabled = anySet,
            modifier = Modifier.fillMaxWidth()
        ) { Text(stringResource(id = R.string.password_generate)) }
        if (password.isNotEmpty()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(password, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                CopyButton(password)
            }
            val bits = PasswordTools.entropyBits(password).roundToInt()
            Text(
                stringResource(id = R.string.password_strength, bits) + " · " +
                    stringResource(id = if (bits >= 60) R.string.password_strong else R.string.password_weak),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    SectionCard(title = stringResource(id = R.string.qr_title)) {
        Text(stringResource(id = R.string.qr_hint), style = MaterialTheme.typography.bodySmall)
        TextField(stringResource(id = R.string.qr_ssid), ssid, { ssid = it })
        TextField(stringResource(id = R.string.qr_password), wifiPassword, { wifiPassword = it })
        if (password.isNotEmpty()) {
            OutlinedButton(onClick = { wifiPassword = password }, modifier = Modifier.fillMaxWidth()) { Text(stringResource(id = R.string.qr_use_generated)) }
        }
        Option(R.string.qr_hidden, hidden) { hidden = it }
        if (ssid.isNotBlank()) {
            val content = PasswordTools.wifiQr(ssid, wifiPassword, hidden)
            val bitmap = remember(content) { qrBitmap(content) }
            Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                Image(bitmap = bitmap.asImageBitmap(), contentDescription = stringResource(id = R.string.qr_title), modifier = Modifier.size(240.dp))
            }
        }
    }
}

@Composable
private fun Option(@StringRes label: Int, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = checked, onCheckedChange = onChange)
        Text(stringResource(id = label), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Black-on-white QR (works in dark mode too, since it's drawn as an image with its own background). */
private fun qrBitmap(content: String, size: Int = 512): Bitmap {
    val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, mapOf(EncodeHintType.MARGIN to 2, EncodeHintType.CHARACTER_SET to "UTF-8"))
    val pixels = IntArray(size * size) { index -> if (matrix[index % size, index / size]) 0xFF000000.toInt() else 0xFFFFFFFF.toInt() }
    return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
}

// endregion
