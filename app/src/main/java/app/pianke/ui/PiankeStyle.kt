package app.pianke.ui

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.text.font.FontFamily

/** The one place that picks fonts. Phase B swaps in Share Tech Mono and IBM Plex Mono here and nowhere else. */
object PiankeType {
    val Lcd: FontFamily = FontFamily.Monospace
    val Plex: FontFamily = FontFamily.Monospace
}

/** Canvas v38 is drawn 390 dp wide; [LocalUiScale] is screen width / [REFERENCE_WIDTH_DP], provided once by CameraScreen. */
val LocalUiScale = compositionLocalOf { 1f }

const val REFERENCE_WIDTH_DP = 390f
