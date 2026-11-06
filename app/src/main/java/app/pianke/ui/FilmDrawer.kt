package app.pianke.ui

import androidx.compose.runtime.Composable
import app.pianke.film.Film

/**
 * The arc film drawer (spec 9.3). Rule 38: the geometry, gestures, inertia and snapping are written only here, with
 * the wheel position and the slide offset held in local `Animatable`s. Always composed: it slides in when [drawer] is
 * not CLOSED and out when it is; closed and fully slid out it draws nothing and takes no touches.
 *
 * Owns the 45% scrim, the tap outside and a `BackHandler` enabled while [drawer] is not CLOSED; the rightward swipe
 * and these three each call [onClose] unconditionally, because `closeDrawer` is the only REQUIRED check (rule 37).
 * REQUIRED here only disables the swipe-follow and shows the "装一卷新胶卷" line. The wheel calls [onStep] for each
 * roll it passes; a tapped label calls [onPick] with `floorMod(k, n)` once the wheel has turned to it. Label bitmaps
 * come from `rememberLabels`.
 */
@Composable
fun FilmDrawer(
    films: List<Film>, filmIndex: Int, drawer: Drawer,
    onStep: () -> Unit, onPick: (Int) -> Unit, onClose: () -> Unit,
) {
    TODO()
}
