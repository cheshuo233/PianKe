package app.pianke.ui

import androidx.compose.runtime.Composable
import app.pianke.settings.FlashMode

/** MENU key, the counter drum (`n/36`), and the flash button (disabled while [flashEnabled] is false: front lens). */
@Composable
fun TopBar(
    frame: Int,
    flash: FlashMode,
    flashEnabled: Boolean,
    onMenu: () -> Unit,
    onFlash: () -> Unit,
) {
    TODO()
}
