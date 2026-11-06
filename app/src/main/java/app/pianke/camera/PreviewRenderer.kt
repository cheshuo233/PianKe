package app.pianke.camera

import android.graphics.PointF
import android.graphics.SurfaceTexture
import android.opengl.GLES11Ext
import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.view.Surface
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceRequest
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Random
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.Executor
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10

/**
 * GLES 3.0 viewfinder: camera frame (OES) -> 3D LUT -> light grain, center-cropped to fill the view.
 * Every GL call happens on the GL thread. [setFilm], [setGrain] and [surfaceProvider] are the entries that may be called
 * from other threads: they only enqueue through [post], and the queue is drained at the top of [onDrawFrame].
 * The SurfaceTexture frame-available callback (arbitrary thread) does nothing but request a render.
 * The 1:1 mask, grid and date overlay are not this class's job.
 */
class PreviewRenderer : GLSurfaceView.Renderer {
    private val glQueue = ConcurrentLinkedQueue<() -> Unit>()

    @Volatile
    private lateinit var view: GLSurfaceView

    // GL-thread state: touched only from onSurfaceCreated / onSurfaceChanged / onDrawFrame / queued ops.
    private var cameraTexture = 0
    private var lutTexture = 0
    private var noiseTexture = 0
    private var program = 0
    private var locTexMatrix = 0
    private var locViewToCamera = 0
    private var locGrain = 0
    private var locNoiseOffset = 0
    private var grain = 0f
    private var viewWidth = 0
    private var viewHeight = 0
    private var cameraWidth = 0
    private var cameraHeight = 0
    private var rotationDegrees = 0
    private var mirrored = false
    private var frame = 0
    private var surfaceTexture: SurfaceTexture? = null
    private val texMatrix = FloatArray(16)
    private val viewToCamera = FloatArray(4)

    /** Rule 33: the center-crop factors (sx, sy), published by [updateViewToCamera] on the GL thread; null until known. Read by [viewToFrame] on main. */
    @Volatile
    private var crop: PointF? = null
    private val quad = ByteBuffer.allocateDirect(QUAD.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(QUAD)

    /** Supplies the OES SurfaceTexture's Surface to CameraX at the requested resolution. */
    val surfaceProvider: Preview.SurfaceProvider = Preview.SurfaceProvider { request -> post { provideSurface(request) } }

    /**
     * Configures [view] for this renderer (ES 3 context, kept across pause, render on demand) and installs it.
     * Call once, before any other member.
     */
    fun attach(view: GLSurfaceView) {
        this.view = view
        view.setEGLContextClientVersion(3)
        view.preserveEGLContextOnPause = true
        view.setRenderer(this)
        view.renderMode = GLSurfaceView.RENDERMODE_WHEN_DIRTY
    }

    /** Uploads a GL_TEXTURE_3D (GL_RGB8, size^3, red -> s, green -> t, blue -> r) on the GL thread. */
    fun setFilm(lut: ByteBuffer, size: Int) = post {
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1) // a 33-texel RGB8 row is 99 bytes
        GLES30.glTexImage3D(
            GLES30.GL_TEXTURE_3D, 0, GLES30.GL_RGB8, size, size, size, 0,
            GLES30.GL_RGB, GLES30.GL_UNSIGNED_BYTE, lut,
        )
    }

    /** [scale] 0 means no grain. */
    fun setGrain(scale: Float) = post { grain = scale }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        val ids = IntArray(3)
        GLES30.glGenTextures(3, ids, 0)
        cameraTexture = ids[0]
        lutTexture = ids[1]
        noiseTexture = ids[2]

        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        setSampling(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES30.GL_LINEAR)

        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        setSampling(GLES30.GL_TEXTURE_3D, GLES30.GL_LINEAR)
        GLES30.glTexParameteri(GLES30.GL_TEXTURE_3D, GLES30.GL_TEXTURE_WRAP_R, GLES30.GL_CLAMP_TO_EDGE)

        val noise = ByteArray(NOISE_TILE * NOISE_TILE).also { Random(NOISE_SEED).nextBytes(it) }
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTexture)
        setSampling(GLES30.GL_TEXTURE_2D, GLES30.GL_NEAREST)
        GLES30.glPixelStorei(GLES30.GL_UNPACK_ALIGNMENT, 1)
        GLES30.glTexImage2D(
            GLES30.GL_TEXTURE_2D, 0, GLES30.GL_R8, NOISE_TILE, NOISE_TILE, 0,
            GLES30.GL_RED, GLES30.GL_UNSIGNED_BYTE, ByteBuffer.wrap(noise),
        )

        program = buildProgram()
        GLES30.glUseProgram(program)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uCamera"), 0)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uLut"), 1)
        GLES30.glUniform1i(GLES30.glGetUniformLocation(program, "uNoise"), 2)
        locTexMatrix = GLES30.glGetUniformLocation(program, "uTexMatrix")
        locViewToCamera = GLES30.glGetUniformLocation(program, "uViewToCamera")
        locGrain = GLES30.glGetUniformLocation(program, "uGrain")
        locNoiseOffset = GLES30.glGetUniformLocation(program, "uNoiseOffset")

        surfaceTexture?.release()
        surfaceTexture = SurfaceTexture(cameraTexture).also { st ->
            st.setOnFrameAvailableListener { view.requestRender() }
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        viewWidth = width
        viewHeight = height
        GLES30.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        var op = glQueue.poll()
        while (op != null) {
            op()
            op = glQueue.poll()
        }
        val st = surfaceTexture ?: return
        st.updateTexImage()
        st.getTransformMatrix(texMatrix)
        updateViewToCamera()

        GLES30.glUseProgram(program)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_3D, lutTexture)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE2)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, noiseTexture)
        GLES30.glUniformMatrix4fv(locTexMatrix, 1, false, texMatrix, 0)
        GLES30.glUniformMatrix2fv(locViewToCamera, 1, false, viewToCamera, 0)
        GLES30.glUniform1f(locGrain, grain)
        frame = (frame + 1) % NOISE_TILE
        GLES30.glUniform2i(locNoiseOffset, frame * 17 % NOISE_TILE, frame * 29 % NOISE_TILE)

        quad.position(0)
        GLES30.glEnableVertexAttribArray(POS_LOCATION)
        GLES30.glVertexAttribPointer(POS_LOCATION, 2, GLES30.GL_FLOAT, false, 0, quad)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4)
    }

    /** The single entry for GL work requested from other threads; wakes the GL thread, which only draws on demand. */
    private fun post(op: () -> Unit) {
        glQueue.add(op)
        view.requestRender()
    }

    /** GL thread: gives CameraX a Surface on the OES texture at the requested resolution. */
    private fun provideSurface(request: SurfaceRequest) {
        val st = surfaceTexture
        if (st == null) {
            request.willNotProvideSurface()
            return
        }
        val resolution = request.resolution
        cameraWidth = resolution.width
        cameraHeight = resolution.height
        st.setDefaultBufferSize(resolution.width, resolution.height)
        val surface = Surface(st)
        request.setTransformationInfoListener(DIRECT) { info ->
            post {
                // Only the buffer's orientation is read here (to know whether width and height swap). The upright
                // rotation itself is not ours: with a camera transform (always the case, the Surface is fed straight
                // by the camera) the camera applies it, either in the buffer (back lens: identity matrix) or in
                // getTransformMatrix (front lens: rotation and mirror). Rotating again would double it.
                rotationDegrees = info.rotationDegrees
                mirrored = info.isMirroring
            }
        }
        request.provideSurface(surface, DIRECT) { surface.release() }
    }

    /**
     * Center-crop: maps view coordinates (centered, y up) to camera-buffer coordinates (before the SurfaceTexture matrix),
     * plus the front camera's mirroring.
     */
    private fun updateViewToCamera() {
        if (cameraWidth == 0 || viewHeight == 0) return
        val turned = rotationDegrees % 180 != 0
        val uprightW = if (turned) cameraHeight else cameraWidth
        val uprightH = if (turned) cameraWidth else cameraHeight
        val viewAspect = viewWidth.toFloat() / viewHeight
        val imageAspect = uprightW.toFloat() / uprightH
        val sx = if (viewAspect < imageAspect) viewAspect / imageAspect else 1f
        val sy = if (viewAspect < imageAspect) 1f else imageAspect / viewAspect
        // Column-major mat2: diag(+-sx, sy).
        viewToCamera[0] = if (mirrored) -sx else sx
        viewToCamera[3] = sy
        val published = crop
        if (published == null || published.x != sx || published.y != sy) crop = PointF(sx, sy)
    }

    /**
     * Rule 33: view-normalised ([x], [y]) -> the frame coordinates `CameraController.focusAt` takes, using the same
     * the same factors as the shader: null while [crop] is null, otherwise reads only [crop]. Main thread.
     */
    fun viewToFrame(x: Float, y: Float): PointF? = TODO()

    private fun setSampling(target: Int, filter: Int) {
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MIN_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_MAG_FILTER, filter)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glTexParameteri(target, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE)
    }

    private fun buildProgram(): Int {
        val linked = GLES30.glCreateProgram()
        GLES30.glAttachShader(linked, compile(GLES30.GL_VERTEX_SHADER, VERTEX_SHADER))
        GLES30.glAttachShader(linked, compile(GLES30.GL_FRAGMENT_SHADER, FRAGMENT_SHADER))
        GLES30.glBindAttribLocation(linked, POS_LOCATION, "aPos")
        GLES30.glLinkProgram(linked)
        val status = IntArray(1)
        GLES30.glGetProgramiv(linked, GLES30.GL_LINK_STATUS, status, 0)
        check(status[0] != 0) { GLES30.glGetProgramInfoLog(linked) }
        return linked
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES30.glCreateShader(type)
        GLES30.glShaderSource(shader, source)
        GLES30.glCompileShader(shader)
        val status = IntArray(1)
        GLES30.glGetShaderiv(shader, GLES30.GL_COMPILE_STATUS, status, 0)
        check(status[0] != 0) { GLES30.glGetShaderInfoLog(shader) }
        return shader
    }

    private companion object {
        const val POS_LOCATION = 0
        const val NOISE_TILE = 64
        const val NOISE_SEED = 1L
        val DIRECT = Executor { it.run() }
        val QUAD = floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        const val VERTEX_SHADER = """#version 300 es
in vec2 aPos;
uniform mat4 uTexMatrix;       // SurfaceTexture.getTransformMatrix
uniform mat2 uViewToCamera;    // center-crop, front-camera mirroring
out vec2 vCameraUv;
void main() {
    vec2 uv = aPos * 0.5 + 0.5;
    vCameraUv = (uTexMatrix * vec4(uViewToCamera * (uv - 0.5) + 0.5, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
"""

        /** Half-texel offset: coord = c * (N-1)/N + 0.5/N maps hardware trilinear sampling onto the LUT nodes. */
        const val FRAGMENT_SHADER = """#version 300 es
#extension GL_OES_EGL_image_external_essl3 : require
precision mediump float;
uniform mediump samplerExternalOES uCamera;
uniform mediump sampler3D uLut;
uniform mediump sampler2D uNoise;
uniform float uGrain;          // GrainLevel.scale; 0 = none
uniform ivec2 uNoiseOffset;    // changes every frame
in vec2 vCameraUv;
out vec4 oColor;
void main() {
    vec3 c = texture(uCamera, vCameraUv).rgb;
    float n = float(textureSize(uLut, 0).x);
    vec3 graded = texture(uLut, c * ((n - 1.0) / n) + 0.5 / n).rgb;
    float noise = texelFetch(uNoise, (ivec2(gl_FragCoord.xy) + uNoiseOffset) % textureSize(uNoise, 0), 0).r - 0.5;
    oColor = vec4(clamp(graded + noise * uGrain * 0.12, 0.0, 1.0), 1.0);
}
"""
    }
}
