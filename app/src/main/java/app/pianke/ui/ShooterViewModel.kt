package app.pianke.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import app.pianke.PiankeApp
import app.pianke.camera.CameraController
import app.pianke.camera.PreviewRenderer
import app.pianke.develop.DevelopQueue
import app.pianke.develop.Developer
import app.pianke.develop.PendingStore
import app.pianke.feedback.Feedback
import app.pianke.film.FilmCatalog
import app.pianke.settings.Settings
import app.pianke.settings.SettingsStore
import kotlinx.coroutines.flow.StateFlow

/**
 * Orchestrates camera, develop, settings and feedback (spec sections 5 and 8).
 *
 * Process-scoped collaborators come in through the constructor. [CameraController] and [PreviewRenderer] are
 * Activity-scoped, so they are never fields of this class: [drive] receives them for as long as the composition
 * lives, and [onShutter], [selectStop] and the rest reach the camera only through what [drive] holds while it
 * runs (hand-off H2).
 *
 * `init` subscribes to `queue.outcomes` first (Saved -> lastPhoto; Kept -> LCD notice) and only then calls
 * `developer.latest()` on Dispatchers.IO (H8).
 */
class ShooterViewModel(
    private val context: Context,
    private val catalog: FilmCatalog,
    private val settingsStore: SettingsStore,
    private val feedback: Feedback,
    private val pendingStore: PendingStore,
    private val developer: Developer,
    private val queue: DevelopQueue,
) : ViewModel() {

    val state: StateFlow<ShooterUiState> get() = TODO()

    /**
     * Runs until cancelled (the caller's `LaunchedEffect` keyed on [camera]). While it runs it:
     * - resolves `Settings.filmId` through `catalog.filmOrFirst` and writes the resolved id back if it differed
     *   (Review Focus 5), then calls `renderer.setFilm` for that film (H3) and again on every film change;
     * - calls `renderer.setGrain` and `camera.setFlash` on settings changes;
     * - binds the camera once permission is GRANTED and again on a lens change;
     * - on each `camera.stops` emission, resets the ring to the ratio-1 stop (H4, the only place);
     * - mirrors `camera.issue` and `camera.exposure` into [state].
     * On exit (finally) it drops its references to [camera] and [renderer].
     */
    suspend fun drive(camera: CameraController, renderer: PreviewRenderer): Nothing = TODO()

    fun onPermission(permission: Permission): Unit = TODO()

    /** Wraps modulo the film count. The fan lives in FilmRow; this is only the selection. */
    fun selectFilm(delta: Int): Unit = TODO()

    fun selectFilmIndex(index: Int): Unit = TODO()

    /** The one zoom-stepping entry: no-op for the current index, else `setZoom` + NOTCH. */
    fun selectStop(index: Int): Unit = TODO()

    /**
     * Spec section 8 shutter flow, the only place it is written. [rotation] is a Surface.ROTATION_* value
     * from the Activity's orientation listener. Ignored while a capture is outstanding.
     */
    fun onShutter(rotation: Int): Unit = TODO()

    /** Switches lens; the roll counter follows the lens (state derives it from `Settings.lens`). */
    fun flipLens(): Unit = TODO()

    fun cycleFlash(): Unit = TODO()

    /** MENU writes go through here so the NOTCH cue is played in one place. */
    fun updateSettings(transform: (Settings) -> Settings): Unit = TODO()

    /** The only place that sets `lcdMessage`: shows [message] for 2 s, replacing any earlier one. */
    private fun showLcd(message: String): Unit = TODO()

    companion object {
        /** Call only when `app.catalog` is non-null (MainActivity shows the broken notice otherwise). */
        fun factory(app: PiankeApp): ViewModelProvider.Factory = TODO()
    }
}
