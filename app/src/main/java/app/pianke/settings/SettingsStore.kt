package app.pianke.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

// A corrupt file is replaced by empty preferences, which read back as the defaults (spec section 6, row 24).
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

// Contract K6: these key strings are what is on disk. Keys may be added, never renamed.
private val FILM_ID = stringPreferencesKey("filmId")
private val FRAMING = stringPreferencesKey("framing")
private val DATE_STAMP = booleanPreferencesKey("dateStamp")
private val GRID = booleanPreferencesKey("grid")
private val GRAIN = stringPreferencesKey("grain")
private val HALATION = booleanPreferencesKey("halation")
private val SHUTTER_SOUND = booleanPreferencesKey("shutterSound")
private val VIBRATION = booleanPreferencesKey("vibration")
private val SOUND_VOLUME = stringPreferencesKey("soundVolume")
private val FLASH = stringPreferencesKey("flash")
private val LENS = stringPreferencesKey("lens")
private val ROLL_BACK = intPreferencesKey("rollBack")
private val ROLL_FRONT = intPreferencesKey("rollFront")
private val RITUAL = stringPreferencesKey("ritual")
private val ZOOM_MODE = stringPreferencesKey("zoomMode")

// Every fallback is read from here, so the defaults stay only in Settings.kt.
private val DEFAULTS = Settings()

private inline fun <reified E : Enum<E>> Preferences.enum(key: Preferences.Key<String>, default: E): E {
    val stored = this[key]
    return enumValues<E>().firstOrNull { it.name == stored } ?: default
}

private fun Preferences.roll(key: Preferences.Key<Int>, default: Int): Int =
    this[key]?.takeIf { it in 1..Roll.FRAMES } ?: default

private fun Preferences.toSettings() = Settings(
    filmId = this[FILM_ID],
    framing = enum(FRAMING, DEFAULTS.framing),
    dateStamp = this[DATE_STAMP] ?: DEFAULTS.dateStamp,
    grid = this[GRID] ?: DEFAULTS.grid,
    grain = enum(GRAIN, DEFAULTS.grain),
    halation = this[HALATION] ?: DEFAULTS.halation,
    shutterSound = this[SHUTTER_SOUND] ?: DEFAULTS.shutterSound,
    vibration = this[VIBRATION] ?: DEFAULTS.vibration,
    soundVolume = enum(SOUND_VOLUME, DEFAULTS.soundVolume),
    flash = enum(FLASH, DEFAULTS.flash),
    lens = enum(LENS, DEFAULTS.lens),
    rollBack = roll(ROLL_BACK, DEFAULTS.rollBack),
    rollFront = roll(ROLL_FRONT, DEFAULTS.rollFront),
    ritual = enum(RITUAL, DEFAULTS.ritual),
    zoomMode = enum(ZOOM_MODE, DEFAULTS.zoomMode),
)

private fun MutablePreferences.write(s: Settings) {
    val id = s.filmId
    if (id == null) remove(FILM_ID) else this[FILM_ID] = id
    this[FRAMING] = s.framing.name
    this[DATE_STAMP] = s.dateStamp
    this[GRID] = s.grid
    this[GRAIN] = s.grain.name
    this[HALATION] = s.halation
    this[SHUTTER_SOUND] = s.shutterSound
    this[VIBRATION] = s.vibration
    this[SOUND_VOLUME] = s.soundVolume.name
    this[FLASH] = s.flash.name
    this[LENS] = s.lens.name
    this[ROLL_BACK] = s.rollBack
    this[ROLL_FRONT] = s.rollFront
    this[RITUAL] = s.ritual.name
    this[ZOOM_MODE] = s.zoomMode.name
}

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    /** Missing keys, unknown enum names and out-of-range roll values fall back to that field's default. */
    val settings: Flow<Settings> = store.data.map { it.toSettings() }

    /** Read-modify-write inside one DataStore edit, so concurrent updates are serialised. */
    suspend fun update(transform: (Settings) -> Settings) {
        store.edit { it.write(transform(it.toSettings())) }
    }
}
