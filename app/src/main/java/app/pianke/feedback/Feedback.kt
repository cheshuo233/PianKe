package app.pianke.feedback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.MediaActionSound
import android.media.SoundPool
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import app.pianke.R

enum class Cue { NOTCH, SHUTTER, ADVANCE, REWIND, REFUSE }

/**
 * Haptics and short sounds. The sound flag and the vibration flag come in as parameters;
 * the ringer-mode check and the forced-shutter-sound rule (spec section 6, row 22) live only here.
 * Lives for the process, so there is no release().
 */
class Feedback(context: Context) {
    private val audio = context.getSystemService(AudioManager::class.java)
    private val vibrator: Vibrator = context.getSystemService(VibratorManager::class.java).defaultVibrator
    private val forcedShutter = MediaActionSound()

    private val pool = SoundPool.Builder()
        .setMaxStreams(2)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build(),
        )
        .build()

    // REFUSE has no sound. A sound not yet loaded when played is simply skipped by SoundPool.
    private val sounds = mapOf(
        Cue.NOTCH to pool.load(context, R.raw.notch, 1),
        Cue.SHUTTER to pool.load(context, R.raw.shutter, 1),
        Cue.ADVANCE to pool.load(context, R.raw.advance, 1),
        Cue.REWIND to pool.load(context, R.raw.rewind, 1),
    )

    /** True when this device insists on a shutter sound; the single check also used by [play]. */
    val shutterSoundForced: Boolean = mustPlayShutterSound()

    init {
        Log.i(TAG, "shutterSoundForced=$shutterSoundForced")
    }

    /**
     * [volume] (0..1) scales every sound this class plays through its own pool; the forced system shutter sound
     * goes through the system stream and is not scaled. That split is decided only here.
     */
    fun play(cue: Cue, sound: Boolean, vibration: Boolean, volume: Float) {
        if (cue == Cue.SHUTTER && shutterSoundForced) {
            Log.i(TAG, "SHUTTER: MediaActionSound (forced)")
            forcedShutter.play(MediaActionSound.SHUTTER_CLICK)
        } else if (sound && audio.ringerMode == AudioManager.RINGER_MODE_NORMAL) {
            if (cue == Cue.SHUTTER) Log.i(TAG, "SHUTTER: pool sound")
            sounds[cue]?.let { pool.play(it, volume, volume, 1, 0, 1f) }
        } else if (cue == Cue.SHUTTER) {
            Log.i(TAG, "SHUTTER: silent")
        }
        if (vibration) vibrator.vibrate(effect(cue))
    }

    // MediaActionSound.mustPlayShutterSound() is public only from API 33. On API 31-32 there is no public
    // way to ask, so those devices are treated as not forced.
    private fun mustPlayShutterSound(): Boolean =
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && MediaActionSound.mustPlayShutterSound()

    private companion object {
        const val TAG = "PiankeFeedback"
    }

    private fun effect(cue: Cue): VibrationEffect = when (cue) {
        Cue.NOTCH -> primitiveOr(VibrationEffect.Composition.PRIMITIVE_TICK, VibrationEffect.EFFECT_TICK)
        Cue.SHUTTER -> primitiveOr(VibrationEffect.Composition.PRIMITIVE_CLICK, VibrationEffect.EFFECT_CLICK)
        Cue.ADVANCE -> tremor(pulses = 6, onMs = 30L, offMs = 20L)      // about 0.3 s
        Cue.REWIND -> tremor(pulses = 9, onMs = 40L, offMs = 30L)       // about 0.63 s
        Cue.REFUSE -> VibrationEffect.createPredefined(VibrationEffect.EFFECT_DOUBLE_CLICK)
    }

    private fun primitiveOr(primitive: Int, fallback: Int): VibrationEffect =
        if (vibrator.areAllPrimitivesSupported(primitive)) {
            VibrationEffect.startComposition().addPrimitive(primitive).compose()
        } else {
            VibrationEffect.createPredefined(fallback)
        }

    private fun tremor(pulses: Int, onMs: Long, offMs: Long): VibrationEffect {
        val timings = LongArray(pulses * 2) { if (it % 2 == 0) offMs else onMs }
        val amplitudes = IntArray(pulses * 2) { if (it % 2 == 0) 0 else 80 }
        return VibrationEffect.createWaveform(timings, amplitudes, -1)
    }
}
