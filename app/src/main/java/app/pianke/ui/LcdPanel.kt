package app.pianke.ui

import androidx.compose.runtime.Composable
import app.pianke.camera.Exposure
import app.pianke.film.Film

/**
 * The 178 dp LCD (spec 9.2 table), as a liveRegion. Row 4 left is [message] if present, else "DEV" with one dot
 * per frame in [developing], else empty; right is `{n}/{filmCount}`. Row 3 formats shutter speed and aperture
 * from [exposure].
 */
@Composable
fun LcdPanel(film: Film, filmIndex: Int, filmCount: Int, exposure: Exposure?, developing: Int, message: String?) {
    TODO()
}
