package app.pianke.ui

import androidx.annotation.StringRes
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import app.pianke.R
import app.pianke.camera.CameraController
import app.pianke.camera.PreviewRenderer
import app.pianke.develop.DevelopQueue
import app.pianke.develop.Developer
import app.pianke.develop.KeptReason
import app.pianke.develop.Outcome
import app.pianke.develop.PendingStore
import app.pianke.develop.Shot
import app.pianke.feedback.Cue
import app.pianke.feedback.Feedback
import app.pianke.film.FilmCatalog
import app.pianke.settings.FlashMode
import app.pianke.settings.Lens
import app.pianke.settings.Roll
import app.pianke.settings.Settings
import app.pianke.settings.SettingsStore
import app.pianke.settings.frame
import app.pianke.settings.withFrame
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.time.LocalDateTime

/**
 * Orchestrates camera, develop, settings and feedback (spec sections 5 and 8).
 *
 * Who collects what:
 * - `init` (viewModelScope) owns the process-scoped flows: the one-shot film resolution, then `settings` ->
 *   `state.settings` (never touching `filmIndex` again), `inFlight` -> `developing`, and `outcomes` ->
 *   `lastPhoto` / LCD, then `developer.latest()`.
 * - From then on this class owns `state.filmIndex`: [selectFilmAt] sets it synchronously.
 * - [drive] owns only the Activity-scoped work, as side effects of state on the camera and the renderer.
 *
 * [CameraController] and [PreviewRenderer] belong to the Activity (H2). The renderer is only touched inside
 * [drive]; the controller is also held in [camera] while [drive] runs, so [onShutter] and [selectZoom] can reach it; the renderer is held in [renderer] the same way for [focusAt].
 */
class ShooterViewModel(
    private val catalog: FilmCatalog,
    private val settingsStore: SettingsStore,
    private val feedback: Feedback,
    private val pendingStore: PendingStore,
    private val developer: Developer,
    private val queue: DevelopQueue,
) : ViewModel() {

    private val _state = MutableStateFlow(
        ShooterUiState(
            films = catalog.films, filmIndex = 0,
            settings = Settings(),
            stops = emptyList(), zoomRatio = 1f,
            exposure = null,
            issue = null, permission = Permission.UNKNOWN,
            developing = 0, lcdMessage = null, lastPhoto = null,
            drawer = Drawer.CLOSED, rewinding = false,
        ),
    )
    val state: StateFlow<ShooterUiState> = _state.asStateFlow()

    /** Set only by [drive], cleared by identity when that `drive` ends. */
    private var camera: CameraController? = null

    /** Set and cleared together with [camera]; [focusAt] needs it for `viewToFrame`. */
    private var renderer: PreviewRenderer? = null

    /** True from the press until `capture` returns. Main thread only. */
    private var capturing = false

    private var lcdJob: Job? = null

    init {
        viewModelScope.launch {
            // Hand-off H7 / Review Focus 5: the only filmOrFirst call in ui; an unknown or missing id is written back.
            val saved = settingsStore.settings.first()
            val film = catalog.filmOrFirst(saved.filmId)
            if (film.id != saved.filmId) settingsStore.update { it.copy(filmId = film.id) }
            _state.update { it.copy(filmIndex = film.index, settings = saved) }
            settingsStore.settings.collect { settings -> _state.update { it.copy(settings = settings) } }
        }
        viewModelScope.launch {
            queue.inFlight.collect { count -> _state.update { it.copy(developing = count) } }
        }
        viewModelScope.launch {
            // Hand-off H8: subscribe first (replay is 0), then ask for the latest photo off the main thread.
            queue.outcomes
                .onSubscription {
                    val latest = withContext(Dispatchers.IO) { developer.latest() }
                    _state.update { if (it.lastPhoto == null) it.copy(lastPhoto = latest) else it }
                }
                .collect { (_, outcome) ->
                    when (outcome) {
                        is Outcome.Saved -> _state.update { it.copy(lastPhoto = outcome.uri) }
                        is Outcome.Kept -> showLcd(
                            if (outcome.reason == KeptReason.STORAGE_FULL) R.string.lcd_storage_full
                            else R.string.lcd_develop_failed,
                        )
                    }
                }
        }
    }

    /**
     * Runs until cancelled (the caller's `LaunchedEffect`, keyed on [camera]). Performs the Activity-scoped side
     * effects of state:
     * - the film index -> `renderer.setFilm`, the only call; its first emission is H3, also after recreation;
     * - grain -> `setGrain`, flash -> `setFlash`;
     * - distinct (permission granted, lens) -> `bind`; never rebinds for other settings changes;
     * - `camera.stops` -> rule 32, the reset of the zoom ratio to 1 (the only place);
     * - `camera.issue` and `camera.exposure` -> state.
     */
    suspend fun drive(camera: CameraController, renderer: PreviewRenderer): Nothing {
        this.camera = camera
        this.renderer = renderer
        try {
            coroutineScope {
                launch {
                    _state.map { it.filmIndex }.distinctUntilChanged().collect { index ->
                        renderer.setFilm(catalog.lut(catalog.films[index]), catalog.lutSize)
                    }
                }
                launch {
                    _state.map { it.settings.grain.scale }.distinctUntilChanged().collect(renderer::setGrain)
                }
                launch {
                    _state.map { it.settings.flash }.distinctUntilChanged().collect(camera::setFlash)
                }
                launch {
                    _state.map { (it.permission == Permission.GRANTED) to it.settings.lens }
                        .distinctUntilChanged()
                        .collect { (granted, lens) ->
                            if (granted) camera.bind(lens, _state.value.settings.flash, renderer.surfaceProvider)
                        }
                }
                launch {
                    camera.stops.collect { stops ->
                        // CameraX starts at ratio 1 after a bind, so this sets the ratio only: no setZoom, no cue.
                        _state.update { it.copy(stops = stops, zoomRatio = 1f) }
                    }
                }
                launch { camera.issue.collect { issue -> _state.update { it.copy(issue = issue) } } }
                launch { camera.exposure.collect { e -> _state.update { it.copy(exposure = e) } } }
            }
            awaitCancellation()
        } finally {
            if (this.camera === camera) {
                this.camera = null
                this.renderer = null
            }
        }
    }

    fun onPermission(permission: Permission) {
        _state.update { it.copy(permission = permission) }
    }

    /**
     * Rule 37: the only film-selection entry. Sets the index synchronously (the LUT follows immediately through
     * [drive]), plays NOTCH, persists the id asynchronously, and sets the drawer to CLOSED.
     */
    fun selectFilmAt(index: Int) {
        TODO()
    }

    /** The film row's entry: [selectFilmAt] of the index [delta] away, wrapping modulo the film count. */
    fun selectFilm(delta: Int) {
        selectFilmAt(Math.floorMod(_state.value.filmIndex + delta, catalog.films.size))
    }

    /** Rule 37: CLOSED -> OPEN; any other state is left alone. */
    fun openDrawer() {
        TODO()
    }

    /** Rule 37: OPEN -> CLOSED; REQUIRED is left alone. */
    fun closeDrawer() {
        TODO()
    }

    /** The drawer wheel passed one roll: plays TICK. */
    fun drawerStep() {
        TODO()
    }

    /** Rules 28 and 29: clamps [ratio], ignores the current value, `setZoom`, then plays the cue for the crossing. */
    fun selectZoom(ratio: Float) {
        TODO()
    }

    /**
     * Rule 34 (via 33): [x], [y] are normalised within the viewfinder view; `renderer.viewToFrame` then
     * `camera.focusAt`. False when there is no camera or the frame size is unknown; plays TICK on success.
     */
    suspend fun focusAt(x: Float, y: Float): Boolean = TODO()

    /**
     * Spec section 8 shutter flow, the only place it is written. [rotation] is a Surface.ROTATION_* value
     * from the Activity's orientation listener. Ignored while a capture is outstanding. Every path after
     * `tryReserve` ends in `submit` or `release`, even if this scope is cancelled mid-shot.
     */
    fun onShutter(rotation: Int) {
        val camera = camera ?: return
        if (capturing) return
        if (!queue.tryReserve()) {
            cue(Cue.REFUSE)
            return
        }
        capturing = true
        val current = _state.value
        val id = System.currentTimeMillis().toString()
        val shot = Shot(
            id = id,
            filmId = current.films[current.filmIndex].id,
            framing = current.settings.framing,
            dateStamp = current.settings.dateStamp,
            grain = current.settings.grain,
            halation = current.settings.halation,
            capturedAt = LocalDateTime.now(),
        )
        cue(Cue.SHUTTER)
        viewModelScope.launch {
            var submitted = false
            try {
                val captured = try {
                    camera.capture(pendingStore.partFile(id), rotation)
                } finally {
                    capturing = false
                }
                if (captured) {
                    withContext(NonCancellable) {
                        val committed = try {
                            withContext(Dispatchers.IO) { pendingStore.commit(shot) }
                            true
                        } catch (_: IOException) {
                            false // treated like a failed capture below: the counter does not move
                        }
                        if (committed) {
                            // The `.meta` exists, so the shot was taken: it is submitted whatever happens to the counter.
                            var rewound = false
                            val advanced = try {
                                settingsStore.update {
                                    val next = Roll.next(it.frame)
                                    rewound = next == 1
                                    it.withFrame(next)
                                }
                                true
                            } catch (_: IOException) {
                                false
                            }
                            if (advanced) cue(if (rewound) Cue.REWIND else Cue.ADVANCE)
                            queue.submit(shot)
                            submitted = true
                        }
                    }
                }
            } finally {
                if (!submitted) {
                    queue.release()
                    withContext(NonCancellable + Dispatchers.IO) {
                        pendingStore.partFile(id).delete()
                        pendingStore.delete(shot)
                    }
                    cue(Cue.REFUSE)
                    showLcd(R.string.lcd_no_shot)
                }
            }
        }
    }

    fun flipLens() {
        viewModelScope.launch {
            settingsStore.update { it.copy(lens = if (it.lens == Lens.BACK) Lens.FRONT else Lens.BACK) }
        }
    }

    fun cycleFlash() {
        viewModelScope.launch {
            settingsStore.update {
                it.copy(
                    flash = when (it.flash) {
                        FlashMode.AUTO -> FlashMode.ON
                        FlashMode.ON -> FlashMode.OFF
                        FlashMode.OFF -> FlashMode.AUTO
                    },
                )
            }
        }
    }

    /** MENU writes go through here so the NOTCH cue is played in one place. */
    fun updateSettings(transform: (Settings) -> Settings) {
        cue(Cue.NOTCH)
        viewModelScope.launch { settingsStore.update(transform) }
    }

    /** The only place that sets `lcdMessage`: shows [message] for 2 s, replacing any earlier one. */
    private fun showLcd(@StringRes message: Int) {
        lcdJob?.cancel()
        _state.update { it.copy(lcdMessage = message) }
        lcdJob = viewModelScope.launch {
            delay(LCD_MESSAGE_MS)
            _state.update { it.copy(lcdMessage = null) }
        }
    }

    /** The one place feedback is played, with the current sound and vibration switches. */
    private fun cue(cue: Cue) {
        val settings = _state.value.settings
        feedback.play(cue, settings.shutterSound, settings.vibration, settings.soundVolume.gain)
    }

    private companion object {
        const val LCD_MESSAGE_MS = 2_000L
    }
}
