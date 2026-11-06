package app.pianke.feedback

import android.content.Context

enum class Cue { NOTCH, SHUTTER, ADVANCE, REWIND, REFUSE }

/**
 * Haptics and short sounds. The sound flag and the vibration flag come in as parameters;
 * the ringer-mode check and the forced-shutter-sound rule (spec section 6, row 22) live only here.
 * Lives for the process, so there is no release().
 */
class Feedback(private val context: Context) {
    fun play(cue: Cue, sound: Boolean, vibration: Boolean) {
        TODO()
    }
}
