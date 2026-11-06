package app.pianke

import android.app.Application
import app.pianke.film.FilmCatalog
import app.pianke.film.FilmCatalogBroken

class PiankeApp : Application() {
    /** Null when the catalog is broken; the activity then shows only the full-screen notice. */
    var catalog: FilmCatalog? = null
        private set

    override fun onCreate() {
        super.onCreate()
        catalog = try {
            FilmCatalog.load(this)
        } catch (_: FilmCatalogBroken) {
            null
        }
    }
}
