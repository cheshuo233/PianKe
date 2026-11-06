package app.pianke.ui

import androidx.compose.runtime.Composable
import app.pianke.camera.PreviewRenderer
import app.pianke.camera.ZoomStop

/**
 * The shooting screen: top bar, live GL preview with its overlays (1:1 mask, grid, date stamp, focal tag, issue text),
 * zoom ring, film row, bottom bar. Provides [LocalUiScale]. Attaches the GLSurfaceView to [renderer] (H6) and forwards
 * lifecycle pause/resume to it. [rotation] is read when the shutter is pressed.
 */
@Composable
fun CameraScreen(
    state: ShooterUiState,
    viewModel: ShooterViewModel,
    renderer: PreviewRenderer,
    rotation: () -> Int,
    onOpenMenu: () -> Unit,
) {
    TODO()
}

/** The one rule for the viewfinder's focal-tag note (main, crop, digital, ...); returns a string resource id. */
internal fun zoomNoteRes(stop: ZoomStop, front: Boolean): Int = TODO()
