package app.pianke.ui

import android.net.Uri
import androidx.annotation.StringRes
import app.pianke.camera.CameraIssue
import app.pianke.camera.Exposure
import app.pianke.camera.ZoomScale
import app.pianke.camera.ZoomStop
import app.pianke.film.Film
import app.pianke.settings.Lens
import app.pianke.settings.Settings
import kotlin.math.roundToInt

/** REQUIRED: after the HIGH-ritual rewind a roll must be picked before the drawer can close (rule 37). */
enum class Drawer { CLOSED, OPEN, REQUIRED }

data class ShooterUiState(
    val films: List<Film>, val filmIndex: Int,
    val settings: Settings,
    val stops: List<ZoomStop>, val zoomRatio: Float,   // the current stop is derived from the ratio (rule 30)
    val exposure: Exposure?,
    val issue: CameraIssue?, val permission: Permission,
    val developing: Int,                   // frames in the queue; drives the LCD's "DEV" dots
    @StringRes val lcdMessage: Int?,             // the 2 s notices, plus the develop-failed and storage-full ones
    val lastPhoto: Uri?,
    val drawer: Drawer,
    val rewinding: Boolean,                // the rewind ritual is running, for [REWIND_MS]
)

/** The rewind's length; the ViewModel's timer and the counter drum's animation share this one value. */
internal const val REWIND_MS = 1_500L

/** The stop whose ratio equals the current one; the one place "on a stop" starts (ruling S1). */
private val ShooterUiState.ratioStop: ZoomStop? get() = stops.firstOrNull { it.ratio == zoomRatio }

/**
 * Rule 30: on the front lens the single stop's mm (the back formula while `stops` is still empty); on the back lens
 * the ratio-matched stop's mm, else `round(ZoomScale.mm(zoomRatio))`.
 */
internal val ShooterUiState.focalMm: Int
    get() = (if (settings.lens == Lens.FRONT) stops.firstOrNull()?.mm else null)
        ?: ratioStop?.mm
        ?: ZoomScale.mm(zoomRatio).roundToInt()

/** Rule 30 (ruling S1): the stop at the current ratio, else the stop whose mm equals [focalMm]; null between stops. */
internal val ShooterUiState.stop: ZoomStop? get() = ratioStop ?: stops.firstOrNull { it.mm == focalMm }

/** The counter drum shows unless the ritual is OFF. */
internal val ShooterUiState.counterShown: Boolean get() = TODO()

enum class Permission { UNKNOWN, GRANTED, DENIED, DENIED_FOREVER }
