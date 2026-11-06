package app.pianke.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.pianke.R
import app.pianke.settings.FlashMode
import app.pianke.settings.Roll

/** MENU key, the counter drum (`n/36`, rule 41), and the flash button (disabled while [flashEnabled] is false: front lens). */
@Composable
fun TopBar(
    frame: Int,
    flash: FlashMode,
    flashEnabled: Boolean,
    counterShown: Boolean,
    rewinding: Boolean,
    onMenu: () -> Unit,
    onFlash: () -> Unit,
) {
    Row(
        Modifier.fillMaxWidth().height(44.dp).padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        val menuShape = RoundedCornerShape(8.dp)
        Box(
            Modifier.height(38.dp).bezelButton(menuShape).clip(menuShape)
                .clickable(role = Role.Button, onClick = onMenu).padding(horizontal = 16.dp),
            contentAlignment = Alignment.Center,
        ) {
            BasicText(
                stringResource(R.string.menu_button),
                style = TextStyle(
                    fontFamily = PiankeType.Plex, fontWeight = FontWeight.SemiBold, fontSize = 12.sp,
                    letterSpacing = 2.4.sp, color = Ink,
                ),
            )
        }
        if (counterShown) CounterDrum(frame, rewinding)
        FlashButton(flash, flashEnabled, onFlash)
    }
}

@Composable
private fun CounterDrum(realFrame: Int, rewinding: Boolean) {
    val frame = if (rewinding) rewindingFrame(realFrame) else realFrame
    val description = stringResource(R.string.counter_desc, frame, Roll.FRAMES)
    Box(
        Modifier.size(86.dp, 38.dp).clip(RoundedCornerShape(19.dp)).background(Color(0xFF1A1A19))
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
            // The right neighbour comes from Roll.next; the left one is blank on the first frame.
            Digit(if (frame > 1) frame - 1 else null, 12.sp, Color(0xFF6F6F6C))
            Digit(frame, 19.sp, Color(0xFFF4F4F1))
            Digit(Roll.next(frame), 12.sp, Color(0xFF6F6F6C))
        }
        Box(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to Color(0xE60C0C0C), 0.32f to Color.Transparent,
                    0.68f to Color.Transparent, 1f to Color(0xE60C0C0C),
                ),
            ),
        )
        PointerTriangle(Modifier.align(Alignment.TopCenter), height = 6.dp)
    }
}

/** Rule 41: the drum's value while rewinding, animated from [from] down to 1 over [REWIND_MS], fast then slow. */
@Composable
private fun rewindingFrame(from: Int): Int = TODO()

@Composable
private fun Digit(value: Int?, size: androidx.compose.ui.unit.TextUnit, color: Color) {
    BasicText(
        if (value == null) "  " else "%02d".format(value),
        style = TextStyle(fontFamily = PiankeType.Plex, fontWeight = FontWeight.SemiBold, fontSize = size, color = color),
    )
}

@Composable
private fun FlashButton(flash: FlashMode, enabled: Boolean, onClick: () -> Unit) {
    val state = stringResource(
        if (!enabled) R.string.flash_unavailable else when (flash) {
            FlashMode.AUTO -> R.string.flash_auto
            FlashMode.ON -> R.string.flash_on
            FlashMode.OFF -> R.string.flash_off
        },
    )
    val description = stringResource(R.string.flash_desc, state)
    Box(
        Modifier.size(40.dp).alpha(if (enabled) 1f else 0.4f)
            .bezelButton(CircleShape)
            .clip(CircleShape)
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .clearAndSetSemantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.size(16.dp)) {
            scale(size.width / 24f, pivot = Offset.Zero) {
                val bolt = Path().apply {
                    moveTo(13f, 2f); lineTo(4f, 14f); lineTo(11f, 14f); lineTo(10f, 22f)
                    lineTo(19f, 10f); lineTo(12f, 10f); close()
                }
                drawPath(bolt, Ink, style = Stroke(1.9f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (enabled && flash == FlashMode.OFF) {
                    drawLine(Ink, Offset(3f, 3f), Offset(21f, 21f), strokeWidth = 1.9f, cap = StrokeCap.Round)
                }
            }
        }
        if (enabled && flash == FlashMode.AUTO) {
            BasicText(
                "A",
                Modifier.align(Alignment.BottomEnd).padding(end = 7.dp, bottom = 4.dp),
                style = TextStyle(fontFamily = PiankeType.Plex, fontWeight = FontWeight.SemiBold, fontSize = 9.sp, color = Ink),
            )
        }
    }
}

