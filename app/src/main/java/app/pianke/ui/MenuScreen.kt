package app.pianke.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pianke.R
import app.pianke.settings.Framing
import app.pianke.settings.GrainLevel
import app.pianke.settings.Ritual
import app.pianke.settings.Settings
import app.pianke.settings.SoundVolume
import app.pianke.settings.ZoomMode
import kotlin.math.roundToInt
import kotlinx.coroutines.launch

private val CardInk = Color(0xFF2E2A26)
private val CardMuted = Color(0xFF6E665A)

/**
 * The paper-card MENU: four groups (shoot, develop, feedback, others) and the About page, which is a private
 * second page of this screen. No save-location row. Writes go to [onUpdate]; [onClose] returns to the viewfinder.
 * It is an opaque overlay: it swallows touches so the viewfinder beneath stays untouched, and it is the only
 * place that handles Back for About -> MENU -> viewfinder.
 */
@Composable
fun MenuScreen(settings: Settings, shutterSoundForced: Boolean, onUpdate: ((Settings) -> Settings) -> Unit, onClose: () -> Unit) {
    var about by rememberSaveable { mutableStateOf(false) }
    val back = { if (about) about = false else onClose() }
    BackHandler(onBack = back)
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }

    // The touch blocker spans the physical screen, so the dial's overhang in the side margins is out of reach too.
    Box(
        Modifier.requiredWidth(REFERENCE_WIDTH_DP.dp + LocalEdgeMargin.current * 2f).fillMaxHeight().pointerInput(Unit) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            Modifier.width(REFERENCE_WIDTH_DP.dp).bodyBackground().windowInsetsPadding(WindowInsets.systemBars)
                .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
        ) {
            Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                val closeDescription = stringResource(if (about) R.string.about_back_desc else R.string.menu_close_desc)
                Box(
                    Modifier.size(40.dp).shadow(4.dp, CircleShape).clip(CircleShape)
                        .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFECE9E3), Color(0xFFD7D3CC))))
                        .clickable(role = Role.Button, onClick = back)
                        .semantics { contentDescription = closeDescription },
                    contentAlignment = Alignment.Center,
                ) {
                    Canvas(Modifier.size(14.dp)) {
                        val stroke = 2.2.dp.toPx()
                        drawLine(Color(0xFF4A453F), Offset(size.width * 0.25f, size.height * 0.25f), Offset(size.width * 0.75f, size.height * 0.75f), stroke, StrokeCap.Round)
                        drawLine(Color(0xFF4A453F), Offset(size.width * 0.75f, size.height * 0.25f), Offset(size.width * 0.25f, size.height * 0.75f), stroke, StrokeCap.Round)
                    }
                }
            }
            Column(
                Modifier.padding(top = 6.dp).weight(1f).fillMaxWidth().shadow(8.dp, RoundedCornerShape(10.dp))
                    .background(Color(0xFFFBF9F5), RoundedCornerShape(10.dp))
                    .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 18.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
            ) {
                if (about) {
                    About(version)
                } else {
                    Header(stringResource(R.string.menu_title))
                    Groups(settings, shutterSoundForced, version, onUpdate, openAbout = { about = true })
                    Box(Modifier.weight(1f))
                    Footer(version)
                }
            }
        }
    }
}

@Composable
private fun Header(title: String) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        BasicText(title, style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 28.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.8.sp, color = CardInk))
        BasicText(
            stringResource(R.string.menu_tag),
            style = TextStyle(fontFamily = PiankeType.Plex, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.6.sp, color = Color(0xFFA8501A)),
        )
    }
}

@Composable
private fun Groups(settings: Settings, shutterSoundForced: Boolean, version: String, onUpdate: ((Settings) -> Settings) -> Unit, openAbout: () -> Unit) {
    Group(R.string.menu_group_shoot_en, R.string.menu_group_shoot) {
        ValueRow(
            R.string.menu_framing,
            stringResource(if (settings.framing == Framing.SQUARE) R.string.menu_framing_square else R.string.menu_framing_43),
        ) { onUpdate { it.copy(framing = if (it.framing == Framing.SQUARE) Framing.FOUR_THREE else Framing.SQUARE) } }
        ToggleRow(R.string.menu_date_stamp, settings.dateStamp) { on -> onUpdate { it.copy(dateStamp = on) } }
        ToggleRow(R.string.menu_grid, settings.grid) { on -> onUpdate { it.copy(grid = on) } }
        RitualRow(settings.ritual, onUpdate)
        ValueRow(
            R.string.menu_zoom_mode,
            stringResource(if (settings.zoomMode == ZoomMode.FREE) R.string.menu_zoom_mode_free else R.string.menu_zoom_mode_stops),
        ) { onUpdate { it.copy(zoomMode = if (it.zoomMode == ZoomMode.FREE) ZoomMode.STOPS else ZoomMode.FREE) } }
    }
    Group(R.string.menu_group_develop_en, R.string.menu_group_develop) {
        ValueRow(
            R.string.menu_grain,
            stringResource(
                when (settings.grain) {
                    GrainLevel.OFF -> R.string.menu_grain_off
                    GrainLevel.LOW -> R.string.menu_grain_low
                    GrainLevel.NORMAL -> R.string.menu_grain_normal
                    GrainLevel.HIGH -> R.string.menu_grain_high
                },
            ),
        ) { onUpdate { it.copy(grain = GrainLevel.entries[(it.grain.ordinal + 1) % GrainLevel.entries.size]) } }
        ToggleRow(R.string.menu_halation, settings.halation) { on -> onUpdate { it.copy(halation = on) } }
    }
    Group(R.string.menu_group_feedback_en, R.string.menu_group_feedback) {
        // A device that forces the shutter sound shows the row on and locked, with a note.
        ToggleRow(
            R.string.menu_shutter_sound, settings.shutterSound || shutterSoundForced,
            enabled = !shutterSoundForced, note = if (shutterSoundForced) R.string.menu_shutter_sound_forced else null,
        ) { on -> onUpdate { it.copy(shutterSound = on) } }
        ValueRow(
            R.string.menu_volume,
            stringResource(
                when (settings.soundVolume) {
                    SoundVolume.LOW -> R.string.menu_volume_low
                    SoundVolume.MEDIUM -> R.string.menu_volume_medium
                    SoundVolume.HIGH -> R.string.menu_volume_high
                },
            ),
        ) { onUpdate { it.copy(soundVolume = SoundVolume.entries[(it.soundVolume.ordinal + 1) % SoundVolume.entries.size]) } }
        ToggleRow(R.string.menu_vibration, settings.vibration) { on -> onUpdate { it.copy(vibration = on) } }
    }
    Group(R.string.menu_group_others_en, R.string.menu_group_others) {
        ValueRow(R.string.menu_about, "v$version", openAbout)
    }
}

@Composable
private fun Group(en: Int, title: Int, rows: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        BasicText(
            "${stringResource(en)} · ${stringResource(title)}",
            style = TextStyle(fontFamily = PiankeType.Plex, fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 2.8.sp, color = CardMuted),
        )
        rows()
    }
}

/** Spec 9.6: a three-stop slider labelled 关 / 中 / 高; dragging the knob or tapping a label changes the stop, release snaps to the nearest. */
@Composable
private fun RitualRow(ritual: Ritual, onUpdate: ((Settings) -> Settings) -> Unit) {
    val name = stringResource(R.string.menu_ritual)
    val labels = listOf(R.string.menu_ritual_off, R.string.menu_ritual_medium, R.string.menu_ritual_high).map { stringResource(it) }
    val knobSize = 24.dp
    val track = 150.dp
    val travel = track - knobSize // the knob's left edge runs this far across the track, 0..2 detents
    val travelPx = with(LocalDensity.current) { travel.toPx() }
    val knob = remember { Animatable(ritual.ordinal.toFloat()) } // in detent units, 0..2
    var drag by remember { mutableStateOf<Float?>(null) }
    var last by remember { mutableIntStateOf(ritual.ordinal) } // the detent last reported, so one drag reports each change once
    val scope = rememberCoroutineScope()
    LaunchedEffect(ritual) {
        // A stored value that lags behind a drag in progress must not overwrite what the finger has reported.
        if (drag == null) {
            last = ritual.ordinal
            knob.animateTo(ritual.ordinal.toFloat())
        }
    }
    fun choose(detent: Int) {
        if (detent == last) return
        last = detent
        onUpdate { it.copy(ritual = Ritual.entries[detent]) }
    }
    /** Hands the knob from the finger back to the detent [to]. */
    fun settle(to: Int) {
        scope.launch {
            knob.snapTo(drag ?: knob.value)
            drag = null
            knob.animateTo(to.toFloat())
        }
    }
    fun jump(detent: Int) {
        choose(detent)
        scope.launch { knob.animateTo(detent.toFloat()) }
    }
    val position = drag ?: knob.value
    Row(
        Modifier.fillMaxWidth().height(60.dp)
            .clearAndSetSemantics {
                contentDescription = name
                stateDescription = labels[ritual.ordinal]
                // TalkBack: activating the row steps 关 -> 中 -> 高 -> 关.
                onClick { jump((ritual.ordinal + 1) % labels.size); true }
            },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = CardInk))
        Column(Modifier.width(track)) {
            Box(
                Modifier.fillMaxWidth().height(28.dp).pointerInput(Unit) {
                    detectHorizontalDragGestures(
                        onDragStart = { drag = knob.value },
                        onDragEnd = { settle((drag ?: knob.value).roundToInt()) },
                        onDragCancel = { settle(last) },
                    ) { change, delta ->
                        change.consume()
                        val next = ((drag ?: knob.value) + delta / (travelPx / 2f)).coerceIn(0f, 2f)
                        drag = next
                        choose(next.roundToInt())
                    }
                },
                contentAlignment = Alignment.CenterStart,
            ) {
                Box(Modifier.padding(horizontal = 12.dp).fillMaxWidth().height(4.dp).background(Color(0xFFD9D5CE), RoundedCornerShape(2.dp)))
                Box(
                    Modifier.offset(travel * (position / 2f), 0.dp).size(knobSize).shadow(2.dp, CircleShape)
                        .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF1EEE8), Color(0xFFD9D5CE))), CircleShape),
                )
            }
            Box(Modifier.fillMaxWidth().height(18.dp)) {
                labels.forEachIndexed { index, label ->
                    Box(
                        Modifier.offset(travel * (index / 2f), 0.dp).width(knobSize).height(18.dp)
                            .clickable { jump(index) },
                        contentAlignment = Alignment.Center,
                    ) {
                        BasicText(label, style = TextStyle(fontSize = 11.sp, fontWeight = FontWeight.Medium, color = if (index == ritual.ordinal) CardInk else CardMuted))
                    }
                }
            }
        }
    }
}

@Composable
private fun ToggleRow(label: Int, on: Boolean, enabled: Boolean = true, note: Int? = null, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).toggleable(on, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        Column {
            BasicText(stringResource(label), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = CardInk))
            if (note != null) BasicText(stringResource(note), style = TextStyle(fontSize = 11.sp, color = CardMuted))
        }
        Box(Modifier.size(48.dp, 28.dp).background(if (on) Color(0xFFE2742B) else Color(0xFFD9D5CE), RoundedCornerShape(14.dp))) {
            Box(
                Modifier.offset(if (on) 22.dp else 2.dp, 2.dp).size(24.dp).shadow(2.dp, CircleShape)
                    .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFF1EEE8), Color(0xFFD9D5CE))), CircleShape),
            )
        }
    }
}

@Composable
private fun ValueRow(label: Int, value: String, onClick: () -> Unit) {
    val name = stringResource(label)
    Row(
        Modifier.fillMaxWidth().height(44.dp).clickable(role = Role.Button, onClick = onClick)
            .semantics(mergeDescendants = true) { contentDescription = "$name：$value" },
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(name, style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = CardInk))
        Box(
            Modifier.height(30.dp).shadow(2.dp, RoundedCornerShape(8.dp))
                .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFEEEBE5))), RoundedCornerShape(8.dp))
                .padding(horizontal = 10.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                "$value  ›",
                style = TextStyle(fontFamily = PiankeType.Plex, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = Color(0xFF3A3631)),
            )
        }
    }
}

@Composable
private fun Footer(version: String) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BasicText(
            stringResource(R.string.menu_footer_name),
            style = TextStyle(fontFamily = FontFamily.Serif, fontSize = 14.sp, fontWeight = FontWeight.Bold, letterSpacing = 4.2.sp, color = CardInk),
        )
        BasicText(
            stringResource(R.string.menu_footer_line, version),
            style = TextStyle(fontFamily = PiankeType.Plex, fontSize = 10.sp, letterSpacing = 1.8.sp, color = CardMuted),
        )
    }
}

@Composable
private fun About(version: String) {
    Header(stringResource(R.string.menu_about))
    val body = TextStyle(fontSize = 15.sp, color = CardInk, lineHeight = 22.sp)
    BasicText(stringResource(R.string.about_version, version), style = body)
    BasicText(stringResource(R.string.about_license), style = body)
    BasicText(stringResource(R.string.about_source, stringResource(R.string.about_source_url)), style = body)
    BasicText(stringResource(R.string.about_credit), style = body)
    BasicText(stringResource(R.string.about_fonts), style = body)
    BasicText(stringResource(R.string.about_trademark), style = body)
}
