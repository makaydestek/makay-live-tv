package com.makay.livetv.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import com.makay.livetv.data.AppSettingsStore

/**
 * TV icin optimize edilmis palet.
 *
 * TV arayuzu = izleme deneyimi. Bu yuzden tema **her zaman koyu** agirlikli
 * ve yuksek kontrastli: uzaktan kumanda ile 2-3 metreden okunabilirlik,
 * OLED panellerde siyah zemin ve yayin uzerine binen bindirmeler icin
 * yari saydam yuzeyler.
 */
val MakayPrimary = Color(0xFF3F8CFF)
val MakaySecondary = Color(0xFF2EB050)
val MakayTertiary = Color(0xFFFF9F0A)
val MakayError = Color(0xFFFF453A)

/** Bindirme (overlay) yuzeyleri — yayin goruntusunun uzerinde. */
val OverlayScrim = Color(0xCC000000)
val OverlaySurface = Color(0xF21A1D24)
val OverlaySurfaceHigh = Color(0xFF262A33)

private val DarkColorScheme = darkColorScheme(
    primary = MakayPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFF1A3A7A),
    onPrimaryContainer = Color(0xFFD6E4FF),
    secondary = MakaySecondary,
    onSecondary = Color.White,
    secondaryContainer = Color(0xFF1A4A2A),
    onSecondaryContainer = Color(0xFFC8F0D4),
    tertiary = MakayTertiary,
    onTertiary = Color.Black,
    tertiaryContainer = Color(0xFF5C3500),
    onTertiaryContainer = Color(0xFFFFE4C2),
    background = Color(0xFF000000),
    onBackground = Color.White,
    surface = Color(0xFF14171D),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF262A33),
    onSurfaceVariant = Color(0xFF9AA4B2),
    outline = Color(0xFF3A4150),
    error = MakayError,
    onError = Color.White,
)

private val LightColorScheme = lightColorScheme(
    primary = MakayPrimary,
    onPrimary = Color.White,
    secondary = MakaySecondary,
    onSecondary = Color.White,
    tertiary = MakayTertiary,
    onTertiary = Color.White,
    background = Color(0xFF0B1220),
    onBackground = Color.White,
    surface = Color(0xFF1A1F29),
    onSurface = Color.White,
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFB8C2CF),
    outline = Color(0xFF4A5263),
    error = MakayError,
    onError = Color.White,
)

@Composable
fun MakayLiveTvTheme(
    settings: AppSettingsStore? = null,
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val systemMode by remember { mutableStateOf(ThemeMode.SYSTEM) }
    val themeMode = settings?.themeMode?.collectAsState()?.value ?: systemMode
    val fontScale = settings?.fontScale?.collectAsState()?.value ?: 1f

    val resolvedDark = when (themeMode) {
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
        ThemeMode.SYSTEM -> darkTheme
    }

    val density = LocalDensity.current
    CompositionLocalProvider(LocalDensity provides Density(density.density, density.fontScale * fontScale)) {
        MaterialTheme(
            colorScheme = if (resolvedDark) DarkColorScheme else LightColorScheme,
            content = content,
        )
    }
}
