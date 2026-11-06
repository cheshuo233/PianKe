package app.pianke.ui

import androidx.compose.runtime.Composable
import app.pianke.film.Film

/**
 * The arc film drawer (spec 9.3). Rule 38: the geometry, gestures, inertia and snapping are written only here, with
 * the wheel position and open offset held in local `Animatable`s. The wheel calls [onStep] for each roll it passes;
 * a tapped label calls [onPick] with `floorMod(k, n)` once the wheel has turned to it; a rightward swipe, a tap
 * outside and Back call [onClose]. While [required] there is no way to close and the "装一卷新胶卷" line shows.
 */
@Composable
fun FilmDrawer(
    films: List<Film>, filmIndex: Int, required: Boolean,
    onStep: () -> Unit, onPick: (Int) -> Unit, onClose: () -> Unit,
) {
    TODO()
}
