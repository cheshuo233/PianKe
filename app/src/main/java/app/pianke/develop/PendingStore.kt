package app.pianke.develop

import java.io.File
import java.util.Properties

/**
 * Frames written to `filesDir/pending/` so they survive process death (contract K4, spec section 3.4; rule row 12).
 * Plain `java.io` / `java.util` only, so a JVM unit test can run it; logs through `android.util.Log`
 * (the unit-test config sets `isReturnDefaultValues = true`).
 *
 * Write order is the rule: `<id>.jpg.part` (written by the caller via [partFile]) -> `<id>.jpg`
 * -> `<id>.meta.part` -> `<id>.meta`. A `.meta` on disk means "the shot was taken".
 */
class PendingStore(private val dir: File) {
    /** Where CameraX writes the raw JPEG: `<dir>/<id>.jpg.part`. */
    fun partFile(id: String): File = TODO()

    /** Steps 2-4 of the write order. Throws IOException on failure. */
    fun commit(shot: Shot): Unit = TODO()

    /**
     * Returns every shot whose `.meta` decodes with `v=1` and whose `.jpg` exists.
     * Deletes: `.jpg` without `.meta`, every `.part`, and a `.meta` whose `.jpg` is gone.
     * Keeps both files, logs and skips a `.meta` that does not decode or has an unknown `v`.
     */
    fun recover(): List<Shot> = TODO()

    fun frame(shot: Shot): File = TODO()

    /** Removes the `.jpg` and the `.meta`. */
    fun delete(shot: Shot): Unit = TODO()

    // The only place that knows the meta format (keys v, film, framing, dateStamp, grain, halation, capturedAt).
    private fun encode(shot: Shot): Properties = TODO()

    /** Null when keys are missing, values unparseable, or `v` is not [META_VERSION]. */
    private fun decode(id: String, props: Properties): Shot? = TODO()

    private companion object {
        const val META_VERSION = 1
    }
}
