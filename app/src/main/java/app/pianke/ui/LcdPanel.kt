package app.pianke.ui

import androidx.annotation.StringRes
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import app.pianke.R
import app.pianke.camera.Exposure
import app.pianke.film.Film
import kotlin.math.roundToInt

private val LcdInk = Color(0xFF1C2218)

/**
 * The 180 dp LCD (spec 9.2 table), as a liveRegion, inside its 4 dp bezel. Row 4 left is REWIND while [rewinding] (9.5), else [message] if present,
 * else "DEV" with one dot per frame in [developing], else empty; right is `{n}/{filmCount}`. Row 3 formats shutter
 * speed and aperture from [exposure].
 */
@Composable
fun LcdPanel(film: Film, filmIndex: Int, filmCount: Int, exposure: Exposure?, developing: Int, @StringRes message: Int?, rewinding: Boolean) {
    fun style(size: Int, color: Color = LcdInk) = TextStyle(fontFamily = PiankeType.Lcd, fontSize = size.sp, color = color)

    Box(
        Modifier.size(180.dp, 108.dp).background(Bezel, RoundedCornerShape(12.dp)).padding(4.dp)
            .semantics { liveRegion = LiveRegionMode.Polite },
    ) {
        Column(
            Modifier.fillMaxSize().clip(RoundedCornerShape(8.dp)).background(Color(0xFFA4AC9B))
                .padding(start = 12.dp, end = 12.dp, top = 9.dp, bottom = 8.dp),
            verticalArrangement = Arrangement.SpaceBetween,
        ) {
            LcdRow(
                stringResource(R.string.lcd_iso, film.iso),
                stringResource(R.string.lcd_process, film.process),
                style(11),
            )
            BasicText(
                film.name.uppercase(), style = style(15), maxLines = 1, softWrap = false, overflow = TextOverflow.Clip,
            )
            LcdRow(
                exposure?.let { shutterText(it) } ?: stringResource(R.string.lcd_exposure_none),
                exposure?.let { stringResource(R.string.lcd_aperture, it.aperture) } ?: "",
                style(14),
            )
            LcdRow(
                if (rewinding) stringResource(R.string.lcd_rewind) else message?.let { stringResource(it) } ?: if (developing > 0) {
                    stringResource(R.string.lcd_developing, "●".repeat(developing))
                } else {
                    ""
                },
                stringResource(R.string.lcd_film_count, filmIndex + 1, filmCount),
                style(11),
            )
        }
        Box(
            Modifier.fillMaxSize().background(
                Brush.linearGradient(0f to Color(0x38FFFFFF), 0.45f to Color.Transparent, 1f to Color.Transparent),
            ),
        )
    }
}

@Composable
private fun LcdRow(left: String, right: String, style: TextStyle) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Bottom) {
        BasicText(left, style = style, maxLines = 1)
        BasicText(right, style = style, maxLines = 1)
    }
}

/** 1/125 for fast exposures, seconds from 0.5 s up (where 1/n would print 1/1 or 1/0). */
@Composable
private fun shutterText(exposure: Exposure): String {
    val seconds = exposure.exposureNs / 1e9
    return if (seconds >= 0.5) {
        stringResource(R.string.lcd_shutter_long, seconds)
    } else {
        stringResource(R.string.lcd_shutter, (1.0 / seconds).roundToInt())
    }
}
