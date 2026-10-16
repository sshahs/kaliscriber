package com.kaliscriber.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import com.kaliscriber.core.ai.ThemeMode

private val Indigo = Color(0xFF4F46E5)
private val IndigoLight = Color(0xFFC7C4FF)

private val LightColors = lightColorScheme(
    primary = Indigo,
    secondary = Color(0xFF0E7C86),
    tertiary = Color(0xFFB45309),
)

private val DarkColors = darkColorScheme(
    primary = IndigoLight,
    secondary = Color(0xFF7FD3DA),
    tertiary = Color(0xFFFFB86B),
)

/** Distinct speaker colours, cycled by [com.kaliscriber.core.model.Speaker.colorIndex]; dark enough to read on light surfaces. */
val SpeakerPalette = listOf(
    Color(0xFF4F46E5), Color(0xFF0E7C86), Color(0xFFB45309), Color(0xFFBE185D), Color(0xFF15803D),
    Color(0xFF7C3AED), Color(0xFF0369A1), Color(0xFFB91C1C), Color(0xFF4D7C0F), Color(0xFF9333EA),
)

/** The same hues, light enough to read on dark surfaces. */
private val DarkSpeakerPalette = listOf(
    Color(0xFFA5B4FC), Color(0xFF5EEAD4), Color(0xFFFCD34D), Color(0xFFF9A8D4), Color(0xFF86EFAC),
    Color(0xFFC4B5FD), Color(0xFF7DD3FC), Color(0xFFFCA5A5), Color(0xFFBEF264), Color(0xFFD8B4FE),
)

private val LocalSpeakerPalette = staticCompositionLocalOf { SpeakerPalette }

/** A speaker's colour, readable on the current theme's surfaces. */
@Composable
fun speakerColor(index: Int): Color {
    val palette = LocalSpeakerPalette.current
    return palette[Math.floorMod(index, palette.size)]
}

/** Whether [mode] means dark right now. */
@Composable
fun isDark(mode: ThemeMode): Boolean = when (mode) {
    ThemeMode.SYSTEM -> isSystemInDarkTheme()
    ThemeMode.LIGHT -> false
    ThemeMode.DARK -> true
}

@Composable
fun KaliScriberTheme(mode: ThemeMode = ThemeMode.SYSTEM, dynamicColor: Boolean = true, content: @Composable () -> Unit) {
    val dark = isDark(mode)
    val colors = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    CompositionLocalProvider(LocalSpeakerPalette provides if (dark) DarkSpeakerPalette else SpeakerPalette) {
        MaterialTheme(colorScheme = colors, content = content)
    }
}
