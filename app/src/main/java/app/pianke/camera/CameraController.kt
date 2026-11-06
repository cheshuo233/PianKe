package app.pianke.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraState
import androidx.camera.core.Preview
import androidx.lifecycle.LifecycleOwner
import app.pianke.settings.FlashMode
import app.pianke.settings.Lens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.io.File

/** Owns the CameraX session, bound to [owner]'s lifecycle. Runs on the CameraX / main executors; no GL here. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
@Suppress("unused") // Phase A skeleton: private members are used once the bodies are filled in.
class CameraController(private val context: Context, private val owner: LifecycleOwner) {
    private val _stops = MutableStateFlow<List<ZoomStop>>(emptyList())
    private val _issue = MutableStateFlow<CameraIssue?>(null)
    private val _exposure = MutableStateFlow<Exposure?>(null)

    val stops: StateFlow<List<ZoomStop>> = _stops
    val issue: StateFlow<CameraIssue?> = _issue
    val exposure: StateFlow<Exposure?> = _exposure

    /** Binds Preview + ImageCapture (largest 4:3 size <= 4096x3072) for [lens]; rebinding replaces the previous session. */
    fun bind(lens: Lens, flash: FlashMode, surface: Preview.SurfaceProvider): Unit = TODO()

    fun setZoom(stop: ZoomStop): Unit = TODO()

    /** The front camera is always FLASH_MODE_OFF. */
    fun setFlash(mode: FlashMode): Unit = TODO()

    /** Writes a JPEG (EXIF orientation set by CameraX) to [target]; false on ImageCaptureException. */
    suspend fun capture(target: File, rotation: Int): Boolean = TODO()

    /** Rule 19: EDGE_MODE_OFF, NOISE_REDUCTION_MODE_MINIMAL, TONEMAP sRGB preset, each only if [chars] lists it. Applied to Preview and ImageCapture alike. */
    private fun applyCamera2Options(extender: Camera2Interop.Extender<*>, chars: CameraCharacteristics): Unit = TODO()

    /** Session capture callback: reads SENSOR_EXPOSURE_TIME and LENS_APERTURE, emits to [_exposure] at most ~4 times a second. */
    private fun exposureCallback(): CameraCaptureSession.CaptureCallback = TODO()

    /** Front camera 35 mm-equivalent focal length from LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0] and SENSOR_INFO_PHYSICAL_SIZE (43.27 mm diagonal). */
    private fun frontEquivalentMm(chars: CameraCharacteristics): Int = TODO()

    /** Rule 18 (spec 7): the only place CameraX error codes become a [CameraIssue]; FATAL-class errors rebind once before Fatal; an open camera clears the issue. */
    private fun onCameraState(state: CameraState): Unit = TODO()
}
