package app.pianke.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.calculateTargetValue
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.splineBasedDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import app.pianke.R
import app.pianke.film.Film
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.random.Random
import kotlinx.coroutines.launch

// ---- Rule 38: the drawer geometry (spec 9.3), the only place it is written. Lengths are dp on the 390 dp reference. ----

/** The wheel's centre sits this far right of the screen's right edge, at half the height. */
private const val CENTRE_DX = 300f
private const val R_EDGE = 500f
private const val R_LABEL = 416f
private const val TRACK_HALF = 64f
private const val STEP_DEG = 15f
private const val STEP_RAD = (STEP_DEG * PI / 180.0).toFloat()
private const val VISIBLE = 3

private const val LABEL_W = 84f
private const val LABEL_H = 77f
private const val FOCUS_SCALE = 0.12f
private const val FOCUS_SHIFT = 14f
private const val OUTLINE_PAD = 6f
private const val OUTLINE_STROKE = 3f

/**
 * A label rides in a card (the outline and the name live in it, so all three turn together). The card's centre is on
 * the wheel; the label's centre is [LABEL_RISE] above the card's centre in the card's own frame, as in the mockup.
 */
private const val CARD_W = 200f
private const val CARD_H = 140f
private const val LABEL_RISE = 8f
private const val NAME_TOP = CARD_H / 2 - LABEL_RISE + LABEL_H / 2 * (1 + FOCUS_SCALE) + 7f

/** The drawer slides this far right to be fully out: its left edge, and its shadow, then lie past the screen edge. */
private const val OUT_DP = R_EDGE - CENTRE_DX + 40f
private const val CLOSE_DISTANCE_DP = 64f
private const val CLOSE_VELOCITY_DP = 1000f

private val Cream = Color(0xFFF6F0E2)
private val SlideSpec = spring<Float>(dampingRatio = 1f, stiffness = 450f)

/** What a touch sequence in the drawer has turned into: a tap until it moves past the slop. */
private enum class Gesture { UNDECIDED, WHEEL, CLOSE, IGNORED }

/** Where a label is on the wheel, for wheel position [pos]: shared by drawing and hit-testing. */
private class Pose(val x: Float, val y: Float, val theta: Float, val focus: Float)

/** 1 at the wheel position, 0 one roll away: the outline, the name and the 1.12x scale follow it. */
private fun focus(delta: Float): Float = (1f - abs(delta)).coerceIn(0f, 1f)

private fun scale(focus: Float): Float = 1f + FOCUS_SCALE * focus

/** The wheel's centre, [CENTRE_DX] right of the right edge at half the height, before any slide. */
private fun centre(width: Float, height: Float, d: Float) = Offset(width + CENTRE_DX * d, height / 2f)

private fun pose(k: Int, pos: Float, cx: Float, cy: Float, d: Float): Pose {
    val delta = k - pos
    val focus = focus(delta)
    val a = PI.toFloat() + delta * STEP_RAD
    val r = (R_LABEL + FOCUS_SHIFT * focus) * d
    return Pose(cx + r * cos(a), cy + r * sin(a), delta * STEP_RAD, focus)
}

/** The visible label under [p], the nearest to the wheel position when several overlap; null if none. */
private fun labelAt(p: Offset, pos: Float, cx: Float, cy: Float, d: Float): Int? {
    val centre = pos.roundToInt()
    var best: Int? = null
    var bestDistance = Float.MAX_VALUE
    for (k in centre - VISIBLE..centre + VISIBLE) {
        val ps = pose(k, pos, cx, cy, d)
        val s = scale(ps.focus)
        val sn = sin(ps.theta)
        val cs = cos(ps.theta)
        // Into the label's own frame: shift to its centre, then undo the clockwise rotation.
        val vx = p.x - (ps.x + LABEL_RISE * d * sn)
        val vy = p.y - (ps.y - LABEL_RISE * d * cs)
        val ux = vx * cs + vy * sn
        val uy = -vx * sn + vy * cs
        val distance = abs(k - pos)
        if (abs(ux) <= LABEL_W / 2 * s * d && abs(uy) <= LABEL_H / 2 * s * d && distance < bestDistance) {
            best = k
            bestDistance = distance
        }
    }
    return best
}

/**
 * The arc film drawer (spec 9.3). Rule 38: the geometry, gestures, inertia and snapping are written only here, with
 * the wheel position and the slide offset held in local `Animatable`s. Always composed: it slides in when [drawer] is
 * not CLOSED and out when it is; closed and fully slid out it draws nothing and takes no touches.
 *
 * Owns the 45% scrim, the tap outside and a `BackHandler` enabled while [drawer] is not CLOSED; the rightward swipe
 * and these three each call [onClose] unconditionally, because `closeDrawer` is the only REQUIRED check (rule 37).
 * REQUIRED here only disables the swipe-follow and shows the "装一卷新胶卷" line. The wheel calls [onStep] for each
 * roll it passes; a tapped label calls [onPick] with `floorMod(k, n)` once the wheel has turned to it. Label bitmaps
 * come from `rememberLabels`.
 *
 * Nothing recomposes per frame: the wheel position, the slide offset and the swipe offset are read only in draw and
 * layer lambdas; composition changes only when the set of visible labels does.
 */
@Composable
fun FilmDrawer(
    films: List<Film>, filmIndex: Int, drawer: Drawer,
    onStep: () -> Unit, onPick: (Int) -> Unit, onClose: () -> Unit,
) {
    val n = films.size
    val closed = drawer == Drawer.CLOSED
    val required = drawer == Drawer.REQUIRED
    val density = LocalDensity.current.density
    val outPx = OUT_DP * density

    /** The wheel position in rolls, unwrapped: label k sits at angle (k - position) * 15 degrees. */
    val position = remember { Animatable(filmIndex.toFloat()) }
    val slide = remember { Animatable(outPx) }
    var swipe by remember { mutableFloatStateOf(0f) }
    val lastRound = remember { mutableIntStateOf(filmIndex) }
    val scope = rememberCoroutineScope()

    val currentOnStep by rememberUpdatedState(onStep)
    val currentOnPick by rememberUpdatedState(onPick)
    val currentOnClose by rememberUpdatedState(onClose)
    val currentClosed by rememberUpdatedState(closed)
    val currentRequired by rememberUpdatedState(required)

    LaunchedEffect(closed) {
        if (closed) {
            // Fold the swipe offset into the slide so that the slide-out starts where the finger left the drawer.
            val extra = swipe
            swipe = 0f
            slide.snapTo(slide.value + extra)
            slide.animateTo(outPx, SlideSpec)
        } else {
            if (slide.value >= outPx - 1f || Math.floorMod(position.value.roundToInt(), n) != filmIndex) {
                position.snapTo(filmIndex.toFloat())
                lastRound.intValue = filmIndex
            }
            slide.animateTo(0f, SlideSpec)
        }
    }
    // The ritual can turn OPEN into REQUIRED while a swipe is under the finger: the swipe then goes back.
    LaunchedEffect(required) {
        if (required && swipe != 0f) animate(swipe, 0f, animationSpec = SlideSpec) { v, _ -> swipe = v }
    }
    BackHandler(enabled = !closed) { onClose() }

    val shown by remember { derivedStateOf { slide.value < outPx - 0.5f } }
    // Before the early return, so the decoded labels outlive the drawer's slide-out.
    val labels = rememberLabels(films)
    if (closed && !shown) return

    /** One `onStep` per change of the nearest roll, as the finger or a settling animation moves the wheel; a tapped pick's turn plays none. */
    fun report() {
        val r = position.value.roundToInt()
        if (r != lastRound.intValue) {
            lastRound.intValue = r
            currentOnStep()
        }
    }

    /** Rests the wheel on a whole roll, after a touch stopped it and did not pick. */
    fun settle() {
        val r = position.value.roundToInt().toFloat()
        if (position.value != r) scope.launch { position.animateTo(r, spring(1f)) { report() } }
    }

    /** Turns the wheel to label [k] in about 200 ms, then picks it; a touch that stops the animation cancels the pick. */
    fun pick(k: Int) {
        scope.launch {
            if (position.value.roundToInt() == k) {
                if (position.value != k.toFloat()) launch { position.animateTo(k.toFloat(), spring(1f)) }
            } else {
                position.animateTo(k.toFloat(), tween(200, easing = FastOutSlowInEasing))
                lastRound.intValue = k
            }
            currentOnPick(Math.floorMod(k, n))
        }
    }

    val centreK by remember { derivedStateOf { position.value.roundToInt() } }
    val radiusLabel = R_LABEL * density
    val arcPerRoll = radiusLabel * STEP_RAD

    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            while (true) {
                awaitPointerEventScope {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    val (x0, cy) = centre(size.width.toFloat(), size.height.toFloat(), density)
                    val cx = x0 + slide.value + swipe
                    val inside = hypot(down.position.x - cx, down.position.y - cy) <= R_EDGE * density
                    val live = !currentClosed
                    val stopped = live && inside
                    if (stopped) scope.launch { position.stop() }
                    val startPos = position.value
                    val tracker = VelocityTracker()
                    tracker.addPosition(down.uptimeMillis, down.position)
                    val slop = viewConfiguration.touchSlop
                    var mode = Gesture.UNDECIDED
                    var angle = atan2(down.position.y - cy, down.position.x - cx)
                    var turned = 0f
                    var last = down.position
                    while (true) {
                        val event = awaitPointerEvent()
                        val change = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!change.pressed) break
                        last = change.position
                        tracker.addPosition(change.uptimeMillis, last)
                        val dx = last.x - down.position.x
                        val dy = last.y - down.position.y
                        if (mode == Gesture.UNDECIDED && hypot(dx, dy) > slop) {
                            val rightward = abs(dx) > abs(dy) && dx > 0
                            mode = when {
                                !live || !inside -> Gesture.IGNORED
                                rightward && currentRequired -> Gesture.IGNORED
                                rightward -> Gesture.CLOSE
                                else -> Gesture.WHEEL
                            }
                        }
                        // REQUIRED can arrive mid-swipe: the swipe then goes back (LaunchedEffect(required)) and the release does nothing.
                        if (mode == Gesture.CLOSE && currentRequired) mode = Gesture.IGNORED
                        when (mode) {
                            Gesture.WHEEL -> {
                                val now = atan2(last.y - cy, last.x - cx)
                                var step = now - angle
                                // The finger passes angle +-pi (straight left of the centre) every time it crosses the middle.
                                if (step > PI) step -= 2 * PI.toFloat() else if (step < -PI) step += 2 * PI.toFloat()
                                angle = now
                                turned += step
                                val target = startPos - turned / STEP_RAD
                                scope.launch { position.snapTo(target); report() }
                            }
                            Gesture.CLOSE -> swipe = dx.coerceAtLeast(0f)
                            else -> Unit
                        }
                        change.consume()
                    }
                    if (!live) return@awaitPointerEventScope
                    when (mode) {
                        Gesture.UNDECIDED -> if (!inside) {
                            currentOnClose()
                        } else {
                            val k = labelAt(last, position.value, cx, cy, density)
                            if (k != null) pick(k) else settle()
                        }
                        Gesture.WHEEL -> {
                            // Angular speed of the finger about the centre, as arc length at the wheel radius.
                            val v = tracker.calculateVelocity()
                            val rx = last.x - cx
                            val ry = last.y - cy
                            val omega = (rx * v.y - ry * v.x) / (rx * rx + ry * ry).coerceAtLeast(1f)
                            val rollsPerSecond = -omega / STEP_RAD
                            val from = startPos - turned / STEP_RAD
                            val landing = splineBasedDecay<Float>(this).calculateTargetValue(0f, -omega * radiusLabel)
                            val target = (from + landing / arcPerRoll).roundToInt().toFloat()
                            // A spring that must turn back would overshoot first; leave the speed out then.
                            val speed = if (rollsPerSecond * (target - from) > 0f) rollsPerSecond else 0f
                            scope.launch {
                                position.animateTo(target, spring(1f, 150f), speed) { report() }
                            }
                        }
                        Gesture.CLOSE -> {
                            if (swipe > CLOSE_DISTANCE_DP * density || tracker.calculateVelocity().x > CLOSE_VELOCITY_DP * density) {
                                currentOnClose()
                            } else {
                                scope.launch { animate(swipe, 0f, animationSpec = SlideSpec) { v, _ -> swipe = v } }
                                settle()
                            }
                        }
                        Gesture.IGNORED -> if (stopped) settle()
                    }
                }
            }
        },
    ) {
        // The scrim: 45% black over everything outside the drawer, fading with the slide.
        Box(
            Modifier.fillMaxSize()
                .graphicsLayer { alpha = (1f - (slide.value + swipe) / outPx).coerceIn(0f, 1f) }
                .background(Color.Black.copy(alpha = 0.45f)),
        )
        // The drawer: one layer, slid as a whole.
        BoxWithConstraints(Modifier.fillMaxSize().graphicsLayer { translationX = slide.value + swipe }) {
            val (cx, cy) = centre(constraints.maxWidth.toFloat(), constraints.maxHeight.toFloat(), density)
            DrawerPanel()
            for (k in centreK - VISIBLE..centreK + VISIBLE) {
                val film = films[Math.floorMod(k, n)]
                val bitmap = labels[film.id] ?: continue
                val desc = stringResource(R.string.drawer_label_desc, film.name)
                val isCurrent = Math.floorMod(k, n) == filmIndex
                Box(
                    Modifier.size(CARD_W.dp, CARD_H.dp).zIndex(10f - abs(k - centreK)).graphicsLayer {
                        val ps = pose(k, position.value, cx, cy, density)
                        translationX = ps.x - size.width / 2
                        translationY = ps.y - size.height / 2
                        rotationZ = (k - position.value) * STEP_DEG
                    },
                ) {
                    val labelCentreY = CARD_H / 2 - LABEL_RISE
                    // The current roll's outline, drawn at the label's size and fading with its distance from the middle.
                    Canvas(Modifier.fillMaxSize()) {
                        val focus = focus(k - position.value)
                        if (focus > 0f) {
                            val s = scale(focus)
                            val w = (LABEL_W * s + 2 * OUTLINE_PAD) * density
                            val h = (LABEL_H * s + 2 * OUTLINE_PAD) * density
                            val inset = OUTLINE_STROKE / 2 * density
                            drawRoundRect(
                                Orange.copy(alpha = focus),
                                Offset(size.width / 2 - w / 2 + inset, labelCentreY * density - h / 2 + inset),
                                Size(w - 2 * inset, h - 2 * inset),
                                CornerRadius(8f * density - inset),
                                Stroke(OUTLINE_STROKE * density),
                            )
                        }
                    }
                    Image(
                        bitmap, contentDescription = null, contentScale = ContentScale.FillBounds,
                        modifier = Modifier
                            .offset(((CARD_W - LABEL_W) / 2).dp, (labelCentreY - LABEL_H / 2).dp)
                            .size(LABEL_W.dp, LABEL_H.dp)
                            .graphicsLayer {
                                val s = scale(focus(k - position.value))
                                scaleX = s
                                scaleY = s
                            }
                            .shadow(5.dp, RoundedCornerShape(2.dp), clip = false)
                            .semantics {
                                role = Role.Button
                                contentDescription = desc
                                selected = isCurrent
                                onClick { pick(k); true }
                            },
                    )
                    BasicText(
                        film.name.uppercase(),
                        modifier = Modifier.align(Alignment.TopCenter).offset(y = NAME_TOP.dp)
                            .graphicsLayer { alpha = focus(k - position.value) },
                        style = TextStyle(
                            color = Cream, fontSize = 11.sp, fontFamily = PiankeType.Plex, fontWeight = FontWeight.Medium,
                        ),
                        maxLines = 1, softWrap = false,
                    )
                }
            }
            if (required) {
                BasicText(
                    stringResource(R.string.drawer_required),
                    modifier = Modifier.align(Alignment.TopEnd).offset { IntOffset((-4 * density).roundToInt(), (cy - 338 * density).roundToInt()) },
                    style = TextStyle(color = Cream, fontSize = 11.sp, fontFamily = PiankeType.Plex, fontWeight = FontWeight.SemiBold),
                    maxLines = 1, softWrap = false,
                )
            }
        }
    }
}

/** The drawer's static look: shadow, brushed panel with its raised rim, the recessed track, grip and index. */
@Composable
private fun DrawerPanel() {
    Canvas(Modifier.fillMaxSize()) {
        val d = density
        val c = centre(size.width, size.height, d)
        val edge = R_EDGE * d
        val wheel = R_LABEL * d
        val blur = 28f * d

        // Shadow on the page: 14 dp blur, down and to the left.
        drawCircle(
            Brush.radialGradient(
                0f to Color(0x96000000), (edge - blur) / (edge + blur) to Color(0x96000000),
                edge / (edge + blur) to Color(0x4B000000), 1f to Color.Transparent,
                center = c + Offset(-6f * d, 6f * d), radius = edge + blur,
            ),
            radius = edge + blur, center = c + Offset(-6f * d, 6f * d),
        )
        // Light brushed silver.
        drawCircle(
            Brush.verticalGradient(0f to Color(0xFFD6D5D2), 1f to Color(0xFFBCBBB8), startY = 0f, endY = size.height),
            radius = edge, center = c,
        )
        clipPath(Path().apply { addOval(Rect(c, edge)) }) {
            val rnd = Random(5)
            var y = 0f
            while (y < size.height) {
                val v = rnd.nextFloat() - 0.5f
                drawRect(
                    (if (v > 0) Color.White else Color.Black).copy(alpha = abs(v) * 0.06f),
                    Offset(0f, y), Size(size.width, d.coerceAtLeast(1f)),
                )
                y += d.coerceAtLeast(1f)
            }
        }
        // The raised rim, 5 dp in from the edge.
        drawCircle(Color(0x26FFFFFF), radius = edge - 5f * d, center = c, style = Stroke(11f * d))
        drawCircle(Color(0x59FFFFFF), radius = edge - 5f * d, center = c, style = Stroke(5f * d))
        drawCircle(Color(0x33000000), radius = edge, center = c, style = Stroke(1.5f * d))

        // The recessed track, 64 dp either side of the wheel radius.
        drawCircle(
            Brush.radialGradient(
                0f to Color(0xFF464543), (wheel - TRACK_HALF * d) / (wheel + TRACK_HALF * d) to Color(0xFF464543),
                1f to Color(0xFF5F5D5B), center = c, radius = wheel + TRACK_HALF * d,
            ),
            radius = wheel, center = c, style = Stroke(2 * TRACK_HALF * d),
        )
        drawCircle(Color(0x66000000), radius = wheel - TRACK_HALF * d, center = c, style = Stroke(2f * d))
        drawCircle(Color(0x33FFFFFF), radius = wheel + TRACK_HALF * d, center = c, style = Stroke(1.5f * d))

        // The grip on the arc, and the fixed orange index to the right of the current roll.
        drawRoundRect(
            Color(0xFF96948E), Offset(c.x - edge + 7f * d, c.y - 210f * d), Size(4f * d, 40f * d), CornerRadius(2f * d),
        )
        drawPath(
            Path().apply {
                moveTo(c.x - wheel + 70f * d, c.y)
                lineTo(c.x - wheel + 80f * d, c.y - 7f * d)
                lineTo(c.x - wheel + 80f * d, c.y + 7f * d)
                close()
            },
            Orange,
        )
    }
}
