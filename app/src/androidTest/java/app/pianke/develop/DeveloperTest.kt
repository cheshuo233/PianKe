package app.pianke.develop

import android.graphics.Bitmap
import android.graphics.BitmapFactory
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

    @Before
    fun synthesizeFrame() {
        frame = File(context.cacheDir, "developer-test.jpg")
        frame.outputStream().use { out ->
            Bitmap.createBitmap(400, 300, Bitmap.Config.ARGB_8888).apply { eraseColor(0xFF808080.toInt()) }
                .compress(Bitmap.CompressFormat.JPEG, 95, out)
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

    private fun size(uri: Uri): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)!!.use { BitmapFactory.decodeStream(it, null, options) }
        return options.outWidth to options.outHeight
    }

    @Test
    fun fourThreeIsUprightWithOrientation1FilmNameAndPianke() {
        val uri = develop(Framing.FOUR_THREE)
        assertEquals(300 to 400, size(uri))
        val exif = context.contentResolver.openInputStream(uri)!!.use { ExifInterface(it) }
        assertEquals(1, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 0))
        assertEquals(catalog.films.first().name, exif.getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION))
        val path = context.contentResolver.query(uri, arrayOf(MediaStore.Images.Media.RELATIVE_PATH), null, null)!!
            .use { it.moveToFirst(); it.getString(0) }
        assertEquals("DCIM/Pianke/", path)
    }

    @Test
    fun squareIsTheCentreSquare() {
        assertEquals(300 to 300, size(develop(Framing.SQUARE)))
    }
}
