package app.pianke.develop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.net.Uri
import android.provider.MediaStore
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import app.pianke.film.FilmCatalog
import app.pianke.settings.Framing
import app.pianke.settings.GrainLevel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File
import java.time.LocalDateTime

class DeveloperTest {
    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val catalog = FilmCatalog.load(context)
    private val developer = Developer(context, catalog)
    private val created = mutableListOf<Uri>()
    private lateinit var frame: File

    /**
     * A 400x300 sensor frame, orientation 6 (the display is that frame turned 90 degrees clockwise): red, green, blue
     * and white quadrants top-left, top-right, bottom-left, bottom-right, and 50 px black bands on its left and right
     * edges. Turned upright (300x400) the bands are the top and bottom 50 rows, which the square crop must remove.
     */
    @Before
    fun synthesizeFrame() {
        frame = File(context.cacheDir, "developer-test.jpg")
        frame.outputStream().use { out ->
            val bitmap = Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            val paint = Paint()
            fun fill(color: Int, left: Int, top: Int, right: Int, bottom: Int) {
                paint.color = color
                canvas.drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), paint)
            }
            fill(Color.RED, 0, 0, 200, 150)
            fill(Color.GREEN, 200, 0, 400, 150)
            fill(Color.BLUE, 0, 150, 200, 300)
            fill(Color.WHITE, 200, 150, 400, 300)
            fill(Color.BLACK, 0, 0, 50, 300)
            fill(Color.BLACK, 350, 0, 400, 300)
            bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)
        }
        ExifInterface(frame).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            saveAttributes()
        }
    }

    @After
    fun cleanUp() {
        created.forEach { context.contentResolver.delete(it, null, null) }
        frame.delete()
    }

    private fun develop(framing: Framing): Uri {
        val shot = Shot(
            id = System.currentTimeMillis().toString(),
            filmId = catalog.films.first().id,
            framing = framing,
            dateStamp = false,
            grain = GrainLevel.OFF,
            halation = false,
            capturedAt = LocalDateTime.now(),
        )
        return developer.develop(shot, frame).also { created += it }
    }

    private fun decode(uri: Uri): Bitmap = context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it)!! }

    /** Which synthetic colour a developed pixel still is; the film only shifts colours, never reorders their channels this far. */
    private fun colorAt(bitmap: Bitmap, x: Int, y: Int): String {
        val p = bitmap.getPixel(x, y)
        val (r, g, b) = Triple(Color.red(p), Color.green(p), Color.blue(p))
        return when {
            maxOf(r, g, b) < 60 -> "black"
            minOf(r, g, b) > 128 -> "white"
            r > g && r > b -> "red"
            g > r && g > b -> "green"
            b > r && b > g -> "blue"
            else -> "other"
        }
    }

    private fun size(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) }
        return options.outWidth to options.outHeight
    }

    @Test
    fun fourThreeIsUprightWithOrientation1FilmNameAndPianke() {
        val uri = develop(Framing.FOUR_THREE)
        assertEquals(300 to 400, size(uri))
        // Turned 90 degrees clockwise: red top-right, green bottom-right, white bottom-left, blue top-left.
        val upright = decode(uri)
        assertEquals(
            listOf("blue", "red", "white", "green"),
            listOf(colorAt(upright, 75, 100), colorAt(upright, 225, 100), colorAt(upright, 75, 300), colorAt(upright, 225, 300)),
        )
        assertEquals("black", colorAt(upright, 150, 20))
        assertEquals("black", colorAt(upright, 150, 380))
        val exif = context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
        assertEquals(1, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals(catalog.films.first().name, exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        val path = context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null)!!
            .use { it.moveToFirst(); it.getString(0) }
        assertEquals("DCIM/Pianke/", path)
    }

    @Test
    fun squareIsTheCentreSquare() {
        val uri = develop(Framing.SQUARE)
        assertEquals(300 to 300, size(uri))
        // The square is rows 50..349 of the upright frame: the black bands are gone from every corner, and the
        // quadrants are where the 4:3 frame has them.
        val square = decode(uri)
        val corners = listOf(8 to 8, 292 to 8, 8 to 292, 292 to 292)
        assertEquals(listOf("blue", "red", "white", "green"), corners.map { (x, y) -> colorAt(square, x, y) })

    }
}
