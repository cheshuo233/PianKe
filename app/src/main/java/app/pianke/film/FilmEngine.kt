package app.pianke.film

import android.content.res.AssetManager
import android.graphics.Bitmap
import java.nio.ByteBuffer

/** JNI bridge to libpianke.so; only [FilmCatalog] calls it. */
internal object FilmEngine {
    init {
        System.loadLibrary("pianke")
    }

    /** Opens assets/films.bin (packed uncompressed) with AAsset_getBuffer, zero copy; the handle lives until process end. */
    external fun open(assets: AssetManager): Long

    external fun ids(handle: Long): Array<String>

    external fun lutSize(handle: Long): Int

    /** Read-only direct ByteBuffer of film [index]'s LUT: lutSize^3 RGB888, red varying fastest, pointing straight into films.bin. */
    external fun lut(handle: Long, index: Int): ByteBuffer

    /** Develops a writable ARGB_8888 bitmap in place (memory order RGBA, channels = 4). dateYmd = 0 prints no date. */
    external fun develop(
        handle: Long,
        index: Int,
        grainScale: Float,
        halation: Boolean,
        dateYmd: Int,
        seed: Int,
        bitmap: Bitmap,
    )
}
