package app.pianke

import android.app.Application
import app.pianke.film.FilmCatalog
import app.pianke.feedback.Feedback
import app.pianke.film.FilmCatalogBroken
import app.pianke.develop.DevelopQueue
import app.pianke.develop.Developer
import app.pianke.develop.PendingStore
import app.pianke.settings.SettingsStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import java.io.File

class PiankeApp : Application() {
    /** Null when the catalog is broken; the activity then shows only the full-screen notice. */
    var catalog: FilmCatalog? = null
        private set

    lateinit var settingsStore: SettingsStore
        private set

    lateinit var feedback: Feedback
        private set

    lateinit var pendingStore: PendingStore
        private set

    /** Null exactly when [catalog] is null: developing needs the catalog. */
    var developer: Developer? = null
        private set

    var queue: DevelopQueue? = null
        private set

    /** Application lifetime; hosts the develop worker. */
    private val appScope = CoroutineScope(SupervisorJob())

    override fun onCreate() {
        super.onCreate()
        settingsStore = SettingsStore(this)
        feedback = Feedback(this)
        catalog = try {
            FilmCatalog.load(this)
        } catch (_: FilmCatalogBroken) {
            null
        }
        pendingStore = PendingStore(File(filesDir, "pending"))
        // Spec section 8, startup: step 1 is the catalog load above; a broken catalog stops here.
        catalog?.let { loaded ->
            val dev = Developer(this, loaded)
            val q = DevelopQueue(appScope, pendingStore, dev)
            developer = dev
            queue = q
            q.submitRecovered(pendingStore.recover())
        }
    }
}
