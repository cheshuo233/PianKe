package app.pianke.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow

// A corrupt file is replaced by empty preferences, which read back as the defaults (spec section 6, row 24).
private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = "settings",
    corruptionHandler = ReplaceFileCorruptionHandler { emptyPreferences() },
)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    /** Missing keys, unknown enum names and out-of-range roll values fall back to that field's default. */
    val settings: Flow<Settings> get() = TODO()

    suspend fun update(transform: (Settings) -> Settings) {
        TODO()
    }
}
