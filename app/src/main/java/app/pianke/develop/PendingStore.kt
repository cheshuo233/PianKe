package app.pianke.develop

import app.pianke.settings.Framing
import app.pianke.settings.GrainLevel
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Properties
import java.util.logging.Logger

/**
 * Frames written to `filesDir/pending/` so they survive process death (contract K4, spec section 3.4; rule row 12).
 * Plain `java.*` only, so a JVM unit test can run it; `java.util.logging` reaches logcat on Android.
 *
 * Write order is the rule: `<id>.jpg.part` (written by the caller via [partFile]) -> `<id>.jpg`
 * -> `<id>.meta.part` -> `<id>.meta`. A `.meta` on disk means "the shot was taken".
 */
class PendingStore(private val dir: File) {
    private val log = Logger.getLogger("PendingStore")

    init {
        dir.mkdirs()
    }

    /** Where CameraX writes the raw JPEG: `<dir>/<id>.jpg.part`. */
    fun partFile(id: String): File = part(jpg(id))

    /** Steps 2-4 of the write order. Throws IOException on failure. */
    fun commit(shot: Shot) {
        move(partFile(shot.id), jpg(shot.id))
        val metaPart = part(meta(shot.id))
        FileOutputStream(metaPart).use { out ->
            encode(shot).store(out, null)
            out.fd.sync()
        }
        move(metaPart, meta(shot.id))
    }

    /**
     * Returns every shot whose `.meta` decodes with `v=1` and whose `.jpg` exists, sorted by id.
     * Deletes every file that is neither `*.jpg` nor `*.meta` (`.part` files and CameraX's temp file),
     * a `.jpg` without `.meta`, and a good `.meta` whose `.jpg` is gone.
     * A `.meta` that does not decode or has an unknown `v` is kept with its `.jpg`, logged and skipped,
     * even when the `.jpg` is missing.
     */
    fun recover(): List<Shot> {
        val files = dir.listFiles() ?: return emptyList()
        files.filter { !it.name.endsWith(JPG) && !it.name.endsWith(META) }.forEach(::remove)

        val shots = mutableListOf<Shot>()
        for (metaFile in files.filter { it.name.endsWith(META) }) {
            val id = metaFile.name.removeSuffix(META)
            val shot = try {
                decode(id, Properties().also { props -> metaFile.inputStream().use(props::load) })
            } catch (e: Exception) {
                log.warning("unreadable ${metaFile.name}: $e")
                null
            }
            when {
                shot == null -> log.warning("keeping ${metaFile.name}: bad meta or unknown version")
                !jpg(id).exists() -> remove(metaFile)
                else -> shots += shot
            }
        }
        files.filter { it.name.endsWith(JPG) && !meta(it.name.removeSuffix(JPG)).exists() }.forEach(::remove)
        return shots.sortedBy { it.id }
    }

    fun frame(shot: Shot): File = jpg(shot.id)

    /** Removes the `.meta` first ("taken" disappears first), then the `.jpg`. */
    fun delete(shot: Shot) {
        remove(meta(shot.id))
        remove(jpg(shot.id))
    }

    private fun jpg(id: String) = File(dir, id + JPG)

    private fun meta(id: String) = File(dir, id + META)

    private fun part(file: File) = File(file.parentFile, file.name + PART)

    private fun move(from: File, to: File) {
        Files.move(from.toPath(), to.toPath(), StandardCopyOption.ATOMIC_MOVE)
    }

    private fun remove(file: File) {
        if (file.exists() && !file.delete()) log.warning("could not delete ${file.name}")
    }

    // The only place that knows the meta format.
    private fun encode(shot: Shot) = Properties().apply {
        setProperty(KEY_V, META_VERSION.toString())
        setProperty(KEY_FILM, shot.filmId)
        setProperty(KEY_FRAMING, shot.framing.name)
        setProperty(KEY_DATE_STAMP, shot.dateStamp.toString())
        setProperty(KEY_GRAIN, shot.grain.name)
        setProperty(KEY_HALATION, shot.halation.toString())
        setProperty(KEY_CAPTURED_AT, shot.capturedAt.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME))
    }

    /** Null when `v` is not [META_VERSION]; throws on missing keys or unparseable values (the caller treats that as bad meta). */
    private fun decode(id: String, props: Properties): Shot? {
        if (props.getProperty(KEY_V) != META_VERSION.toString()) return null
        fun value(key: String) = props.getProperty(key) ?: throw IOException("missing $key")
        return Shot(
            id = id,
            filmId = value(KEY_FILM),
            framing = enumValueOf<Framing>(value(KEY_FRAMING)),
            dateStamp = value(KEY_DATE_STAMP).toBooleanStrict(),
            grain = enumValueOf<GrainLevel>(value(KEY_GRAIN)),
            halation = value(KEY_HALATION).toBooleanStrict(),
            capturedAt = LocalDateTime.parse(value(KEY_CAPTURED_AT), DateTimeFormatter.ISO_LOCAL_DATE_TIME),
        )
    }

    private companion object {
        const val META_VERSION = 1

        const val JPG = ".jpg"
        const val META = ".meta"
        const val PART = ".part"

        // K4: on-disk names, never renamed.
        const val KEY_V = "v"
        const val KEY_FILM = "film"
        const val KEY_FRAMING = "framing"
        const val KEY_DATE_STAMP = "dateStamp"
        const val KEY_GRAIN = "grain"
        const val KEY_HALATION = "halation"
        const val KEY_CAPTURED_AT = "capturedAt"
    }
}
