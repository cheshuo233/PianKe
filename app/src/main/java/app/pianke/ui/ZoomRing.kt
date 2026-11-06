package app.pianke.ui

import androidx.compose.runtime.Composable
import app.pianke.camera.ZoomStop

/**
 * Focal-length window above a draggable knurled ring, 48 dp per stop. Dragging calls [onSelect] with the new index for
 * each stop crossed; a tap on the left/right half steps one stop. Locked (no drag, no taps) when [stops] has one entry.
 */
@Composable
fun ZoomRing(stops: List<ZoomStop>, index: Int, onSelect: (Int) -> Unit) {
    TODO()
}
