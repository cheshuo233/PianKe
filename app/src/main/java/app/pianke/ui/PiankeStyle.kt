package app.pianke.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import app.pianke.R

/** The one place that picks fonts. */
object PiankeType {
    /** Share Tech Mono: the LCD. */
    val Lcd = FontFamily(Font(R.font.share_tech_mono))

    /** IBM Plex Mono as one family with both weights the canvas uses. */
    val Plex = FontFamily(
        Font(R.font.ibm_plex_mono_medium, FontWeight.Medium),
        Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    )
}

/** Canvas v38 is drawn 390 x 866 dp; MainActivity scales the whole UI by providing one LocalDensity. */
const val REFERENCE_WIDTH_DP = 390f
const val REFERENCE_HEIGHT_DP = 866f

val Orange = Color(0xFFE8833A)
val Ink = Color(0xFF1E1E1D)
val Bezel = Color(0xFF1B1B1A)

/** The grey body of the camera, shared by the viewfinder screen and the notices. */
fun Modifier.bodyBackground(): Modifier = this
    .fillMaxSize()
    .background(Color(0xFFC7C7C4))
    .background(Brush.verticalGradient(listOf(Color(0x33FFFFFF), Color(0x12000000))))

/** A raised light-grey button face, used by MENU, flash and flip. */
fun Modifier.bezelButton(shape: Shape): Modifier = this
    .shadow(3.dp, shape)
    .background(
        Brush.verticalGradient(listOf(Color(0xFFF6F6F4), Color(0xFFCFCFCC), Color(0xFFA9A9A5))),
        shape,
    )
