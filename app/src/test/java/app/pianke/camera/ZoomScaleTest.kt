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
            setOf(18, 23, 28, 35, 50, 115, 135, 200),
            deviceTicks.filter { it.marked }.map { it.mm }.toSet(),
        )
        assertEquals(deviceTicks.map { it.mm }.sorted(), deviceTicks.map { it.mm })
    }

    @Test
    fun ticksStayWithinFirstAndLastStop() {
        val ticks = ZoomScale.ticks(ZoomStops.available(ZoomStops.BACK, 1f..5f))
        assertEquals(23, ticks.first().mm)
        assertEquals(115, ticks.last().mm)
    }

    @Test
    fun crossedTable() {
        val rows = listOf(
            Row("marked wins over 34", 33.5f, 36.2f, Tick(35, true)),
            Row("nothing between 35 and 40", 36.0f, 39.0f, null),
            Row("reaching counts", 37.0f, 40.0f, Tick(40, false)),
            Row("start excluded", 40.0f, 41.0f, null),
            Row("downward marked", 52.0f, 44.0f, Tick(50, true)),
        )
        for (row in rows) {
            assertEquals(row.name, row.expected, ZoomScale.crossed(row.from, row.to, deviceTicks))
        }
    }
}
