package app.pianke

import android.app.Application
import app.pianke.film.FilmCatalog

class PiankeApp : Application() {
    /** Null when the catalog is broken; the activity then shows only the full-screen notice. */
    var catalog: FilmCatalog? = null
        private set

    override fun onCreate(): Unit = TODO()
}
