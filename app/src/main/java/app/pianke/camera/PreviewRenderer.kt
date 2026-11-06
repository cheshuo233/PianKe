package app.pianke.camera

import android.graphics.SurfaceTexture
import android.opengl.GLSurfaceView
import androidx.camera.core.Preview
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLES 3.0 viewfinder: camera frame (OES) -> 3D LUT -> light grain, center-cropped to fill the view.
 * Every GL call happens on the GL thread; [setFilm] and [setGrain] only enqueue into [glQueue], drained at the top of [onDrawFrame].
 * The 1:1 mask, grid and date overlay are not this class's job.
 */
@Suppress("unused") // Phase A skeleton: private members are used once the bodies are filled in.
class PreviewRenderer : GLSurfaceView.Renderer {
    private val glQueue = ConcurrentLinkedQueue<() -> Unit>()

    // GL-thread state: touched only from onSurfaceCreated / onSurfaceChanged / onDrawFrame / queued ops.
    private var cameraTexture = 0
    private var lutTexture = 0
    private var noiseTexture = 0
    private var program = 0
    private var lutSize = 0
    private var grain = 0f
    private var viewWidth = 0
    private var viewHeight = 0
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var frame = 0
    private var surfaceTexture: SurfaceTexture? = null

    /** Supplies the OES SurfaceTexture's Surface to CameraX at the requested resolution. */
    val surfaceProvider: Preview.SurfaceProvider = Preview.SurfaceProvider { _ -> TODO() }

    /** Uploads a GL_TEXTURE_3D (GL_RGB8, size^3, red -> s, green -> t, blue -> r) on the GL thread. */
    fun setFilm(lut: ByteBuffer, size: Int): Unit = TODO()

    /** [scale] 0 means no grain. */
    fun setGrain(scale: Float): Unit = TODO()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?): Unit = TODO()
    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int): Unit = TODO()
    override fun onDrawFrame(gl: GL10?): Unit = TODO()

    /** The single entry for GL work requested from other threads. */
    private fun post(op: () -> Unit): Unit = TODO()

    private companion object {
        const val VERTEX_SHADER = """#version 300 es
in vec2 aPos;
uniform mat4 uTexMatrix;   // SurfaceTexture.getTransformMatrix
uniform vec2 uCropScale;   // center-crop: shrinks the sampled area on the longer axis
out vec2 vCameraUv;
out vec2 vViewUv;
void main() {
    vec2 uv = aPos * 0.5 + 0.5;
    vViewUv = uv;
    vCameraUv = (uTexMatrix * vec4((uv - 0.5) * uCropScale + 0.5, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        /** Half-texel offset: coord = c * (N-1)/N + 0.5/N maps hardware trilinear sampling onto the LUT nodes. */
        const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform samplerExternalOES uCamera;
uniform mediump sampler3D uLut;
uniform sampler2D uNoise;
uniform float uLutSize;
uniform float uGrain;          // GrainLevel.scale; 0 = none
uniform vec2 uNoiseOffset;     // changes every frame
uniform vec2 uNoiseScale;      // view size / noise tile size
in vec2 vCameraUv;
in vec2 vViewUv;
out vec4 oColor;
void main() {
    vec3 c = texture(uCamera, vCameraUv).rgb;
    vec3 coord = c * ((uLutSize - 1.0) / uLutSize) + 0.5 / uLutSize;
    vec3 graded = texture(uLut, coord).rgb;
    float n = texture(uNoise, vViewUv * uNoiseScale + uNoiseOffset).r - 0.5;
    oColor = vec4(clamp(graded + n * uGrain * 0.12, 0.0, 1.0), 1.0);
}
"""
    }
}
