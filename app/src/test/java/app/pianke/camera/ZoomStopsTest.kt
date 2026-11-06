package app.pianke.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomStopsTest {
    private class Row(
        val stops: List<ZoomStop>,
        val range: ClosedFloatingPointRange<Float>,
        val mm: List<Int>,
        val firstRatio: Float,
    )

    private val all = listOf(18, 23, 28, 35, 50, 115, 135, 200)

    @Test
    fun availableFiltersByRangeAndClampsRatio() {
        val rows = listOf(
            Row(ZoomStops.BACK, 1.0f..5.0f, listOf(23, 28, 35, 50, 115), 1f),
            Row(ZoomStops.BACK, 0.8f..8.7f, all, 0.8f),          // 18 mm is within 3% of the lower end and is clamped
            Row(ZoomStops.BACK, 0.6f..10.0f, all, 18 / 23f),     // nothing clamped
            Row(ZoomStops.front(24), 1.0f..1.0f, listOf(24), 1f),
        )
        for (row in rows) {
            val got = ZoomStops.available(row.stops, row.range)
            assertEquals(row.range.toString(), row.mm, got.map { it.mm })
            assertEquals(row.range.toString(), row.firstRatio, got.first().ratio, 1e-6f)
            assertEquals(row.range.toString(), true, got.all { it.ratio in row.range })
        }
    }
}
