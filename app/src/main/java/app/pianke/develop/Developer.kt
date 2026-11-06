package app.pianke.develop

import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.exifinterface.media.ExifInterface
import app.pianke.film.Film
import app.pianke.film.FilmCatalog
import app.pianke.settings.Framing
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Decode -> upright -> optional 1:1 crop -> film engine -> JPEG -> MediaStore + EXIF (spec section 8, "develop").
 * Owns rows 13 (file name, path, EXIF) and 14 (upright first, then crop).
 *
 * [develop] is called only from the queue's worker, which is the engine's single thread.
 * [latest] may be called from any background thread; it is a ContentResolver query and must not run on the main thread.
 */
class Developer(private val context: Context, private val catalog: FilmCatalog) {
    private val images: Uri = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    private val software by lazy {
        "Pianke " + context.packageManager.getPackageInfo(context.packageName, 0).versionName
    }

    /**
     * Throws IOException / EngineException / OutOfMemoryError (or any runtime failure from Android).
     * If anything fails after the MediaStore insert, that row is deleted before rethrowing,
     * so nothing half-written stays in DCIM.
     */
    fun develop(shot: Shot, frame: File): Uri {
        val film = catalog.filmOrFirst(shot.filmId)
        if (film.id != shot.filmId) Log.w(TAG, "film ${shot.filmId} is no longer in the catalog; using ${film.id}")

        val source = ExifInterface(frame)
        val orientation = source.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val jpeg = render(shot, film, frame, orientation)

        val resolver = context.contentResolver
        val uri = resolver.insert(images, pendingRow(shot)) ?: throw IOException("MediaStore insert returned null")
        try {
            (resolver.openOutputStream(uri) ?: throw IOException("no output stream for $uri")).use { it.write(jpeg) }
            writeExif(uri, source, film.name)
            // A row that stays pending is hidden from the gallery, so an update that touched nothing is a failure.
            if (resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null) != 1) {
                throw IOException("could not publish $uri")
            }
        } catch (t: Throwable) {
            runCatching { resolver.delete(uri, null, null) }.exceptionOrNull()?.let(t::addSuppressed)
            throw t
        }
        return uri
    }

    /** Newest row of this app under `DCIM/Pianke/`, or null. */
    fun latest(): Uri? {
        val query = Bundle().apply {
            putString(ContentResolver.QUERY_ARG_SQL_SELECTION, "${MediaStore.Images.Media.RELATIVE_PATH} = ?")
            putStringArray(ContentResolver.QUERY_ARG_SQL_SELECTION_ARGS, arrayOf(RELATIVE_PATH))
            putStringArray(
                ContentResolver.QUERY_ARG_SORT_COLUMNS,
                arrayOf(MediaStore.Images.Media.DATE_TAKEN, MediaStore.Images.Media._ID),
            )
            putInt(ContentResolver.QUERY_ARG_SORT_DIRECTION, ContentResolver.QUERY_SORT_DIRECTION_DESCENDING)
            putInt(ContentResolver.QUERY_ARG_LIMIT, 1)
        }
        return context.contentResolver.query(images, arrayOf(MediaStore.Images.Media._ID), query, null)?.use {
            if (it.moveToFirst()) ContentUris.withAppendedId(images, it.getLong(0)) else null
        }
    }

    /** Decode, upright (rule 14, step one), crop (step two), engine, JPEG. Recycles every bitmap it creates. */
    private fun render(shot: Shot, film: Film, frame: File, orientation: Int): ByteArray {
        var bitmap = decode(frame)
        try {
            bitmap = upright(bitmap, orientation)
            if (shot.framing == Framing.SQUARE) bitmap = centreSquare(bitmap)
            catalog.develop(
                film,
                grainScale = shot.grain.scale,
                halation = shot.halation,
                date = if (shot.dateStamp) shot.capturedAt.toLocalDate() else null,
                seed = shot.id.hashCode(),
                bitmap = bitmap,
            )
            return encodeJpeg(bitmap)
        } finally {
            bitmap.recycle()
        }
    }

    /** Mutable ARGB_8888 in sRGB (the LUT is baked for sRGB input); BitmapFactory ignores EXIF orientation. */
    private fun decode(frame: File): Bitmap {
        val options = BitmapFactory.Options().apply {
            inMutable = true
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        }
        return BitmapFactory.decodeFile(frame.path, options) ?: throw IOException("cannot decode ${frame.name}")
    }

    /** Rule 14, step one: the single mapping from EXIF orientation 1..8 to a Matrix. Recycles the input when it makes a new bitmap. */
    private fun upright(bitmap: Bitmap, exifOrientation: Int): Bitmap {
        val matrix = Matrix()
        when (exifOrientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.postScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.postRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.postScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> { matrix.postRotate(90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.postRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> { matrix.postRotate(-90f); matrix.postScale(-1f, 1f) }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.postRotate(-90f)
            else -> return bitmap
        }
        return Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, matrix, false).also { bitmap.recycle() }
    }

    /** Rule 14, step two: the centre square, mutable ARGB_8888. Recycles the input. */
    private fun centreSquare(bitmap: Bitmap): Bitmap {
        val side = minOf(bitmap.width, bitmap.height)
        return Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
            .also { bitmap.recycle() }
    }

    /** Quality 95, into memory: Bitmap.compress swallows stream exceptions, which would hide ENOSPC. */
    private fun encodeJpeg(bitmap: Bitmap): ByteArray {
        val out = ByteArrayOutputStream(INITIAL_JPEG_BYTES)
        if (!bitmap.compress(Bitmap.CompressFormat.JPEG, 95, out)) throw IOException("JPEG encode failed")
        return out.toByteArray()
    }

    /** Rule 13: file name `PK_yyyyMMdd_HHmmss.jpg`, `DCIM/Pianke/`, image/jpeg, DATE_TAKEN, IS_PENDING=1. */
    private fun pendingRow(shot: Shot) = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "PK_${NAME_TIME.format(shot.capturedAt)}.jpg")
        put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
        put(MediaStore.Images.Media.RELATIVE_PATH, RELATIVE_PATH)
        put(MediaStore.Images.Media.DATE_TAKEN, shot.capturedAt.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli())
        put(MediaStore.Images.Media.IS_PENDING, 1)
    }

    /** Rule 13, EXIF: copy the capture tags present in [source]; set ImageDescription, Software and Orientation=1. */
    private fun writeExif(uri: Uri, source: ExifInterface, filmName: String) {
        val descriptor = context.contentResolver.openFileDescriptor(uri, "rw") ?: throw IOException("no descriptor for $uri")
        descriptor.use {
            val exif = ExifInterface(it.fileDescriptor)
            for (tag in COPIED_TAGS) source.getAttribute(tag)?.let { value -> exif.setAttribute(tag, value) }
            exif.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, filmName)
            exif.setAttribute(ExifInterface.TAG_SOFTWARE, software)
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL.toString())
            exif.saveAttributes()
        }
    }

    private companion object {
        const val TAG = "Developer"
        const val RELATIVE_PATH = "DCIM/Pianke/"
        const val INITIAL_JPEG_BYTES = 4 shl 20
        val NAME_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
        val COPIED_TAGS = listOf(
            ExifInterface.TAG_EXPOSURE_TIME,
            ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_F_NUMBER,
            ExifInterface.TAG_FOCAL_LENGTH,
            ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM,
            ExifInterface.TAG_DATETIME_ORIGINAL,
            ExifInterface.TAG_MAKE,
            ExifInterface.TAG_MODEL,
        )
    }
}
