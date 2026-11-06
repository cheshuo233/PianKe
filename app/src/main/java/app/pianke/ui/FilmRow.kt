package app.pianke.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.pianke.R
import app.pianke.film.Film
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private const val FAN_DEG = 30f
private const val WINDOW_STEP_DP = 58f
private const val DIAL_STEP_DP = 26f

// Geometry from canvas v38 (dp, reference 390 wide). The dial's centre is the pivot of the whole fan.
private const val WINDOW_CENTER_X = 164f
private const val WINDOW_CENTER_Y = 56f

/**
 * Rule 23, the only place for the fan: LCD slot, the arc label window, the half dial.
 * 30 degrees per film, labels and dial turn by the same angle; window drag 58 dp / dial drag 26 dp per step, snapping
 * on release; tap a visible neighbour or a dial half -> [onSelect] with +1 / -1; the list wraps. Each crossed step
 * calls [onSelect] with +1 or -1 (the ViewModel plays NOTCH and switches the LUT). Labels are loaded from
 * `film.labelAsset` once and cached by id.
 */
@Composable
fun FilmRow(
    films: List<Film>,
    filmIndex: Int,
    lcd: @Composable () -> Unit,
    onSelect: (delta: Int) -> Unit,
) {
    val n = films.size
    val context = LocalContext.current
    val labels by produceState(emptyMap<String, ImageBitmap>(), films) {
        value = withContext(Dispatchers.IO) {
            films.associate { film ->
                film.id to context.assets.open(film.labelAsset).use { BitmapFactory.decodeStream(it).asImageBitmap() }
            }
        }
    }

    // `target` is the unwrapped film position the fan settles on; `shown` is the angle in films, animated or dragged.
    var target by remember { mutableIntStateOf(filmIndex) }
    var dragging by remember { mutableStateOf(false) }
    var dragStart by remember { mutableIntStateOf(0) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val animated = remember { Animatable(filmIndex.toFloat()) }
    val shown = if (dragging) dragValue else animated.value
    var lastShown = shown

    LaunchedEffect(filmIndex) { target += wrap(filmIndex - target, n) }
    LaunchedEffect(target, dragging) {
        if (!dragging) {
            animated.snapTo(lastShown)
            animated.animateTo(target.toFloat(), tween(320, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)))
        }
    }
    lastShown = shown

    val currentOnSelect by rememberUpdatedState(onSelect)
    @Composable
    fun drag(stepDp: Float) = Modifier.stepDrag(
        stepDp,
        onStart = { dragStart = target; dragValue = target.toFloat(); dragging = true },
        onOffset = { steps -> dragValue = dragStart + steps },
        onStep = { currentOnSelect(it) },
        onEnd = { dragging = false },
    )

    Box(Modifier.fillMaxWidth().height(122.dp).clipToBounds()) {
        Box(Modifier.offset(11.dp, 7.dp)) { lcd() }

        // The light metal plate round the window.
        Box(
            Modifier.offset(222.dp, 1.dp).size(168.dp, 120.dp).clip(RingShape(168f, 60f, 66f, 168f))
                .background(Brush.verticalGradient(listOf(Color(0xFF8C8C88), Color(0xFFB6B6B2), Color(0xFFECECEA)))),
        )

        // The label window: a ring band 70..164 dp from the dial centre, labels fanned on a 115 dp radius.
        val density = LocalDensity.current.density
        Box(
            Modifier.offset(226.dp, 5.dp).size(125.dp, 112.dp)
                .clip(RingShape(WINDOW_CENTER_X, WINDOW_CENTER_Y, 70f, 164f))
                .background(
                    Brush.radialGradient(
                        0.427f to Color(0xFF111110), 0.683f to Color(0xFF262624), 1f to Color(0xFF141413),
                        center = Offset(WINDOW_CENTER_X * density, WINDOW_CENTER_Y * density),
                        radius = 164f * density,
                    ),
                )
                .then(drag(WINDOW_STEP_DP)),
        ) {
            films.forEachIndexed { j, film ->
                val d = wrapF(j - shown, n)
                val a = abs(d)
                val alpha = ((2f - a) / 0.4f).coerceIn(0f, 1f)
                val bitmap = labels[film.id]
                if (alpha > 0f && bitmap != null) {
                    val neighbour = a in 0.5f..1.5f
                    val pick = stringResource(R.string.film_pick_desc, film.name)
                    val brightness = 1f - 0.3f * minOf(a, 1f)
                    Image(
                        bitmap, contentDescription = null, contentScale = ContentScale.FillBounds,
                        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) }),
                        modifier = Modifier
                            .offset(8.dp, 18.5.dp).size(82.dp, 75.dp)
                            .zIndex(max(1, 20 - (a * 4).roundToInt()).toFloat())
                            .graphicsLayer {
                                rotationZ = -d * FAN_DEG
                                transformOrigin = TransformOrigin(156f / 82f, 0.5f)
                                this.alpha = alpha
                            }
                            .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(2.dp))
                            .clickable(enabled = neighbour, role = Role.Button) { onSelect(if (d > 0) 1 else -1) }
                            .semantics { if (neighbour) contentDescription = pick },
                    )
                }
            }
            Canvas(Modifier.fillMaxSize().zIndex(30f)) {
                val center = Offset(WINDOW_CENTER_X.dp.toPx(), WINDOW_CENTER_Y.dp.toPx())
                val radius = 164.dp.toPx()
                drawRect(
                    Brush.radialGradient(
                        0.427f to Color(0xD9000000), 0.482f to Color.Transparent,
                        0.945f to Color.Transparent, 1f to Color(0xCC000000),
                        center = center, radius = radius,
                    ),
                )
                drawRect(
                    Brush.verticalGradient(
                        0f to Color(0xCC000000), 9f / 112f to Color.Transparent,
                        105f / 112f to Color.Transparent, 1f to Color(0x8C000000),
                    ),
                )
            }
        }

        Dial(shown, drag(DIAL_STEP_DP), onPrev = { onSelect(-1) }, onNext = { onSelect(1) })
    }
}

/** The half dial: knurled rim and metal cap turn with the fan; its top half is "previous", the bottom half "next". */
@Composable
private fun Dial(shown: Float, drag: Modifier, onPrev: () -> Unit, onNext: () -> Unit) {
    val prev = stringResource(R.string.film_prev_desc)
    val next = stringResource(R.string.film_next_desc)
    Box(Modifier.offset(334.dp, 5.dp).size(112.dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(Color(0xFFA5A5A1))
            rotate(shown * FAN_DEG, center) {
                drawCircle(Color(0xFF8E8E8A))
                repeat(90) { i ->
                    drawArc(Color(0xFFEDEDEB), i * 4f, 2f, useCenter = true)
                }
            }
            drawCircle(
                Brush.linearGradient(
                    0f to Color(0x59FFFFFF), 0.45f to Color.Transparent, 0.6f to Color.Transparent, 1f to Color(0x40000000),
                    start = Offset.Zero, end = Offset(size.width, size.height),
                ),
            )
            rotate(-90f, center) {
                drawCircle(
                    Brush.sweepGradient(
                        0f to Color(0xFFECECEA), 0.111f to Color(0xFFBDBDB9), 0.236f to Color(0xFFF6F6F4),
                        0.375f to Color(0xFFC4C4C0), 0.5f to Color(0xFFE4E4E1), 0.611f to Color(0xFFB7B7B3),
                        0.736f to Color(0xFFF6F6F4), 0.875f to Color(0xFFC4C4C0), 1f to Color(0xFFECECEA),
                        center = center,
                    ),
                    radius = 49.dp.toPx(), center = center,
                )
            }
        }
        Column(Modifier.fillMaxSize().clip(CircleShape).then(drag)) {
            Box(
                Modifier.weight(1f).fillMaxWidth().clickable(role = Role.Button, onClick = onPrev)
                    .semantics { contentDescription = prev },
            )
            Box(
                Modifier.weight(1f).fillMaxWidth().clickable(role = Role.Button, onClick = onNext)
                    .semantics { contentDescription = next },
            )
        }
    }
}

/**
 * One vertical step-drag used by both the window and the dial. Reports the continuous offset in steps (dragging up
 * is positive, bringing the next film in) and a +1 / -1 for every step crossed, counted from the drag start.
 */
@Composable
private fun Modifier.stepDrag(
    stepDp: Float,
    onStart: () -> Unit,
    onOffset: (Float) -> Unit,
    onStep: (Int) -> Unit,
    onEnd: () -> Unit,
): Modifier {
    val stepPx = with(LocalDensity.current) { stepDp.dp.toPx() }
    val start by rememberUpdatedState(onStart)
    val offset by rememberUpdatedState(onOffset)
    val step by rememberUpdatedState(onStep)
    val end by rememberUpdatedState(onEnd)
    return this.pointerInput(stepPx) {
        var dy = 0f
        var emitted = 0
        detectVerticalDragGestures(
            onDragStart = { dy = 0f; emitted = 0; start() },
            onDragEnd = { end() },
            onDragCancel = { end() },
        ) { _, amount ->
            dy += amount
            offset(-dy / stepPx)
            val steps = (-dy / stepPx).roundToInt()
            while (emitted < steps) { step(1); emitted++ }
            while (emitted > steps) { step(-1); emitted-- }
        }
    }
}

/** A circular band clipped to its layer: dp coordinates of the circle centre, inner and outer radius. */
private class RingShape(val cx: Float, val cy: Float, val inner: Float, val outer: Float) : Shape {
    override fun createOutline(size: androidx.compose.ui.geometry.Size, layoutDirection: LayoutDirection, density: Density): Outline {
        val d = density.density
        val c = Offset(cx * d, cy * d)
        val ring = Path.combine(
            PathOperation.Difference,
            Path().apply { addOval(Rect(c, outer * d)) },
            Path().apply { addOval(Rect(c, inner * d)) },
        )
        return Outline.Generic(ring)
    }
}

/** The shortest signed distance in films, in -n/2..n/2, for an integer difference. */
private fun wrap(delta: Int, n: Int): Int = Math.floorMod(delta + n / 2, n) - n / 2

private fun wrapF(delta: Float, n: Int): Float {
    val half = n / 2f
    return ((delta + half) % n + n) % n - half
}
