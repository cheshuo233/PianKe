package app.pianke

import android.app.Application
import app.pianke.film.FilmCatalog
import app.pianke.feedback.Feedback
import app.pianke.film.FilmCatalogBroken
import app.pianke.settings.SettingsStore

class PiankeApp : Application() {
    /** Null when the catalog is broken; the activity then shows only the full-screen notice. */
    var catalog: FilmCatalog? = null
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var feedback: Feedback
        private set

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        feedback = Feedback(this)
        catalog = try {
            FilmCatalog.load(this)
        } catch (_: FilmCatalogBroken) {
            null
        }
    }
}
