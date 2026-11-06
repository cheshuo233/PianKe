package app.pianke.film

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

/** Develops tests/engine/golden inputs through JNI and compares with expected/<film>_<input>.png, per channel within 1. */
class GoldenDevelopTest {
    @Test
    fun developMatchesHostGoldens() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val goldens = instrumentation.context.assets // golden PNGs live in the test APK
        val catalog = FilmCatalog.load(instrumentation.targetContext) // films.bin lives in the app APK
        val options = BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }
        var compared = 0
        for (input in listOf("sweep", "discs", "patches")) {
            val source = goldens.open("inputs/$input.png").use { BitmapFactory.decodeStream(it, null, options)!! }
            for (film in catalog.films) {
                val bitmap = source.copy(Bitmap.Config.ARGB_8888, true)
                catalog.develop(film, grainScale = 0f, halation = true, date = null, seed = 1, bitmap = bitmap)
                val expected = goldens.open("expected/${film.id}_$input.png").use {
                    BitmapFactory.decodeStream(it, null, options)!!
                }
                assertEquals("${film.id}_$input width", expected.width, bitmap.width)
                assertEquals("${film.id}_$input height", expected.height, bitmap.height)
                val got = pixels(bitmap)
                val want = pixels(expected)
                var worst = 0
                for (i in got.indices) {
                    for (shift in intArrayOf(16, 8, 0)) {
                        worst = maxOf(worst, abs(((got[i] shr shift) and 0xFF) - ((want[i] shr shift) and 0xFF)))
                    }
                }
                assertTrue("${film.id}_$input: max channel difference $worst > 1", worst <= 1)
                compared++
            }
        }
        assertEquals(45, compared)
    }

    private fun pixels(bitmap: Bitmap) =
        IntArray(bitmap.width * bitmap.height).also { bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height) }
}
