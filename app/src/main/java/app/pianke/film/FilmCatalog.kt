package app.pianke.film

import android.content.Context
import android.graphics.Bitmap
import org.json.JSONObject
import java.nio.ByteBuffer
import java.time.LocalDate

class FilmCatalog private constructor(val films: List<Film>, private val handle: Long) {
    val lutSize: Int
        get() = FilmEngine.lutSize(handle)

    /** The one LUT byte source for both the viewfinder and the developed photo. */
    fun lut(film: Film): ByteBuffer = FilmEngine.lut(handle, film.index)

    /** The one place for the rule: unknown or null id means the first film. */
    fun filmOrFirst(id: String?): Film = films.firstOrNull { it.id == id } ?: films.first()

    fun develop(
        film: Film,
        grainScale: Float,
        halation: Boolean,
        date: LocalDate?,
        seed: Int,
        bitmap: Bitmap,
    ) {
        val dateYmd = if (date == null) 0 else date.year * 10000 + date.monthValue * 100 + date.dayOfMonth
        FilmEngine.develop(handle, film.index, grainScale, halation, dateYmd, seed, bitmap)
    }

    companion object {
        /**
         * Throws [FilmCatalogBroken] for any failure: an [EngineException] from open/ids, an unreadable films.json,
         * or films.json ids that do not match films.bin's ids (same ids, same order).
         */
        fun load(context: Context): FilmCatalog {
            try {
                val handle = FilmEngine.open(context.assets)
                val ids = FilmEngine.ids(handle)
                val json = context.assets.open("films.json").use { JSONObject(it.reader().readText()) }
                val entries = json.getJSONArray("films")
                val films = List(entries.length()) { i ->
                    val e = entries.getJSONObject(i)
                    Film(i, e.getString("id"), e.getString("name"), e.getInt("iso"), e.getString("process"))
                }
                check(films.map { it.id } == ids.toList()) { "films.json ids differ from films.bin" }
                return FilmCatalog(films, handle)
            } catch (e: Exception) {
                throw FilmCatalogBroken(e)
            }
        }
    }
}
