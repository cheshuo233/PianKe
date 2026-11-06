package app.pianke.ui

import android.net.Uri
import androidx.annotation.StringRes
import app.pianke.camera.CameraIssue
import app.pianke.camera.Exposure
import app.pianke.camera.ZoomStop
import app.pianke.film.Film
import app.pianke.settings.Settings

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

/** Rule 30: the front camera's only stop's mm, else `round(ZoomScale.mm(zoomRatio))`. */
internal val ShooterUiState.focalMm: Int get() = TODO()

/** Rule 30: the stop whose mm equals [focalMm]; null when between stops. */
internal val ShooterUiState.stop: ZoomStop? get() = TODO()

/** The counter drum shows unless the ritual is OFF. */
internal val ShooterUiState.counterShown: Boolean get() = TODO()

enum class Permission { UNKNOWN, GRANTED, DENIED, DENIED_FOREVER }
