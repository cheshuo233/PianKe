package app.pianke.develop

import android.net.Uri
import app.pianke.settings.Framing
import app.pianke.settings.GrainLevel
import java.time.LocalDateTime

/** One captured frame with the settings in force when it was taken (spec section 3.2). */
data class Shot(
    val id: String,                    // epoch millis at capture; captures are issued serially (section 8), so unique
    val filmId: String,
    val framing: Framing,
    val dateStamp: Boolean,
    val grain: GrainLevel,
    val halation: Boolean,
    val capturedAt: LocalDateTime,     // local time: date stamp and file name
)

enum class KeptReason { STORAGE_FULL, DEVELOP_FAILED }

sealed interface Outcome {
    data class Saved(val uri: Uri) : Outcome
    data class Kept(val reason: KeptReason) : Outcome
}
