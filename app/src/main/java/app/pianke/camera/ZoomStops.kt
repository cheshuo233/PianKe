package app.pianke.camera

enum class StopKind { NATIVE, CROP, DIGITAL }

data class ZoomStop(val mm: Int, val ratio: Float, val kind: StopKind)

object ZoomStops {
    /** Rule 17: the stop kinds live only here; the ratio comes from [ZoomScale.ratio]. */
    val BACK: List<ZoomStop> = listOf(
        18 to StopKind.NATIVE,
        23 to StopKind.NATIVE,
        28 to StopKind.CROP,
        35 to StopKind.CROP,
        50 to StopKind.DIGITAL,
        115 to StopKind.NATIVE,
        135 to StopKind.CROP,
        200 to StopKind.DIGITAL,
    ).map { (mm, kind) -> ZoomStop(mm, ZoomScale.ratio(mm.toFloat()), kind) }

    /** The front camera has one stop: its own 35 mm-equivalent focal length at ratio 1. */
    fun front(mm: Int): List<ZoomStop> = listOf(ZoomStop(mm, 1f, StopKind.NATIVE))

    /** Rule 16: keep a stop whose ratio is within [lower*0.97, upper*1.03] and clamp the kept ratio into the range. */
    fun available(stops: List<ZoomStop>, range: ClosedFloatingPointRange<Float>): List<ZoomStop> =
        stops
            .filter { it.ratio >= range.start * 0.97f && it.ratio <= range.endInclusive * 1.03f }
            .map { it.copy(ratio = it.ratio.coerceIn(range)) }
}
