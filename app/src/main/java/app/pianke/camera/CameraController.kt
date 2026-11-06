package app.pianke.camera

import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Size
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LiveData
import androidx.lifecycle.Observer
import app.pianke.settings.FlashMode
import app.pianke.settings.Lens
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * Owns the CameraX session, bound to [owner]'s lifecycle. Call the public members on the main thread; no GL here.
 */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
class CameraController(private val context: Context, private val owner: LifecycleOwner) {
    private val _stops = MutableStateFlow<List<ZoomStop>>(emptyList())
    private val _issue = MutableStateFlow<CameraIssue?>(null)
    private val _exposure = MutableStateFlow<Exposure?>(null)

    val stops: StateFlow<List<ZoomStop>> = _stops
    val issue: StateFlow<CameraIssue?> = _issue
    val exposure: StateFlow<Exposure?> = _exposure

    // Main-thread state: the last requested arguments, the bound CameraX objects, and the rebind-once budget.
    private var lens = Lens.BACK
    private var flash = FlashMode.AUTO
    private var surface: Preview.SurfaceProvider? = null
    private var camera: Camera? = null
    private var imageCapture: ImageCapture? = null
    private var rebound = false
    private var zoomRatio = 1f
    private var observed: LiveData<CameraState>? = null
    private val stateObserver = Observer<CameraState> { onCameraState(it) }
    private val saveExecutor = Executors.newSingleThreadExecutor()

    /**
     * Binds Preview + ImageCapture (largest 4:3 size <= 4096x3072) for [lens]; rebinding replaces the previous session.
     * Asynchronous: when the camera provider is ready, the session is bound with the arguments requested last.
     */
    fun bind(lens: Lens, flash: FlashMode, surface: Preview.SurfaceProvider) {
        this.lens = lens
        this.flash = flash
        this.surface = surface
        rebound = false
        zoomRatio = 1f
        bindCurrent()
    }

    /** Remembered, because CameraX goes back to 1x whenever the use cases detach (stop/start, rebind); OPEN re-applies it. */
    fun setZoom(stop: ZoomStop) {
        zoomRatio = stop.ratio
        camera?.cameraControl?.setZoomRatio(stop.ratio)
    }

    /** The only FlashMode -> ImageCapture mapping; front is always FLASH_MODE_OFF; bind applies its flash through here. */
    fun setFlash(mode: FlashMode) {
        flash = mode
        imageCapture?.flashMode = when {
            lens == Lens.FRONT -> ImageCapture.FLASH_MODE_OFF
            mode == FlashMode.AUTO -> ImageCapture.FLASH_MODE_AUTO
            mode == FlashMode.ON -> ImageCapture.FLASH_MODE_ON
            else -> ImageCapture.FLASH_MODE_OFF
        }
    }

    /**
     * Writes a JPEG (EXIF orientation set by CameraX) to [target]; false on ImageCaptureException.
     * [rotation] is a Surface.ROTATION_* value, applied as imageCapture.targetRotation.
     */
    suspend fun capture(target: File, rotation: Int): Boolean {
        val capture = imageCapture ?: return false
        capture.targetRotation = rotation
        return suspendCancellableCoroutine { cont ->
            capture.takePicture(
                ImageCapture.OutputFileOptions.Builder(target).build(),
                saveExecutor,
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(true)
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resume(false)
                    }
                },
            )
        }
    }

    /** The CameraX work, from the stored fields. Does not touch [rebound]; a throw becomes Fatal, next to rule 18. */
    private fun bindCurrent() {
        if (surface == null) return
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            try {
                bindNow(future.get())
            } catch (_: Exception) {
                _issue.value = CameraIssue.Fatal
            }
        }, context.mainExecutor)
    }

    private fun bindNow(provider: ProcessCameraProvider) {
        val surface = surface ?: return

        val selector = if (lens == Lens.FRONT) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val info = checkNotNull(selector.filter(provider.availableCameraInfos).firstOrNull()) { "no camera for $lens" }
        val chars = context.getSystemService(CameraManager::class.java)
            .getCameraCharacteristics(Camera2CameraInfo.from(info).cameraId)

        val previewBuilder = Preview.Builder()
            .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(RATIO_43).build())
        val captureBuilder = ImageCapture.Builder().setResolutionSelector(
            ResolutionSelector.Builder()
                .setAspectRatioStrategy(RATIO_43)
                .setResolutionStrategy(ResolutionStrategy(Size(4096, 3072), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER))
                .build(),
        )
        val previewExtender = Camera2Interop.Extender(previewBuilder)
        applyCamera2Options(previewExtender, chars)
        previewExtender.setSessionCaptureCallback(exposureCallback())
        applyCamera2Options(Camera2Interop.Extender(captureBuilder), chars)

        val preview = previewBuilder.build().also { it.setSurfaceProvider(surface) }
        val capture = captureBuilder.build()
        provider.unbindAll()
        val bound = provider.bindToLifecycle(owner, selector, preview, capture)
        camera = bound
        imageCapture = capture
        setFlash(flash)

        _stops.value = if (lens == Lens.FRONT) {
            ZoomStops.front(frontEquivalentMm(chars))
        } else {
            val range = chars.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE)
            ZoomStops.available(ZoomStops.BACK, if (range == null) 1f..1f else range.lower..range.upper)
        }

        // A rebind of the same camera keeps its observer: re-observing would replay the state it is recovering from.
        val live = bound.cameraInfo.cameraState
        if (live !== observed) {
            observed?.removeObserver(stateObserver)
            live.observe(owner, stateObserver)
            observed = live
        }
    }

    /** Rule 19: EDGE_MODE_OFF, NOISE_REDUCTION_MODE_MINIMAL, TONEMAP sRGB preset, each only if [chars] lists it. Applied to Preview and ImageCapture alike. */
    private fun applyCamera2Options(extender: Camera2Interop.Extender<*>, chars: CameraCharacteristics) {
        fun listed(key: CameraCharacteristics.Key<IntArray>, mode: Int) = chars.get(key)?.contains(mode) == true

        if (listed(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CaptureRequest.EDGE_MODE_OFF)) {
            extender.setCaptureRequestOption(CaptureRequest.EDGE_MODE, CaptureRequest.EDGE_MODE_OFF)
        }
        if (listed(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)) {
            extender.setCaptureRequestOption(CaptureRequest.NOISE_REDUCTION_MODE, CaptureRequest.NOISE_REDUCTION_MODE_MINIMAL)
        }
        if (listed(CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)) {
            extender.setCaptureRequestOption(CaptureRequest.TONEMAP_MODE, CaptureRequest.TONEMAP_MODE_PRESET_CURVE)
            extender.setCaptureRequestOption(CaptureRequest.TONEMAP_PRESET_CURVE, CaptureRequest.TONEMAP_PRESET_CURVE_SRGB)
        }
    }

    /** Session capture callback: reads SENSOR_EXPOSURE_TIME and LENS_APERTURE, emits to [_exposure] at most ~4 times a second. */
    private fun exposureCallback() = object : CameraCaptureSession.CaptureCallback() {
        private var lastEmit = 0L

        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: return
            val aperture = result.get(CaptureResult.LENS_APERTURE) ?: return
            val now = SystemClock.elapsedRealtime()
            if (now - lastEmit < EXPOSURE_INTERVAL_MS) return
            lastEmit = now
            _exposure.value = Exposure(exposureNs, aperture)
        }
    }

    /** Front camera 35 mm-equivalent focal length from LENS_INFO_AVAILABLE_FOCAL_LENGTHS[0] and SENSOR_INFO_PHYSICAL_SIZE (43.27 mm diagonal). */
    private fun frontEquivalentMm(chars: CameraCharacteristics): Int {
        val focal = checkNotNull(chars.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.firstOrNull())
        val size = checkNotNull(chars.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE))
        return (focal * FULL_FRAME_DIAGONAL_MM / hypot(size.width, size.height)).roundToInt()
    }

    /** Rule 18 (spec 7): the only place CameraX error codes become a [CameraIssue]; FATAL-class errors rebind once before Fatal; an open camera clears the issue. */
    private fun onCameraState(state: CameraState) {
        when (state.error?.code) {
            null -> if (state.type == CameraState.Type.OPEN) {
                _issue.value = null
                camera?.cameraControl?.setZoomRatio(zoomRatio)
            }
            CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE ->
                _issue.value = CameraIssue.InUse
            CameraState.ERROR_CAMERA_DISABLED, CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED ->
                _issue.value = CameraIssue.Disabled
            CameraState.ERROR_CAMERA_FATAL_ERROR,
            CameraState.ERROR_STREAM_CONFIG,
            CameraState.ERROR_OTHER_RECOVERABLE_ERROR,
            ->
                if (rebound) {
                    _issue.value = CameraIssue.Fatal
                } else {
                    rebound = true
                    bindCurrent()
                }
        }
    }

    private companion object {
        const val EXPOSURE_INTERVAL_MS = 250L
        const val FULL_FRAME_DIAGONAL_MM = 43.27f
        val RATIO_43 = AspectRatioStrategy(AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_NONE)
    }
}
