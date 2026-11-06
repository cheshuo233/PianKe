package app.pianke.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pianke.R
import app.pianke.settings.Framing
import app.pianke.settings.GrainLevel
import app.pianke.settings.Settings

private val CardInk = Color(0xFF2E2A26)
private val CardMuted = Color(0xFF6E665A)

/**
 * The paper-card MENU: four groups (shoot, develop, feedback, others) and the About page, which is a private
 * second page of this screen. No save-location row. Writes go to [onUpdate]; [onClose] returns to the viewfinder.
 * It is an opaque overlay: it swallows touches so the viewfinder beneath stays untouched, and it is the only
 * place that handles Back for About -> MENU -> viewfinder.
 */
@Composable
fun MenuScreen(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit, onClose: () -> Unit) {
    var about by rememberSaveable { mutableStateOf(false) }
    BackHandler { if (about) about = false else onClose() }
    val context = LocalContext.current
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }

    Column(
        Modifier.bodyBackground().pointerInput(Unit) {}.windowInsetsPadding(WindowInsets.systemBars)
            .padding(start = 18.dp, end = 18.dp, bottom = 24.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(56.dp), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            val closeDescription = stringResource(if (about) R.string.about_back_desc else R.string.menu_close_desc)
            Box(
                Modifier.size(40.dp).shadow(4.dp, CircleShape).clip(CircleShape)
                    .background(Brush.verticalGradient(listOf(Color.White, Color(0xFFECE9E3), Color(0xFFD7D3CC))))
                    .clickable(role = Role.Button) { if (about) about = false else onClose() }
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
                Groups(settings, version, onUpdate, openAbout = { about = true })
                Box(Modifier.weight(1f))
                Footer(version)
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
private fun Groups(settings: Settings, version: String, onUpdate: ((Settings) -> Settings) -> Unit, openAbout: () -> Unit) {
    Group(R.string.menu_group_shoot_en, R.string.menu_group_shoot) {
        ValueRow(
            R.string.menu_framing,
            stringResource(if (settings.framing == Framing.SQUARE) R.string.menu_framing_square else R.string.menu_framing_43),
        ) { onUpdate { it.copy(framing = if (it.framing == Framing.SQUARE) Framing.FOUR_THREE else Framing.SQUARE) } }
        ToggleRow(R.string.menu_date_stamp, settings.dateStamp) { on -> onUpdate { it.copy(dateStamp = on) } }
        ToggleRow(R.string.menu_grid, settings.grid) { on -> onUpdate { it.copy(grid = on) } }
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
        ToggleRow(R.string.menu_shutter_sound, settings.shutterSound) { on -> onUpdate { it.copy(shutterSound = on) } }
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

@Composable
private fun ToggleRow(label: Int, on: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).toggleable(on, role = Role.Switch, onValueChange = onChange),
        horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicText(stringResource(label), style = TextStyle(fontSize = 15.sp, fontWeight = FontWeight.Medium, color = CardInk))
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
