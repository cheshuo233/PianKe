package app.pianke.ui

import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pianke.R
import app.pianke.camera.StopKind
import app.pianke.camera.ZoomStop
import kotlin.math.roundToInt

private const val STOP_DP = 48f
private const val WINDOW_DP = 344f

/**
 * Focal-length window above a draggable knurled ring, 48 dp per stop. Dragging calls [onSelect] with the new index for
 * each stop crossed; a tap on the left/right half steps one stop. Locked (no drag, no taps) when [stops] has one entry.
 */
@Composable
fun ZoomRing(stops: List<ZoomStop>, index: Int, onSelect: (Int) -> Unit) {
    val last = stops.lastIndex
    val locked = stops.size <= 1
    val stopPx = with(LocalDensity.current) { STOP_DP.dp.toPx() }
    val density = LocalDensity.current.density
    val currentIndex by rememberUpdatedState(index)
    val currentOnSelect by rememberUpdatedState(onSelect)

    // While the finger is down the ring follows the drag; otherwise it eases to the selected stop.
    var dragging by remember { mutableStateOf(false) }
    var dragStart by remember { mutableStateOf(0) }
    var dragDx by remember { mutableFloatStateOf(0f) }

    fun base(k: Int) = WINDOW_DP / 2 - (k * STOP_DP + STOP_DP / 2)
    val target = if (dragging) {
        (base(dragStart) + dragDx / density).coerceIn(base(last.coerceAtLeast(0)), base(0))
    } else {
        base(index.coerceIn(0, last.coerceAtLeast(0)))
    }
    val offset by animateFloatAsState(target, if (dragging) snap() else tween(200), label = "zoomRing")

    Box(Modifier.size(372.dp, 64.dp)) {
        Box(
            Modifier.offset(6.dp, 0.dp).size(360.dp, 41.dp)
                .clip(RoundedCornerShape(10.dp, 10.dp, 3.dp, 3.dp))
                .background(Brush.verticalGradient(listOf(Color(0xFFF4F4F2), Color(0xFFDADAD7), Color(0xFFBBBBB7)))),
        ) {
            Box(
                Modifier.offset(8.dp, 4.dp).size(WINDOW_DP.dp, 32.dp).clip(RoundedCornerShape(7.dp))
                    .background(
                        Brush.verticalGradient(
                            0f to Color(0xFF0B0B0A), 0.3f to Color(0xFF262624), 0.52f to Color(0xFF3A3A37),
                            0.72f to Color(0xFF2A2A28), 1f to Color(0xFF0E0E0D),
                        ),
                    ),
            ) {
                Row(Modifier.offset(offset.dp, 0.dp).wrapContentWidth(unbounded = true, align = Alignment.Start)) {
                    stops.forEachIndexed { k, stop -> StopLabel(stop, k == index, enabled = !locked) { onSelect(k) } }
                }
                Box(
                    Modifier.fillMaxSize().background(
                        Brush.horizontalGradient(
                            0f to Color(0xF50A0A09), 0.24f to Color.Transparent,
                            0.76f to Color.Transparent, 1f to Color(0xF50A0A09),
                        ),
                    ),
                )
                Canvas(Modifier.align(Alignment.TopCenter).size(10.dp, 7.dp)) {
                    drawPath(
                        Path().apply { moveTo(0f, 0f); lineTo(size.width, 0f); lineTo(size.width / 2, size.height); close() },
                        Orange,
                    )
                }
            }
        }
        Knurl(
            offsetDp = offset - base(0),
            locked = locked,
            onStepBack = { onSelect((index - 1).coerceAtLeast(0)) },
            onStepForward = { onSelect((index + 1).coerceAtMost(last)) },
            modifier = Modifier.offset(0.dp, 41.dp).size(372.dp, 23.dp).clip(RoundedCornerShape(5.dp)).then(
                if (locked) Modifier else Modifier.pointerInput(last, stopPx) {
                    detectHorizontalDragGestures(
                        onDragStart = { dragStart = currentIndex; dragDx = 0f; dragging = true },
                        onDragEnd = { dragging = false },
                        onDragCancel = { dragging = false },
                    ) { _, delta ->
                        dragDx += delta
                        // Dragging right brings the lower stops in, like the canvas.
                        currentOnSelect((dragStart - (dragDx / stopPx).roundToInt()).coerceIn(0, last))
                    }
                },
            ),
        )
    }
}

@Composable
private fun StopLabel(stop: ZoomStop, current: Boolean, enabled: Boolean, onClick: () -> Unit) {
    val description = stringResource(R.string.zoom_to_desc, stop.mm)
    val color = when {
        current -> Color.White
        stop.kind == StopKind.DIGITAL -> Color(0xFFE0955E)
        else -> Color(0xFFC8C8C3)
    }
    Column(
        Modifier.size(STOP_DP.dp, 32.dp)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .semantics { contentDescription = description },
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(Modifier.padding(top = 4.dp).size(1.5.dp, 7.dp).background(Color(0xD9E6E6E0)))
        BasicText(
            stop.mm.toString(),
            Modifier.padding(top = 2.dp),
            style = TextStyle(
                fontFamily = PiankeType.Plex, fontWeight = FontWeight.SemiBold,
                fontSize = if (current) 16.sp else 12.sp, color = color,
            ),
        )
        Box(
            Modifier.padding(top = 2.dp).size(3.dp)
                .background(if (stop.kind == StopKind.NATIVE) Color(0xFFE2742B) else Color.Transparent, RoundedCornerShape(2.dp)),
        )
    }
}

/** The knurled ring: stripes that move with the window, and two tap halves (wider / closer). */
@Composable
private fun Knurl(
    offsetDp: Float,
    locked: Boolean,
    onStepBack: () -> Unit,
    onStepForward: () -> Unit,
    modifier: Modifier,
) {
    val backDescription = stringResource(R.string.zoom_wider_desc)
    val closerDescription = stringResource(R.string.zoom_closer_desc)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val period = 6.dp.toPx()
            val shift = (offsetDp.dp.toPx() % period + period) % period
            drawRect(Color(0xFF8A8A86))
            var x = shift - period
            while (x < size.width) {
                drawRect(Color(0xFFF4F4F2), Offset(x, 0f), Size(3.dp.toPx(), size.height))
                x += period
            }
            drawRect(
                Brush.verticalGradient(
                    0f to Color(0x9EFFFFFF), 0.32f to Color.Transparent, 0.55f to Color.Transparent, 1f to Color(0x66000000),
                ),
            )
        }
        Row(Modifier.fillMaxSize()) {
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(enabled = !locked, role = Role.Button, onClick = onStepBack)
                    .semantics { contentDescription = backDescription },
            )
            Box(
                Modifier.weight(1f).fillMaxHeight()
                    .clickable(enabled = !locked, role = Role.Button, onClick = onStepForward)
                    .semantics { contentDescription = closerDescription },
            )
        }
    }
}

