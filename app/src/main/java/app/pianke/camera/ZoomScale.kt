package app.pianke.camera

/** One mark on the zoom scale. [marked]: this focal length is a stop, so the scale prints its number. */
data class Tick(val mm: Int, val marked: Boolean)

/** The zoom scale's maths: the only place that converts mm and ratio, builds the tick table and finds stops. */
object ZoomScale {
    /** The 23 mm main lens is ratio 1; every back ratio is mm / MAIN_MM, written only here. */
    const val MAIN_MM = 23f

    /** mm -> ratio. */
    fun ratio(mm: Float): Float = TODO()

    /** ratio -> mm; meaningful for the back camera only. */
    fun mm(ratio: Float): Float = TODO()

    /** Rule 26: the grid united with every stop's mm, kept within the first and last stop, [Tick.marked] for stops. */
    fun ticks(stops: List<ZoomStop>): List<Tick> = TODO()

    /** Rule 27: the tick passed or reached going from [fromMm] to [toMm] (a marked one first), not the one at the start. */
    fun crossed(fromMm: Float, toMm: Float, ticks: List<Tick>): Tick? = TODO()

    /** The stop whose ratio is nearest to [ratio] in log distance. */
    fun nearest(stops: List<ZoomStop>, ratio: Float): ZoomStop = TODO()
}
