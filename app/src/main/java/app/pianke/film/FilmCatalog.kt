package app.pianke.film

import android.content.Context
import android.graphics.Bitmap
import java.nio.ByteBuffer
import java.time.LocalDate

class FilmCatalog private constructor(val films: List<Film>, private val handle: Long) {
    val lutSize: Int
        get() = TODO()

    /** The one LUT byte source for both the viewfinder and the developed photo. */
    fun lut(film: Film): ByteBuffer = TODO()

    fun develop(
        film: Film,
        grainScale: Float,
        halation: Boolean,
        date: LocalDate?,
        seed: Int,
        bitmap: Bitmap,
    ): Unit = TODO()

    companion object {
        /** Throws [FilmCatalogBroken]. */
        fun load(context: Context): FilmCatalog = TODO()
    }
}
