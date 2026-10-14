package com.kaliscriber.app.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

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

/** Distinct, readable colours for speaker chips, cycled by [com.kaliscriber.core.model.Speaker.colorIndex]. */
val SpeakerPalette = listOf(
    Color(0xFF4F46E5), Color(0xFF0E7C86), Color(0xFFB45309), Color(0xFFBE185D), Color(0xFF15803D),
    Color(0xFF7C3AED), Color(0xFF0369A1), Color(0xFFB91C1C), Color(0xFF4D7C0F), Color(0xFF9333EA),
)

fun speakerColor(index: Int): Color = SpeakerPalette[Math.floorMod(index, SpeakerPalette.size)]

@Composable
fun KaliScriberTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    MaterialTheme(colorScheme = colors, content = content)
}
