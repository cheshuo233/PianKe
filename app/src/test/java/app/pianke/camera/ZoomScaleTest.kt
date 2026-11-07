package app.pianke.camera

import org.junit.Assert.assertEquals
import org.junit.Test

class ZoomScaleTest {
    private val device = ZoomStops.available(ZoomStops.BACK, 0.7f..10f)
    private val deviceTicks = ZoomScale.ticks(device)

    private class Row(val name: String, val from: Float, val to: Float, val expected: Tick?)

    @Test
    fun ticksOnTheDeviceRange() {
        assertEquals(43, deviceTicks.size)
        assertEquals(
            ((18..35) + (40..100 step 5) + (110..200 step 10) + listOf(115, 135)).toSet(),
            deviceTicks.map { it.mm }.toSet(),
        )
        assertEquals(
            setOf(18, 23, 28, 35, 50, 115, 135, 200),
            deviceTicks.filter { it.marked }.map { it.mm }.toSet(),
        )
    }

    @Test
    fun ticksStayWithinFirstAndLastStop() {
        val ticks = ZoomScale.ticks(ZoomStops.available(ZoomStops.BACK, 1f..5f))
        assertEquals(23, ticks.first().mm)
        assertEquals(115, ticks.last().mm)
    }

    @Test
    fun nearestUsesLogDistance() {
        // The 35/50 boundary is sqrt(35 * 50) = 41.83 mm.
        assertEquals(35, ZoomScale.nearest(device, ZoomScale.ratio(41.5f)).mm)
        assertEquals(50, ZoomScale.nearest(device, ZoomScale.ratio(42.2f)).mm)
    }

    @Test
    fun crossedTable() {
        val rows = listOf(
            Row("marked wins over 34", 33.5f, 36.2f, Tick(35, true)),
            Row("nothing between 35 and 40", 36.0f, 39.0f, null),
            Row("reaching counts", 37.0f, 40.0f, Tick(40, false)),
            Row("start excluded", 40.0f, 41.0f, null),
            Row("downward marked", 52.0f, 44.0f, Tick(50, true)),
            Row("inexact 28 stop is the start", ZoomScale.mm(ZoomScale.ratio(28f)), 28.4f, null),
        )
        for (row in rows) {
            assertEquals(row.name, row.expected, ZoomScale.crossed(row.from, row.to, deviceTicks))
        }
    }
}
