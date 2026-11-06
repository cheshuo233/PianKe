package app.pianke.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.opengl.GLSurfaceView
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.input.pointer.pointerInput
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
import app.pianke.settings.frame
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * The shooting screen: top bar, live viewfinder with its overlays (corner mask, 1:1 mask, grid, date stamp, focal
 * tag, issue text), zoom strip, film row, bottom bar. [preview] is the Activity's GLSurfaceView, already attached to
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
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.bodyBackground().windowInsetsPadding(WindowInsets.systemBars)) {
            TopBar(
                state.settings.frame, state.settings.flash, !front, state.counterShown, state.rewinding,
                onOpenMenu, viewModel::cycleFlash,
            )
            Box(Modifier.padding(top = 8.dp).align(Alignment.CenterHorizontally)) { Viewfinder(state, preview, viewModel::focusAt) }
            Box(Modifier.padding(top = 10.dp).align(Alignment.CenterHorizontally)) {
                ZoomStrip(state.stops, state.zoomRatio, state.settings.zoomMode, state.focalMm, state.stop, viewModel::selectZoom)
            }
            Box(Modifier.padding(top = 6.dp)) {
                FilmRow(
                    films = state.films,
                    filmIndex = state.filmIndex,
                    lcd = {
                        LcdPanel(
                            state.films[state.filmIndex], state.filmIndex, state.films.size,
                            state.exposure, state.developing, state.lcdMessage, state.rewinding,
                        )
                    },
                    onSelect = viewModel::selectFilm,
                    onOpen = viewModel::openDrawer,
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
        FilmDrawer(
            films = state.films, filmIndex = state.filmIndex, drawer = state.drawer,
            onStep = viewModel::drawerStep, onPick = viewModel::selectFilmAt, onClose = viewModel::closeDrawer,
        )
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
private fun Viewfinder(state: ShooterUiState, preview: GLSurfaceView, onFocus: suspend (Float, Float) -> Boolean) {
    val front = state.settings.lens == Lens.FRONT
    val square = state.settings.framing == Framing.SQUARE
    // The 1:1 mask covers the top and bottom [mask]; the stamp and tag sit inside the square then (rule 35 filters taps by it too).
    val mask = if (square) 60.dp else 0.dp
    val scope = rememberCoroutineScope()
    var reticle by remember { mutableStateOf<Offset?>(null) }
    var focused by remember { mutableStateOf<Boolean?>(null) }
    var reticleAlpha by remember { mutableFloatStateOf(1f) }
    var focusJob by remember { mutableStateOf<Job?>(null) }
    val today = remember { LocalDate.now().format(DateTimeFormatter.ofPattern("''yy MM dd")) }
    // A SurfaceView ignores Modifier.clip, so the rounded corners and the bezel are drawn on top.
    Box(
        Modifier.size(360.dp, 480.dp).drawBehind {
            val grow = 5.dp.toPx()
            drawRoundRect(Bezel, Offset(-grow, -grow), Size(size.width + 2 * grow, size.height + 2 * grow), CornerRadius(13.dp.toPx()))
        }.background(Color(0xFF0B0B0A)),
    ) {
        AndroidView({ preview }, Modifier.fillMaxSize())
        // Rule 35: taps in the 1:1 bands are ignored; a new tap cancels the previous focus job.
        Box(
            Modifier.fillMaxSize().pointerInput(mask) {
                detectTapGestures { tap ->
                    val band = mask.toPx()
                    if (tap.y < band || tap.y > size.height - band) return@detectTapGestures
                    focusJob?.cancel()
                    reticle = tap
                    focused = null
                    reticleAlpha = 1f
                    focusJob = scope.launch {
                        focused = onFocus(tap.x / size.width, tap.y / size.height)
                        delay(2_000)
                        animate(1f, 0f, animationSpec = tween(300)) { value, _ -> reticleAlpha = value }
                        reticle = null
                    }
                }
            },
        )

        if (square) {
            Box(Modifier.fillMaxWidth().height(mask).background(Color(0x8C000000)).align(Alignment.TopCenter))
            Box(Modifier.fillMaxWidth().height(mask).background(Color(0x8C000000)).align(Alignment.BottomCenter))
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
        if (state.settings.dateStamp) {
            BasicText(
                today,
                Modifier.align(Alignment.BottomEnd).padding(end = 22.dp, bottom = 20.dp + mask),
                style = TextStyle(fontFamily = PiankeType.Plex, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.1.sp, color = Color(0xFFF28C3A)),
            )
        }
        val stop = state.stop
        val ratio = String.format(Locale.ROOT, "%.1f", (stop?.ratio ?: state.zoomRatio)).removeSuffix(".0")
        val ratioText = stringResource(R.string.zoom_ratio, ratio)
        BasicText(
            if (stop != null) {
                stringResource(R.string.zoom_tag, stop.mm, ratioText, stringResource(zoomNoteRes(stop, front)))
            } else {
                stringResource(R.string.zoom_tag_free, state.focalMm, ratioText)
            },
            Modifier.align(Alignment.BottomStart).padding(start = 14.dp, bottom = 16.dp + mask)
                .semantics { liveRegion = LiveRegionMode.Polite }
                .background(Color(0x94141210), RoundedCornerShape(10.dp)).padding(horizontal = 9.dp, vertical = 4.dp),
            style = TextStyle(fontFamily = PiankeType.Plex, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFFF3ECDD)),
        )
        state.issue?.let { issue ->
            BasicText(
                stringResource(if (issue == CameraIssue.Fatal) R.string.issue_fatal else R.string.issue_in_use),
                Modifier.align(Alignment.Center).semantics { liveRegion = LiveRegionMode.Polite }.wrapContentSize(),
                style = TextStyle(fontSize = 16.sp, fontWeight = FontWeight.Medium, color = Color(0xFFF3ECDD)),
            )
        }
        // Rule 41: the dim while rewinding, fast in and slow out.
        val dim by animateFloatAsState(
            if (state.rewinding) 0.55f else 0f,
            tween(if (state.rewinding) 150 else 300),
            label = "rewindDim",
        )
        Box(Modifier.fillMaxSize().drawBehind { drawRect(Color.Black.copy(alpha = dim)) })
        Canvas(Modifier.fillMaxSize()) {
            val corner = 8.dp.toPx()
            val cornerMask = Path().apply {
                fillType = PathFillType.EvenOdd
                addRect(androidx.compose.ui.geometry.Rect(0f, 0f, size.width, size.height))
                addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(corner)))
            }
            drawPath(cornerMask, Bezel)
            drawRect(
                Brush.linearGradient(0f to Color(0x24FFFFFF), 0.32f to Color.Transparent, 1f to Color.Transparent, end = Offset(size.width, size.height)),
            )
        }
        reticle?.let { centre ->
            // §9.4: four corner brackets, 60dp square, 1.5dp stroke, 12dp arms; orange once focus succeeded.
            Canvas(Modifier.fillMaxSize()) {
                val color = (if (focused == true) Color(0xFFF28C3A) else Color(0xFFF3ECDD)).copy(alpha = reticleAlpha)
                val half = 30.dp.toPx()
                val arm = 12.dp.toPx()
                val stroke = 1.5.dp.toPx()
                for (sx in listOf(-1f, 1f)) {
                    for (sy in listOf(-1f, 1f)) {
                        val corner = Offset(centre.x + sx * half, centre.y + sy * half)
                        drawLine(color, corner, Offset(corner.x - sx * arm, corner.y), stroke)
                        drawLine(color, corner, Offset(corner.x, corner.y - sy * arm), stroke)
                    }
                }
            }
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
