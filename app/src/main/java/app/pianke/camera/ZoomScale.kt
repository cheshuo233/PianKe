package app.pianke.camera

import kotlin.math.abs
import kotlin.math.ln

/** One mark on the zoom scale. [marked]: this focal length is a stop, so the scale prints its number. */
data class Tick(val mm: Int, val marked: Boolean)

/** The zoom scale's maths: the only place that converts mm and ratio, builds the tick table and finds stops. */
object ZoomScale {
    /** The 23 mm main lens is ratio 1; every back ratio is mm / MAIN_MM, written only here. */
    const val MAIN_MM = 23f

    /** mm -> ratio. */
    fun ratio(mm: Float): Float = mm / MAIN_MM

    /** ratio -> mm; meaningful for the back camera only. */
    fun mm(ratio: Float): Float = ratio * MAIN_MM

    /** Rule 26: the grid united with every stop's mm, kept within the first and last stop, [Tick.marked] for stops. */
    fun ticks(stops: List<ZoomStop>): List<Tick> {
        val grid = (18..35) + (40..100 step 5) + (110..200 step 10)
        val stopMm = stops.map { it.mm }.toSet()
        val lo = stops.first().mm
        val hi = stops.last().mm
        return (grid + stopMm).toSortedSet().filter { it in lo..hi }.map { Tick(it, it in stopMm) }
    }

    /** Rule 27: the tick passed or reached going from [fromMm] to [toMm] (a marked one first), not the one at the start. */
    fun crossed(fromMm: Float, toMm: Float, ticks: List<Tick>): Tick? {
        val counted = when {
            toMm > fromMm -> ticks.filter { it.mm > fromMm && it.mm <= toMm }
            toMm < fromMm -> ticks.filter { it.mm >= toMm && it.mm < fromMm }
            else -> emptyList()
        }
        return counted.firstOrNull { it.marked } ?: counted.firstOrNull()
    }

    /** The stop whose ratio is nearest to [ratio] in log distance. */
    fun nearest(stops: List<ZoomStop>, ratio: Float): ZoomStop =
        stops.minBy { abs(ln(it.ratio / ratio)) }
}
