package app.pianke.ui

import androidx.compose.runtime.Composable
import app.pianke.film.Film

/**
 * Rule 23, the only place for the fan: LCD slot, the arc label window, the half dial.
 * 30 degrees per film, labels and dial turn by the same angle; window drag 58 dp / dial drag 26 dp per step, snapping
 * on release; tap a visible neighbour -> [onSelectIndex]; tap the dial's top/bottom half -> [onSelect] with -1 / +1;
 * the list wraps. Each crossed step calls [onSelect] with +1 or -1 (the ViewModel plays NOTCH and switches the LUT).
 * Labels are loaded from `film.labelAsset` once and cached by id.
 */
@Composable
fun FilmRow(
    films: List<Film>,
    filmIndex: Int,
    lcd: @Composable () -> Unit,
    onSelect: (delta: Int) -> Unit,
    onSelectIndex: (index: Int) -> Unit,
) {
    TODO()
}
