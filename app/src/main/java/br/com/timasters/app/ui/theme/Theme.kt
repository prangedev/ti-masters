package br.com.timasters.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = PrimaryBlueDark,
    secondary = SecondaryTealDark,
    tertiary = TertiaryAmberDark,
    background = BackgroundDark,
    surface = SurfaceDark
)

private val LightColorScheme = lightColorScheme(
    primary = PrimaryBlue,
    secondary = SecondaryTeal,
    tertiary = TertiaryAmber,
    background = BackgroundLight,
    surface = SurfaceLight
)

@Composable
fun TI_MASTERSTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    // Off by default: wallpaper colors would override the TI Masters and white-label brand colors.
    dynamicColor: Boolean = false,
    /** Business accounts' own color (ARGB); replaces the TI Masters blue as the primary color. */
    brandColor: Int? = null,
    content: @Composable () -> Unit
) {
    val baseScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        darkTheme -> DarkColorScheme
        else -> LightColorScheme
    }
    val colorScheme = brandColor?.let { baseScheme.withBrand(Color(it), darkTheme) } ?: baseScheme

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography,
        content = content
    )
}

/**
 * Primary colors derived from one brand color: in dark mode it is lightened so text and icons
 * keep enough contrast; text on it is black or white, whichever reads better.
 */
private fun ColorScheme.withBrand(brand: Color, darkTheme: Boolean): ColorScheme {
    val primary = if (darkTheme) lerp(brand, Color.White, 0.35f) else brand
    val onPrimary = if (primary.luminance() > 0.45f) Color.Black else Color.White
    val container = if (darkTheme) lerp(brand, Color.Black, 0.55f) else lerp(brand, Color.White, 0.82f)
    val onContainer = if (container.luminance() > 0.45f) lerp(brand, Color.Black, 0.6f) else lerp(brand, Color.White, 0.8f)
    return copy(
        primary = primary,
        onPrimary = onPrimary,
        primaryContainer = container,
        onPrimaryContainer = onContainer,
        inversePrimary = if (darkTheme) brand else lerp(brand, Color.White, 0.35f),
        surfaceTint = primary
    )
}
