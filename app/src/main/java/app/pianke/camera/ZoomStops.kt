package app.pianke.camera

enum class StopKind { NATIVE, CROP, DIGITAL }

data class ZoomStop(val mm: Int, val ratio: Float, val kind: StopKind)

object ZoomStops {
    /** Rule 17: the stop kinds live only here. ratio = mm / 23f (the 23 mm main lens is ratio 1). */
    val BACK: List<ZoomStop> = listOf(
        ZoomStop(18, 18 / 23f, StopKind.NATIVE),
        ZoomStop(23, 23 / 23f, StopKind.NATIVE),
        ZoomStop(28, 28 / 23f, StopKind.CROP),
        ZoomStop(35, 35 / 23f, StopKind.CROP),
        ZoomStop(50, 50 / 23f, StopKind.DIGITAL),
        ZoomStop(115, 115 / 23f, StopKind.NATIVE),
        ZoomStop(135, 135 / 23f, StopKind.CROP),
        ZoomStop(200, 200 / 23f, StopKind.DIGITAL),
    )

    /** The front camera has one stop: its own 35 mm-equivalent focal length at ratio 1. */
    fun front(mm: Int): List<ZoomStop> = TODO()

    /** Rule 16: keep a stop whose ratio is within [lower*0.97, upper*1.03] and clamp the kept ratio into the range. */
    fun available(stops: List<ZoomStop>, range: ClosedFloatingPointRange<Float>): List<ZoomStop> = TODO()
}
