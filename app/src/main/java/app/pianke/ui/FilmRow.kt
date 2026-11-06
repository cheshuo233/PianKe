package app.pianke.ui

import android.graphics.BitmapFactory
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import app.pianke.R
import app.pianke.film.Film
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

// ---- Rule 23: the fan geometry, the only place it is written. All lengths are dp on the 390 dp reference. ----
// The dial's centre sits on the physical screen's right edge, at the row's vertical centre; the label wheel and
// the window are concentric with it.

/** Row height; the dial (176) fits inside it without touching the zoom scale above or the bottom bar below. */
private const val FILM_ROW_HEIGHT_DP = 184f
private const val DIAL_RADIUS = 88f

private const val LABEL_W = 82f
private const val LABEL_H = 75f

/** Neighbouring labels clear each other by this much at their inner corners. */
private const val LABEL_GAP = 3f

private const val WINDOW_INNER = DIAL_RADIUS + 4f

/** Radius of the label centres: the labels' inner edge sits 4 dp outside the window's inner edge. */
private const val WHEEL_RADIUS = WINDOW_INNER + 4f + LABEL_W / 2

/** The outer corners of the labels, plus 3 dp. */
private val WINDOW_OUTER: Float = hypot(WHEEL_RADIUS + LABEL_W / 2, LABEL_H / 2) + 3f

/** The window's half height: the current label (+-37.5) shows fully, the neighbours only peek in at the tips. */
private const val WINDOW_HALF_HEIGHT = 62f
private const val PLATE_MARGIN = 4f

/**
 * Step angle in degrees, derived from the radii: the angle at which the inner corners of two neighbouring labels are
 * [LABEL_GAP] apart. With a = inner radial edge and h = half the label height, the corner of one label lies
 * `a*sin(t) - h*cos(t) - h` from the side edge of the next, so t = atan(h / a) + asin((h + gap) / hypot(a, h)).
 */
private val STEP_DEG: Float = run {
    val a = WHEEL_RADIUS - LABEL_W / 2
    val h = LABEL_H / 2
    Math.toDegrees((atan2(h, a) + asin((h + LABEL_GAP) / hypot(a, h))).toDouble()).toFloat()
}

/** Drag per step: the arc length of one step on the window at the label-centre radius, and on the dial at its rim. */
private val WINDOW_STEP_DP: Float = (WHEEL_RADIUS * STEP_DEG * PI / 180).toFloat()
private val DIAL_STEP_DP: Float = (DIAL_RADIUS * STEP_DEG * PI / 180).toFloat()

/**
 * The film row: LCD slot, the arc label window, the half dial. The row is laid out against the physical screen edge:
 * it is [LocalEdgeMargin] wider than the centred board and overhangs it on the right.
 *
 * Labels sit on a wheel concentric with the dial, [STEP_DEG] apart, and turn rigidly with the dial by that angle per
 * step. The window clips them to the arc band, so only the current label shows fully and the neighbours' corners peek
 * in at the tips. Window drag [WINDOW_STEP_DP] / dial drag [DIAL_STEP_DP] per step, snapping on release; tap a visible
 * neighbour or a dial half -> [onSelect] with +1 / -1; the list wraps. Each crossed step calls [onSelect] with +1 or -1
 * (the ViewModel plays NOTCH and switches the LUT). A tap on the current label calls [onOpen] (the film drawer). Labels are loaded from `film.labelAsset` once and cached by id.
 */
@Composable
fun FilmRow(
    films: List<Film>,
    filmIndex: Int,
    lcd: @Composable () -> Unit,
    onSelect: (delta: Int) -> Unit,
    onOpen: () -> Unit,
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

    // ONE continuous, unwrapped wheel position, in steps. Labels and dial both derive from it; the film index is
    // always position mod n. Drag, tap and snap all move this same value, so nothing ever turns a whole cycle.
    var position by remember { mutableFloatStateOf(filmIndex.toFloat()) }
    var dragBase by remember { mutableFloatStateOf(0f) }
    var goal by remember { mutableStateOf<Float?>(null) }

    // A new goal animates from the current value; starting a drag sets the goal to null and so cancels it.
    LaunchedEffect(goal) {
        val g = goal ?: return@LaunchedEffect
        animate(position, g, animationSpec = tween(320, easing = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f))) { v, _ ->
            position = v
        }
    }
    // The index changed for a reason other than this wheel (startup, resolve): jump to its nearest equivalent.
    // Steps this wheel reported itself already agree with where it is heading, so they do nothing here.
    LaunchedEffect(filmIndex) {
        val settled = (goal ?: position).roundToInt()
        if (Math.floorMod(settled, n) != filmIndex) {
            goal = null
            position = (settled + wrapF((filmIndex - settled).toFloat(), n).roundToInt()).toFloat()
        }
    }

    val currentOnSelect by rememberUpdatedState(onSelect)
    fun settle(value: Float) {
        goal = value
    }

    /** Moves the position by [steps] from where the drag began, reporting each step boundary crossed, once each. */
    fun dragTo(steps: Float) {
        val before = position.roundToInt()
        position = dragBase + steps
        val diff = position.roundToInt() - before
        repeat(abs(diff)) { currentOnSelect(if (diff > 0) 1 else -1) }
    }

    /** A tap: one step from where the wheel is heading, so taps during the animation keep stepping. */
    fun tapStep(delta: Int) {
        settle((goal ?: position).roundToInt() + delta.toFloat())
        currentOnSelect(delta)
    }

    @Composable
    fun drag(stepDp: Float) = Modifier.stepDrag(
        stepDp,
        onStart = { goal?.let { position = it }; goal = null; dragBase = position },
        onOffset = ::dragTo,
        onEnd = { settle(position.roundToInt().toFloat()) },
    )

    val margin = LocalEdgeMargin.current
    val width = REFERENCE_WIDTH_DP.dp + margin
    val cy = FILM_ROW_HEIGHT_DP / 2
    // The row overhangs the board by `margin` on the right: shift it so its left edge stays on the board's.
    Box(
        Modifier.offset(margin / 2).requiredWidth(width).height(FILM_ROW_HEIGHT_DP.dp).clipToBounds(),
    ) {
        Box(Modifier.align(Alignment.CenterStart).padding(start = 8.dp)) { lcd() }

        // The light metal plate round the window.
        val plateOuter = WINDOW_OUTER + PLATE_MARGIN + 2f
        val plateHalf = WINDOW_HALF_HEIGHT + PLATE_MARGIN
        Box(
            Modifier.offset(width - plateOuter.dp, (cy - plateHalf).dp).size(plateOuter.dp, (2 * plateHalf).dp)
                .ringClip(plateHalf, WINDOW_INNER - PLATE_MARGIN, plateOuter)
                .background(Brush.verticalGradient(listOf(Color(0xFF8C8C88), Color(0xFFB6B6B2), Color(0xFFECECEA)))),
        )

        // The label window: the band WINDOW_INNER..WINDOW_OUTER round the dial centre, cut by the half height.
        val density = LocalDensity.current.density
        Box(
            Modifier.offset(width - WINDOW_OUTER.dp, (cy - WINDOW_HALF_HEIGHT).dp)
                .size(WINDOW_OUTER.dp, (2 * WINDOW_HALF_HEIGHT).dp)
                .ringClip(WINDOW_HALF_HEIGHT, WINDOW_INNER, WINDOW_OUTER)
                .background(
                    Brush.radialGradient(
                        WINDOW_INNER / WINDOW_OUTER to Color(0xFF111110), 0.6f to Color(0xFF262624), 1f to Color(0xFF141413),
                        center = Offset(WINDOW_OUTER * density, WINDOW_HALF_HEIGHT * density),
                        radius = WINDOW_OUTER * density,
                    ),
                )
                .then(drag(WINDOW_STEP_DP)),
        ) {
            films.forEachIndexed { j, film ->
                val d = wrapF(j - position, n)
                val a = abs(d)
                val bitmap = labels[film.id]
                // Beyond 1.5 steps a label is fully clipped by the window.
                if (a <= 1.5f && bitmap != null) {
                    val neighbour = a >= 0.5f
                    val pick = stringResource(R.string.film_pick_desc, film.name)
                    val brightness = 1f - 0.3f * minOf(a, 1f)
                    Image(
                        bitmap, contentDescription = null, contentScale = ContentScale.FillBounds,
                        colorFilter = ColorFilter.colorMatrix(ColorMatrix().apply { setToScale(brightness, brightness, brightness, 1f) }),
                        modifier = Modifier
                            .offset((WINDOW_OUTER - WHEEL_RADIUS - LABEL_W / 2).dp, (WINDOW_HALF_HEIGHT - LABEL_H / 2).dp)
                            .size(LABEL_W.dp, LABEL_H.dp)
                            .zIndex(20f - a)
                            .graphicsLayer {
                                // Rigid rotation about the dial centre, which is (WHEEL_RADIUS + 41) right of the label's left edge.
                                rotationZ = -d * STEP_DEG
                                transformOrigin = TransformOrigin((WHEEL_RADIUS + LABEL_W / 2) / LABEL_W, 0.5f)
                            }
                            .border(1.dp, Color(0x29FFFFFF), RoundedCornerShape(2.dp))
                            .clickable(role = Role.Button) { if (neighbour) tapStep(if (d > 0) 1 else -1) else onOpen() }
                            .semantics { if (neighbour) contentDescription = pick },
                    )
                }
            }
            Canvas(Modifier.fillMaxSize().zIndex(30f)) {
                val center = Offset(WINDOW_OUTER.dp.toPx(), WINDOW_HALF_HEIGHT.dp.toPx())
                val radius = WINDOW_OUTER.dp.toPx()
                val inner = WINDOW_INNER / WINDOW_OUTER
                drawRect(
                    Brush.radialGradient(
                        inner to Color(0xD9000000), inner + 9f / WINDOW_OUTER to Color.Transparent,
                        1f - 9f / WINDOW_OUTER to Color.Transparent, 1f to Color(0xCC000000),
                        center = center, radius = radius,
                    ),
                )
                drawRect(
                    Brush.verticalGradient(
                        0f to Color(0xCC000000), 9f / (2 * WINDOW_HALF_HEIGHT) to Color.Transparent,
                        1f - 9f / (2 * WINDOW_HALF_HEIGHT) to Color.Transparent, 1f to Color(0x8C000000),
                    ),
                )
            }
        }

        Box(Modifier.offset(width - DIAL_RADIUS.dp, (cy - DIAL_RADIUS).dp)) {
            Dial(position, drag(DIAL_STEP_DP), onPrev = { tapStep(-1) }, onNext = { tapStep(1) })
        }
    }
}

/** The half dial: knurled rim and metal cap turn with the wheel; its top half is "previous", the bottom half "next". */
@Composable
private fun Dial(shown: Float, drag: Modifier, onPrev: () -> Unit, onNext: () -> Unit) {
    val prev = stringResource(R.string.film_prev_desc)
    val next = stringResource(R.string.film_next_desc)
    Box(Modifier.size((2 * DIAL_RADIUS).dp)) {
        Canvas(Modifier.fillMaxSize()) {
            val center = Offset(size.width / 2, size.height / 2)
            drawCircle(Color(0xFFA5A5A1))
            rotate(shown * STEP_DEG, center) {
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
                    radius = size.width / 2 * 0.875f, center = center,
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
 * One vertical drag used by both the window and the dial. Reports the continuous offset in steps (dragging up is
 * positive, bringing the next film in); the caller turns that into a position and the crossed steps.
 */
@Composable
private fun Modifier.stepDrag(
    stepDp: Float,
    onStart: () -> Unit,
    onOffset: (Float) -> Unit,
    onEnd: () -> Unit,
): Modifier {
    val stepPx = with(LocalDensity.current) { stepDp.dp.toPx() }
    val start by rememberUpdatedState(onStart)
    val offset by rememberUpdatedState(onOffset)
    val end by rememberUpdatedState(onEnd)
    return this.pointerInput(stepPx) {
        var dy = 0f
        detectVerticalDragGestures(
            onDragStart = { dy = 0f; start() },
            onDragEnd = { end() },
            onDragCancel = { end() },
        ) { _, amount ->
            dy += amount
            offset(-dy / stepPx)
        }
    }
}

/**
 * The one window shape: the circular band (its centre is on the element's right edge, at height [cy] in dp; inner and
 * outer radius) intersected with the element's own rectangle (the vertical cut). It is built once here and clips everything drawn
 * after it, the background fill and the labels alike, so the fill and the clip cannot disagree. Drawn through
 * `clipPath`, so nothing escapes onto the neighbouring parts.
 */
private fun Modifier.ringClip(cy: Float, inner: Float, outer: Float): Modifier = drawWithCache {
    val d = density
    val centre = Offset(size.width, cy * d)
    val band = Path.combine(
        PathOperation.Difference,
        Path().apply { addOval(Rect(centre, outer * d)) },
        Path().apply { addOval(Rect(centre, inner * d)) },
    )
    val shape = Path.combine(PathOperation.Intersect, band, Path().apply { addRect(Rect(Offset.Zero, size)) })
    onDrawWithContent { clipPath(shape) { this@onDrawWithContent.drawContent() } }
}

/** The shortest signed distance in films, in -n/2..n/2. */
private fun wrapF(delta: Float, n: Int): Float {
    val half = n / 2f
    return ((delta + half) % n + n) % n - half
}
