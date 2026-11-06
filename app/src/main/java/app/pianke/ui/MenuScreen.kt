package app.pianke.ui

import androidx.compose.runtime.Composable
import app.pianke.settings.Settings

/**
 * The paper-card MENU: four groups (shoot, develop, feedback, others) and the About page, which is a private
 * second page of this screen. No save-location row. Writes go to [onUpdate]; [onClose] returns to the viewfinder.
 */
@Composable
fun MenuScreen(settings: Settings, onUpdate: ((Settings) -> Settings) -> Unit, onClose: () -> Unit) {
    TODO()
}
