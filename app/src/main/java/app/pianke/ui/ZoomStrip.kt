package app.pianke.ui

import androidx.compose.animation.core.animate
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pianke.R
import app.pianke.camera.StopKind
import app.pianke.camera.ZoomScale
import app.pianke.camera.ZoomStop
import app.pianke.settings.ZoomMode
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.exp
import kotlin.math.ln

private val Red = Color(0xFFEC483A)
private val Amber = Color(0xFFD6A054)
private val Light = Color(0xFFE8E4DA)
private val TickColor = Color(0xFFD6D3CA)
private val Faint = Color(0xFF787670)

/** Rule 39: the drum scale, ln unit to dp, used by both the drag and the drawing. */
private val PerLn = 120.dp

/** The drum's position as ln(mm). [busy] holds off the follow-the-ratio sync while a drag or the release snap runs. */
private class Drum(initial: Float) {
    var posLn by mutableFloatStateOf(initial)
    var busy = false
    var job: Job? = null
}

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
    val locked = stops.size <= 1
    // The drum's exact focal length; the locked front lens has no ratio-to-mm relation, so it shows its own stop.
    val base = if (locked) focalMm.toFloat() else ZoomScale.mm(ratio)
    val drum = remember { Drum(ln(base)) }
    LaunchedEffect(base) { if (!drum.busy) drum.posLn = ln(base) }

    val ticks = remember(stops) { if (stops.isEmpty()) emptyList() else ZoomScale.ticks(stops) }
    val measurer = rememberTextMeasurer()
    val numberStyle = TextStyle(fontFamily = PiankeType.Plex, fontSize = 10.sp, fontWeight = FontWeight.Medium)
    val bigStyle = TextStyle(fontFamily = PiankeType.Plex, fontSize = 9.sp, fontWeight = FontWeight.SemiBold)
    val small = TextStyle(fontFamily = PiankeType.Plex, fontSize = 6.sp, fontWeight = FontWeight.Medium)
    val numbers = remember(ticks, measurer) {
        ticks.filter { it.marked }.associate { it.mm to measurer.measure(it.mm.toString(), numberStyle) }
    }
    val readout = remember(focalMm, measurer) { measurer.measure(focalMm.toString(), bigStyle) }
    val mmLabel = remember(measurer) { measurer.measure("mm", small) }
    val range = remember(stops, measurer) {
        measurer.measure(if (stops.isEmpty()) "" else "${stops.first().mm}-${stops.last().mm}mm", bigStyle)
    }
    val brand = remember(measurer) { measurer.measure("PIANKE ZOOM", small) }

    val onZoomNow by rememberUpdatedState(onZoom)
    val scope = rememberCoroutineScope()
    val drag = if (locked) Modifier else Modifier.pointerInput(stops, mode) {
        val perLn = PerLn.toPx()
        val lo = ln(stops.first().mm.toFloat())
        val hi = ln(stops.last().mm.toFloat())
        var startLn = 0f
        var dx = 0f
        var last: ZoomStop? = null
        fun release() {
            if (mode == ZoomMode.STOPS) {
                val stop = ZoomScale.nearest(stops, ZoomScale.ratio(exp(drum.posLn)))
                val target = ln(ZoomScale.mm(stop.ratio))
                drum.job = scope.launch {
                    animate(drum.posLn, target, animationSpec = tween(200)) { v, _ -> drum.posLn = v }
                    drum.busy = false
                }
            } else {
                drum.busy = false
            }
        }
        try {
            detectHorizontalDragGestures(
                onDragStart = {
                    drum.job?.cancel()
                    drum.busy = true
                    startLn = drum.posLn
                    dx = 0f
                    last = null
                },
                onDragEnd = { release() },
                onDragCancel = { release() },
                onHorizontalDrag = { _, delta ->
                    // The drum follows the finger: dragging right brings shorter focal lengths in from the left.
                    dx += delta
                    drum.posLn = (startLn - dx / perLn).coerceIn(lo, hi)
                    // At the clamp the end stop's own ratio goes out, so the end NOTCH does not hang on float rounding.
                    val dragRatio = when (drum.posLn) {
                        lo -> stops.first().ratio
                        hi -> stops.last().ratio
                        else -> ZoomScale.ratio(exp(drum.posLn))
                    }
                    if (mode == ZoomMode.STOPS) {
                        val stop = ZoomScale.nearest(stops, dragRatio)
                        if (stop !== last) {
                            last = stop
                            onZoomNow(stop.ratio)
                        }
                    } else {
                        onZoomNow(dragRatio)
                    }
                },
            )
        } finally {
            // A pointerInput restart cancels the detector without onDragCancel; a running release snap keeps its hold.
            if (drum.job?.isActive != true) drum.busy = false
        }
    }

    val shape = RoundedCornerShape(10.dp)
    val description = stringResource(R.string.zoom_desc, focalMm)
    Canvas(
        Modifier.size(360.dp, 48.dp)
            .semantics { contentDescription = description }
            .shadow(3.dp, shape)
            .background(Brush.verticalGradient(listOf(Color(0xFFE2E2DE), Color(0xFFC0C0BC))), shape)
            .then(drag),
    ) {
        val dp = 1.dp.toPx()
        drawRoundRect(Color(0xFFFAF9F5), cornerRadius = CornerRadius(10 * dp), style = Stroke(dp))

        val wx = 56 * dp
        val wy = 6 * dp
        val ww = 210 * dp
        val wh = 30 * dp
        // Recess bevel: dark lip on top, light lip below, then the dark glass.
        drawRoundRect(Color(0xFF969590), Offset(wx - 3 * dp, wy - 3 * dp), Size(ww + 6 * dp, wh + 6 * dp), CornerRadius(8 * dp))
        drawRoundRect(Color(0xFFF4F3EE), Offset(wx - 3 * dp, wy - dp), Size(ww + 6 * dp, wh + 6 * dp), CornerRadius(8 * dp))
        drawRoundRect(Color(0xFF1E1E1D), Offset(wx - dp, wy - dp), Size(ww + 2 * dp, wh + 2 * dp), CornerRadius(6 * dp))

        val window = Path().apply { addRoundRect(RoundRect(wx, wy, wx + ww, wy + wh, CornerRadius(5 * dp))) }
        val cx = wx + ww / 2
        clipPath(window) {
            drawRect(Color(0xFF2C2B29), Offset(wx, wy), Size(ww, wh))
            for (tick in ticks) {
                val x = cx + PerLn.toPx() * (ln(tick.mm.toFloat()) - drum.posLn)
                if (x < wx - 20 * dp || x > wx + ww + 20 * dp) continue
                val len = (if (tick.marked) 9 else 6) * dp
                drawLine(TickColor, Offset(x, wy), Offset(x, wy + len), (if (tick.marked) 0.9f else 0.6f) * dp)
                val layout = numbers[tick.mm] ?: continue
                val color = when {
                    tick.mm == current?.mm -> Red
                    stops.firstOrNull { it.mm == tick.mm }?.kind == StopKind.DIGITAL -> Amber
                    else -> Light
                }
                drawText(layout, color, Offset(x - layout.size.width / 2f, wy + 11 * dp))
            }
            // Glass: inner shadow on top, darkened ends, a soft reflection band.
            drawRect(Brush.verticalGradient(listOf(Color(0x8C000000), Color.Transparent), wy, wy + wh * 0.4f), Offset(wx, wy), Size(ww, wh))
            drawRect(
                Brush.horizontalGradient(
                    0f to Color(0x8C000000), 0.3f to Color.Transparent, 0.7f to Color.Transparent, 1f to Color(0x8C000000),
                    startX = wx, endX = wx + ww,
                ),
                Offset(wx, wy), Size(ww, wh),
            )
            drawRect(Color(0x1AFFFFFF), Offset(wx, wy + wh * 0.18f), Size(ww, wh * 0.2f))
        }

        // The fixed index below the window, then the readouts on the frame.
        drawPath(
            Path().apply { moveTo(cx, 39 * dp); lineTo(cx - 3 * dp, 44 * dp); lineTo(cx + 3 * dp, 44 * dp); close() },
            Color(0xFF3C3B38),
        )
        drawText(readout, Color(0xFFCE3228), Offset(14 * dp, 12 * dp))
        drawText(mmLabel, Faint, Offset(14 * dp, 25 * dp))
        drawText(range, Color(0xFF403F3C), Offset(278 * dp, 12 * dp))
        drawText(brand, Faint, Offset(278 * dp, 25 * dp))
    }
}
