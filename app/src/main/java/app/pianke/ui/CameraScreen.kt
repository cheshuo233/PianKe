package app.pianke.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pianke.R
import app.pianke.camera.CameraIssue
import app.pianke.camera.StopKind
import app.pianke.camera.ZoomStop
import app.pianke.settings.Framing
import app.pianke.settings.Lens
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The shooting screen: top bar, live viewfinder with its overlays (corner mask, 1:1 mask, grid, date stamp, focal
 * tag, issue text), zoom ring, film row, bottom bar. [preview] is the Activity's GLSurfaceView, already attached to
 * the renderer; it is only embedded here, so MENU and the permission gate never destroy it. [rotation] is read when
 * the shutter is pressed.
 */
@Composable
fun CameraScreen(
    state: ShooterUiState,
    viewModel: ShooterViewModel,
    preview: GLSurfaceView,
    rotation: () -> Int,
    onOpenMenu: () -> Unit,
) {
    val context = LocalContext.current
    val front = state.settings.lens == Lens.FRONT
    Column(Modifier.bodyBackground().windowInsetsPadding(WindowInsets.systemBars)) {
        TopBar(state.settings.frame, state.settings.flash, !front, onOpenMenu, viewModel::cycleFlash)
        Box(Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally)) { Viewfinder(state, preview) }
        Box(Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally)) {
            ZoomRing(state.stops, state.stopIndex, viewModel::selectStop)
        }
        Box(Modifier.padding(top = 6.dp)) {
            FilmRow(
                films = state.films,
                filmIndex = state.filmIndex,
                lcd = {
                    LcdPanel(
                        state.films[state.filmIndex], state.filmIndex, state.films.size,
                        state.exposure, state.developing, state.lcdMessage,
                    )
                },
                onSelect = viewModel::selectFilm,
            )
        }
        Spacer(Modifier.weight(1f))
        BottomBar(
            lastPhoto = state.lastPhoto,
            onGallery = { state.lastPhoto?.let { openPhoto(context, it) } },
            onShutter = { viewModel.onShutter(rotation()) },
            onFlip = viewModel::flipLens,
        )
        Spacer(Modifier.height(14.dp))
    }
}

private fun openPhoto(context: android.content.Context, uri: Uri) {
    val view = Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/jpeg").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    try {
        context.startActivity(view)
    } catch (_: ActivityNotFoundException) {
        // No viewer installed: nothing to show.
    }
}

@Composable
private fun Viewfinder(state: ShooterUiState, preview: GLSurfaceView) {
    val front = state.settings.lens == Lens.FRONT
    val square = state.settings.framing == Framing.SQUARE
    val today = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("''yy MM dd")) }
    // A SurfaceView ignores Modifier.clip, so the rounded corners and the bezel are drawn on top.
    Box(
        Modifier.size(360.dp, 480.dp).drawBehind {
            val grow = 5.dp.toPx()
            drawRoundRect(Bezel, Offset(-grow, -grow), Size(size.width + 2 * grow, size.height + 2 * grow), CornerRadius(13.dp.toPx()))
        }.background(Color(0xFF0B0B0A)),
    ) {
        AndroidView({ preview }, Modifier.fillMaxSize())

        if (square) {
            Box(Modifier.fillMaxWidth().height(60.dp).background(Color(0x8C000000)).align(Alignment.TopCenter))
            Box(Modifier.fillMaxWidth().height(60.dp).background(Color(0x8C000000)).align(Alignment.BottomCenter))
        }
        if (state.settings.grid) {
            Canvas(Modifier.fillMaxSize()) {
                val line = Color(0x59FFFFFF)
                for (i in 1..2) {
                    drawLine(line, Offset(size.width * i / 3, 0f), Offset(size.width * i / 3, size.height), 1.dp.toPx())
                    drawLine(line, Offset(0f, size.height * i / 3), Offset(size.width, size.height * i / 3), 1.dp.toPx())
                }
            }
        }
        // The 1:1 mask covers the top and bottom 60 dp, so the stamp and tag sit inside the square then.
        val inset = if (square) 60.dp else 0.dp
        if (state.settings.dateStamp) {
            BasicText(
                today,
                Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 20.dp + inset),
                style = TextStyle(fontFamily = PiankeType.Plex, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp, color = Color(0xFFF28C3A)),
            )
        }
        state.stops.getOrNull(state.stopIndex)?.let { stop ->
            val ratio = String.format(Locale.ROOT, "%.1f", stop.ratio).removeSuffix(".0")
            BasicText(
                stringResource(R.string.zoom_tag, stop.mm, stringResource(R.string.zoom_ratio, ratio), stringResource(zoomNoteRes(stop, front))),
                Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 16.dp + inset)
                    .semantics { liveRegion = LiveRegionMode.Polite }
                    .background(Color(0x94141210), RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
                style = TextStyle(fontFamily = PiankeType.Plex, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF3ECDD)),
            )
        }
        state.issue?.let { issue ->
            BasicText(
                stringResource(if (issue == CameraIssue.Fatal) R.string.issue_fatal else R.string.issue_in_use),
                Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite }.wrapContentSize(),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF3ECDD)),
            )
        }
        Canvas(Modifier.fillMaxSize()) {
            val corner = 8.dp.toPx()
            val mask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)))
            }
            drawPath(mask, Bezel)
            drawRect(
                Brush.linearGradient(0f to Color(0x24FFFFFF), 0.32f to Color.Transparent, 1f to Color.Transparent, end = Offset(size.width, size.height)),
            )
        }
    }
}

/** The one rule for the viewfinder's focal-tag note (main, crop, digital, ...); returns a string resource id. */
private fun zoomNoteRes(stop: ZoomStop, front: Boolean): Int = when {
    front -> R.string.zoom_note_front
    stop.kind == StopKind.CROP -> R.string.zoom_note_crop
    stop.kind == StopKind.DIGITAL -> R.string.zoom_note_digital
    stop.ratio < 1f -> R.string.zoom_note_ultrawide
    stop.ratio == 1f -> R.string.zoom_note_main
    else -> R.string.zoom_note_tele
}
