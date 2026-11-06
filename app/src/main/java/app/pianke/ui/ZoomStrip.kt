package app.pianke.ui

import androidx.compose.runtime.Composable
import app.pianke.camera.ZoomStop
import app.pianke.settings.ZoomMode

/**
 * The lens-style zoom strip (360x48 dp, spec 9.2), replacing the old ring. Rule 39: tick positions
 * `x = window centre + 120 dp * ln(f / focalMm)` and the number colours (current red, DIGITAL amber, others light).
 * Rule 31: in STOPS mode `ZoomScale.nearest` picks the stop under the drum centre while dragging and the drum snaps
 * to it on release; in FREE mode it stays where released. Drags call [onZoom] with a ratio. [focalMm] and [current]
 * come derived from `ShooterUiState` (rule 30); the strip never derives them. Locked when [stops] has one entry.
 */
@Composable
fun ZoomStrip(
    stops: List<ZoomStop>, ratio: Float, mode: ZoomMode, focalMm: Int, current: ZoomStop?,
    onZoom: (Float) -> Unit,
) {
    TODO()
}
