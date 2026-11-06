package app.pianke.ui

import android.net.Uri
import app.pianke.camera.CameraIssue
import app.pianke.camera.Exposure
import app.pianke.camera.ZoomStop
import app.pianke.film.Film
import app.pianke.settings.Lens
import app.pianke.settings.Settings

data class ShooterUiState(
    val films: List<Film>, val filmIndex: Int,
    val settings: Settings,
    val stops: List<ZoomStop>, val stopIndex: Int,
    val exposure: Exposure?,
    val issue: CameraIssue?, val permission: Permission,
    val developing: Int,                   // frames in the queue; drives the LCD's "DEV" dots
    val lcdMessage: String?,               // the 2 s notices, plus the develop-failed and storage-full ones
    val lastPhoto: Uri?,
)

enum class Permission { UNKNOWN, GRANTED, DENIED, DENIED_FOREVER }

/** The roll counter of the current lens: the one place the lens picks rollBack or rollFront. */
internal val Settings.frame: Int
    get() = if (lens == Lens.BACK) rollBack else rollFront

internal fun Settings.withFrame(frame: Int): Settings =
    if (lens == Lens.BACK) copy(rollBack = frame) else copy(rollFront = frame)
