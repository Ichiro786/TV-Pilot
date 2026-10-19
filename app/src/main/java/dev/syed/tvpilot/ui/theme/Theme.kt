package dev.syed.tvpilot.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.material3.MaterialExpressiveTheme
import androidx.compose.material3.MotionScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.expressiveLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val FallbackDark = darkColorScheme(
    primary = Color(0xFFD0BCFF),
    onPrimary = Color(0xFF381E72),
    primaryContainer = Color(0xFF4F378B),
    onPrimaryContainer = Color(0xFFEADDFF),
    secondaryContainer = Color(0xFF4A4458),
    tertiary = Color(0xFF7DD3C0),
    tertiaryContainer = Color(0xFF1F5A50),
    onTertiaryContainer = Color(0xFFB8F2E3),
    surface = Color(0xFF141218),
    surfaceContainer = Color(0xFF211F26),
    surfaceContainerHigh = Color(0xFF2B2930),
    surfaceContainerHighest = Color(0xFF36343B),
)

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun TvPilotTheme(dark: Boolean = isSystemInDarkTheme(), amoled: Boolean = false, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    val base = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> if (dark) dynamicDarkColorScheme(ctx) else dynamicLightColorScheme(ctx)
        dark -> FallbackDark
        else -> expressiveLightColorScheme()
    }
    // AMOLED: true black behind everything, with containers as faint steps above it.
    val scheme = if (dark && amoled) base.copy(
        background = Color.Black,
        surface = Color.Black,
        surfaceDim = Color.Black,
        surfaceContainerLowest = Color.Black,
        surfaceContainerLow = Color(0xFF0B0B0C),
        surfaceContainer = Color(0xFF131314),
        surfaceContainerHigh = Color(0xFF1B1B1D),
        surfaceContainerHighest = Color(0xFF242427),
    ) else base
    MaterialExpressiveTheme(
        colorScheme = scheme,
        motionScheme = MotionScheme.expressive(),
        content = content,
    )
}
