package com.cyberbot.ai.hologram

import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.util.Log
import com.cyberbot.ai.network.models.CyberbotState
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * JARVIS-style 3D energy orb rendered with real OpenGL ES 2.0 (depth-buffered).
 *
 * Everything is real 3D geometry pushed through two tiny shader programs and a
 * perspective projection, composited with additive blending on absolute black:
 *
 *   - a rotating wireframe sphere (GL_LINES) with a depth-based brightness so
 *     front-facing lines glow brighter than the occluded back ones,
 *   - a pulsing equatorial ring,
 *   - twelve particles orbiting in 3D (soft glow point sprites),
 *   - circuit rays radiating from the core,
 *   - a bright central glow,
 *   - and a per-state effect: expanding sound/voice waves, a spinning data ring,
 *     or a circular progress arc.
 *
 * The current [CyberbotState] (pushed from the UI thread via [setState]) drives
 * colour, rotation speed, pulse and which effect is shown. The orb itself is
 * always present, so state changes are seamless (no reload, no flash).
 */
class HologramGLRenderer : GLSurfaceView.Renderer {

    @Volatile private var state: CyberbotState = CyberbotState.STANDBY

    /** Called from the UI thread when the assistant state changes. */
    fun setState(newState: CyberbotState) {
        state = newState
    }

    // Monotonic elapsed time in seconds (for time-based effects).
    private var startNanos = 0L
    private var timeSec = 0f

    // Accumulated rotation in degrees (frame-based, per the design spec).
    private var angleY = 0f
    private var angleX = 0f

    // --- Line program: depth-shaded wireframe ---
    private var lineProgram = 0
    private var lpPosition = 0
    private var lpMvp = 0
    private var lpMv = 0
    private var lpColor = 0
    private var lpCameraDist = 0
    private var lpRadius = 0
    private var lpAlpha = 0

    // --- Point program: soft radial glow sprites ---
    private var pointProgram = 0
    private var ppPosition = 0
    private var ppMvp = 0
    private var ppColor = 0
    private var ppPointSize = 0
    private var ppAlpha = 0

    // Static geometry.
    private lateinit var sphereBuf: FloatBuffer
    private var sphereCount = 0
    private lateinit var ringBuf: FloatBuffer       // unit circle in XZ (equator), GL_LINES
    private var ringCount = 0
    private lateinit var faceCircleBuf: FloatBuffer  // unit circle in XY (faces camera), GL_LINES
    private var faceCircleCount = 0
    private lateinit var rayBuf: FloatBuffer          // spokes radiating from the core
    private var rayCount = 0
    private lateinit var dataTickBuf: FloatBuffer     // radial ticks for the THINKING data ring
    private var dataTickCount = 0

    // Particles, repositioned every frame.
    private val particleData = FloatArray(PARTICLE_COUNT * 3)
    private lateinit var particleBuf: FloatBuffer

    // Single central glow vertex at the origin.
    private lateinit var glowBuf: FloatBuffer

    // Matrices (reused each draw call).
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val orbModel = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)

    private val cameraDistance = 4f
    private val radius = 1f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f) // absolute black background
        GLES20.glEnable(GLES20.GL_DEPTH_TEST)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE) // additive glow on black

        buildSphere(latLines = 14, lonLines = 16)
        ringBuf = buildCircle(inXyPlane = false).also { ringCount = CIRCLE_SEG * 2 }
        faceCircleBuf = buildCircle(inXyPlane = true).also { faceCircleCount = CIRCLE_SEG * 2 }
        rayBuf = buildRays().also { rayCount = RAY_COUNT * 2 }
        dataTickBuf = buildDataTicks().also { dataTickCount = DATA_TICKS * 2 }
        particleBuf = newFloatBuffer(PARTICLE_COUNT * 3)
        glowBuf = newFloatBuffer(3).apply { put(floatArrayOf(0f, 0f, 0f)); position(0) }

        lineProgram = buildProgram(LINE_VS, LINE_FS)
        lpPosition = GLES20.glGetAttribLocation(lineProgram, "aPosition")
        lpMvp = GLES20.glGetUniformLocation(lineProgram, "uMVP")
        lpMv = GLES20.glGetUniformLocation(lineProgram, "uMV")
        lpColor = GLES20.glGetUniformLocation(lineProgram, "uColor")
        lpCameraDist = GLES20.glGetUniformLocation(lineProgram, "uCameraDist")
        lpRadius = GLES20.glGetUniformLocation(lineProgram, "uRadius")
        lpAlpha = GLES20.glGetUniformLocation(lineProgram, "uAlpha")

        pointProgram = buildProgram(POINT_VS, POINT_FS)
        ppPosition = GLES20.glGetAttribLocation(pointProgram, "aPosition")
        ppMvp = GLES20.glGetUniformLocation(pointProgram, "uMVP")
        ppColor = GLES20.glGetUniformLocation(pointProgram, "uColor")
        ppPointSize = GLES20.glGetUniformLocation(pointProgram, "uPointSize")
        ppAlpha = GLES20.glGetUniformLocation(pointProgram, "uAlpha")

        Matrix.setLookAtM(view, 0, 0f, 0f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projection, 0, 45f, aspect, 1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        if (startNanos == 0L) startNanos = System.nanoTime()
        timeSec = (System.nanoTime() - startNanos) / 1_000_000_000f
        val t = timeSec
        val s = state
        val v = visualsFor(s)

        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        // Rotation (stopped while ERROR).
        if (s != CyberbotState.ERROR) {
            angleY = (angleY + v.rotY) % 360f
            angleX = (angleX + v.rotX) % 360f
        }

        // Breathing pulse; SPEAKING pulses with the (faked) voice amplitude.
        val pulse = if (s == CyberbotState.SPEAKING) {
            lerp(v.pulseMin, v.pulseMax, voiceNoise(t))
        } else {
            lerp(v.pulseMin, v.pulseMax, (sin(2f * PI.toFloat() * v.pulseHz * t) + 1f) / 2f)
        }

        // ERROR shakes the orb and flashes on/off every 500ms.
        val shakeX: Float
        val shakeY: Float
        val globalAlpha: Float
        if (s == CyberbotState.ERROR) {
            shakeX = sin(t * 55f) * 0.05f
            shakeY = cos(t * 48f) * 0.05f
            globalAlpha = if (floor(t * 2f).toInt() % 2 != 0) 0.25f else 1f
        } else {
            shakeX = 0f
            shakeY = 0f
            globalAlpha = 1f
        }
        val ga = v.baseAlpha * globalAlpha

        // Orb model: translate(shake) * rotateX * rotateY * scale(pulse).
        Matrix.setIdentityM(orbModel, 0)
        Matrix.translateM(orbModel, 0, shakeX, shakeY, 0f)
        Matrix.rotateM(orbModel, 0, angleX, 1f, 0f, 0f)
        Matrix.rotateM(orbModel, 0, angleY, 0f, 1f, 0f)
        Matrix.scaleM(orbModel, 0, pulse, pulse, pulse)

        // Central glow (additive overlay, no depth write so it never occludes).
        GLES20.glDepthMask(false)
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, shakeX, shakeY, 0f)
        drawPoints(glowBuf, 1, v.primary, ga * 0.9f, 120f * pulse, model)
        GLES20.glDepthMask(true)

        // Circuit rays (faint) and the wireframe sphere.
        drawLines(rayBuf, rayCount, v.secondary, ga * 0.45f, orbModel)
        drawLines(sphereBuf, sphereCount, v.primary, ga, orbModel)

        // Equatorial ring (slightly larger than the sphere; blinks while LISTENING).
        val ringAlpha = if (s == CyberbotState.LISTENING) {
            ga * (0.3f + 0.7f * (sin(2f * PI.toFloat() * 3f * t) + 1f) / 2f)
        } else {
            ga * 0.9f
        }
        System.arraycopy(orbModel, 0, model, 0, 16)
        Matrix.scaleM(model, 0, 1.12f, 1.12f, 1.12f)
        drawLines(ringBuf, ringCount, v.secondary, ringAlpha, model)

        // Orbiting particles (additive overlay).
        updateParticles(t, v.particleSpeed)
        GLES20.glDepthMask(false)
        drawPoints(particleBuf, PARTICLE_COUNT, v.secondary, ga, 18f, orbModel)

        // Per-state effect (also additive overlays).
        when (s) {
            CyberbotState.LISTENING ->
                drawWaves(t, v.primary, ga, count = 4, speed = 0.5f, amplitude = 0.9f, shakeX, shakeY)
            CyberbotState.SPEAKING -> {
                val amp = 0.55f + 0.95f * voiceNoise(t)
                drawWaves(t, v.primary, ga, count = 5, speed = 0.7f, amplitude = amp, shakeX, shakeY)
            }
            CyberbotState.THINKING -> drawDataRing(t, v.secondary, ga, shakeX, shakeY)
            CyberbotState.EXECUTING -> drawProgressArc(t, v.primary, ga, shakeX, shakeY)
            else -> Unit
        }
        GLES20.glDepthMask(true)
    }

    // --- Per-state effects --------------------------------------------------

    /** Expanding rings growing out of the sphere (sound for LISTENING, voice for SPEAKING). */
    private fun drawWaves(
        t: Float, color: FloatArray, ga: Float,
        count: Int, speed: Float, amplitude: Float, shakeX: Float, shakeY: Float,
    ) {
        for (i in 0 until count) {
            val phase = frac(t * speed + i.toFloat() / count)
            val scale = 1f + phase * amplitude       // starts at the sphere edge, expands out
            val a = (1f - phase) * ga * 0.8f          // fades as it expands
            Matrix.setIdentityM(model, 0)
            Matrix.translateM(model, 0, shakeX, shakeY, 0f)
            Matrix.scaleM(model, 0, scale, scale, scale)
            drawLines(faceCircleBuf, faceCircleCount, color, a, model)
        }
    }

    /** THINKING: a ring of radial ticks spinning in the screen plane. */
    private fun drawDataRing(t: Float, color: FloatArray, ga: Float, shakeX: Float, shakeY: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, shakeX, shakeY, 0f)
        Matrix.rotateM(model, 0, (t * 140f) % 360f, 0f, 0f, 1f) // spin about the camera axis
        drawLines(dataTickBuf, dataTickCount, color, ga, model)
    }

    /** EXECUTING: a faint full circle with a bright 90-degree arc sweeping around it. */
    private fun drawProgressArc(t: Float, color: FloatArray, ga: Float, shakeX: Float, shakeY: Float) {
        Matrix.setIdentityM(model, 0)
        Matrix.translateM(model, 0, shakeX, shakeY, 0f)
        Matrix.scaleM(model, 0, 1.5f, 1.5f, 1.5f)
        // Faint full ring.
        drawLines(faceCircleBuf, faceCircleCount, color, ga * 0.2f, model)
        // Bright sweeping window (a quarter of the circle), wrapping around.
        val window = CIRCLE_SEG / 4
        val startSeg = (frac(t * 0.4f) * CIRCLE_SEG).toInt() % CIRCLE_SEG
        val firstRun = minOf(window, CIRCLE_SEG - startSeg)
        drawLineRange(faceCircleBuf, startSeg * 2, firstRun * 2, color, ga, model)
        if (firstRun < window) {
            drawLineRange(faceCircleBuf, 0, (window - firstRun) * 2, color, ga, model)
        }
    }

    private fun updateParticles(t: Float, speed: Float) {
        for (i in 0 until PARTICLE_COUNT) {
            val ang = t * speed + i.toFloat() / PARTICLE_COUNT * 2f * PI.toFloat()
            particleData[i * 3] = 1.35f * cos(ang)
            particleData[i * 3 + 1] = 0.3f * sin(ang * 2f + i)
            particleData[i * 3 + 2] = 1.35f * sin(ang)
        }
        particleBuf.position(0)
        particleBuf.put(particleData)
        particleBuf.position(0)
    }

    // --- Draw helpers -------------------------------------------------------

    private fun drawLines(buf: FloatBuffer, count: Int, color: FloatArray, alpha: Float, modelM: FloatArray) {
        drawLineRange(buf, 0, count, color, alpha, modelM)
    }

    private fun drawLineRange(
        buf: FloatBuffer, first: Int, count: Int, color: FloatArray, alpha: Float, modelM: FloatArray,
    ) {
        Matrix.multiplyMM(mv, 0, view, 0, modelM, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        GLES20.glUseProgram(lineProgram)
        GLES20.glUniformMatrix4fv(lpMvp, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(lpMv, 1, false, mv, 0)
        GLES20.glUniform3f(lpColor, color[0], color[1], color[2])
        GLES20.glUniform1f(lpCameraDist, cameraDistance)
        GLES20.glUniform1f(lpRadius, radius)
        GLES20.glUniform1f(lpAlpha, alpha)
        GLES20.glEnableVertexAttribArray(lpPosition)
        GLES20.glVertexAttribPointer(lpPosition, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_LINES, first, count)
        GLES20.glDisableVertexAttribArray(lpPosition)
    }

    private fun drawPoints(
        buf: FloatBuffer, count: Int, color: FloatArray, alpha: Float, size: Float, modelM: FloatArray,
    ) {
        Matrix.multiplyMM(mv, 0, view, 0, modelM, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)
        GLES20.glUseProgram(pointProgram)
        GLES20.glUniformMatrix4fv(ppMvp, 1, false, mvp, 0)
        GLES20.glUniform3f(ppColor, color[0], color[1], color[2])
        GLES20.glUniform1f(ppPointSize, size)
        GLES20.glUniform1f(ppAlpha, alpha)
        GLES20.glEnableVertexAttribArray(ppPosition)
        GLES20.glVertexAttribPointer(ppPosition, 3, GLES20.GL_FLOAT, false, 0, buf)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, count)
        GLES20.glDisableVertexAttribArray(ppPosition)
    }

    // --- Geometry builders --------------------------------------------------

    /** Wireframe unit sphere: [latLines] rings + [lonLines] meridians as GL_LINES. */
    private fun buildSphere(latLines: Int, lonLines: Int) {
        val verts = ArrayList<Float>()
        val ringSeg = 48
        val meriSeg = 24
        val rad = radius.toDouble()
        for (i in 1..latLines) {
            val theta = PI * i / (latLines + 1)
            val y = (rad * cos(theta)).toFloat()
            val rr = rad * sin(theta)
            for (sgmt in 0 until ringSeg) {
                val a0 = 2.0 * PI * sgmt / ringSeg
                val a1 = 2.0 * PI * (sgmt + 1) / ringSeg
                verts.add((rr * cos(a0)).toFloat()); verts.add(y); verts.add((rr * sin(a0)).toFloat())
                verts.add((rr * cos(a1)).toFloat()); verts.add(y); verts.add((rr * sin(a1)).toFloat())
            }
        }
        for (j in 0 until lonLines) {
            val phi = 2.0 * PI * j / lonLines
            for (sgmt in 0 until meriSeg) {
                val t0 = PI * sgmt / meriSeg
                val t1 = PI * (sgmt + 1) / meriSeg
                verts.add((rad * sin(t0) * cos(phi)).toFloat()); verts.add((rad * cos(t0)).toFloat()); verts.add((rad * sin(t0) * sin(phi)).toFloat())
                verts.add((rad * sin(t1) * cos(phi)).toFloat()); verts.add((rad * cos(t1)).toFloat()); verts.add((rad * sin(t1) * sin(phi)).toFloat())
            }
        }
        val arr = verts.toFloatArray()
        sphereCount = arr.size / 3
        sphereBuf = newFloatBuffer(arr.size).apply { put(arr); position(0) }
    }

    /** Unit circle of [CIRCLE_SEG] segments as GL_LINES, in the XY plane (faces camera) or XZ (equator). */
    private fun buildCircle(inXyPlane: Boolean): FloatBuffer {
        val arr = FloatArray(CIRCLE_SEG * 2 * 3)
        var p = 0
        for (i in 0 until CIRCLE_SEG) {
            val a0 = 2.0 * PI * i / CIRCLE_SEG
            val a1 = 2.0 * PI * (i + 1) / CIRCLE_SEG
            for (a in doubleArrayOf(a0, a1)) {
                val c = cos(a).toFloat()
                val s = sin(a).toFloat()
                if (inXyPlane) { arr[p++] = c; arr[p++] = s; arr[p++] = 0f }
                else { arr[p++] = c; arr[p++] = 0f; arr[p++] = s }
            }
        }
        return newFloatBuffer(arr.size).apply { put(arr); position(0) }
    }

    /** [RAY_COUNT] spokes from near the core out past the sphere, in the equatorial plane. */
    private fun buildRays(): FloatBuffer {
        val arr = FloatArray(RAY_COUNT * 2 * 3)
        var p = 0
        for (j in 0 until RAY_COUNT) {
            val a = 2.0 * PI * j / RAY_COUNT
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            arr[p++] = 0.12f * c; arr[p++] = 0f; arr[p++] = 0.12f * s
            arr[p++] = 1.5f * c; arr[p++] = 0f; arr[p++] = 1.5f * s
        }
        return newFloatBuffer(arr.size).apply { put(arr); position(0) }
    }

    /** [DATA_TICKS] short radial ticks in the XY plane forming the spinning data ring. */
    private fun buildDataTicks(): FloatBuffer {
        val arr = FloatArray(DATA_TICKS * 2 * 3)
        var p = 0
        for (k in 0 until DATA_TICKS) {
            val a = 2.0 * PI * k / DATA_TICKS
            val c = cos(a).toFloat()
            val s = sin(a).toFloat()
            arr[p++] = 1.3f * c; arr[p++] = 1.3f * s; arr[p++] = 0f
            arr[p++] = 1.46f * c; arr[p++] = 1.46f * s; arr[p++] = 0f
        }
        return newFloatBuffer(arr.size).apply { put(arr); position(0) }
    }

    // --- Shader plumbing ----------------------------------------------------

    private fun buildProgram(vsSource: String, fsSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        return prog
    }

    private fun compileShader(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val ok = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) {
            Log.e(TAG, "Shader compile failed: ${GLES20.glGetShaderInfoLog(shader)}")
            GLES20.glDeleteShader(shader)
        }
        return shader
    }

    // --- Per-state look -----------------------------------------------------

    /** Per-state colours (RGB 0..1), rotation (deg/frame), pulse range/Hz, particle speed and base alpha. */
    private class OrbVisuals(
        val primary: FloatArray,
        val secondary: FloatArray,
        val rotY: Float,
        val rotX: Float,
        val pulseMin: Float,
        val pulseMax: Float,
        val pulseHz: Float,
        val particleSpeed: Float,
        val baseAlpha: Float,
    )

    private fun visualsFor(s: CyberbotState): OrbVisuals = when (s) {
        CyberbotState.STANDBY -> OrbVisuals(WHITE, WHITE_DIM, 0.3f, 0.1f, 0.95f, 1.0f, 0.4f, 0.6f, 0.6f)
        CyberbotState.LISTENING -> OrbVisuals(GREEN, GREEN_DIM, 0.6f, 0.15f, 0.97f, 1.03f, 1.2f, 1.2f, 0.9f)
        CyberbotState.THINKING -> OrbVisuals(PURPLE, PURPLE_DIM, 2.0f, 0.3f, 0.9f, 1.06f, 2.2f, 2.6f, 0.9f)
        CyberbotState.SPEAKING -> OrbVisuals(CYAN, CYAN_DIM, 0.6f, 0.15f, 0.94f, 1.06f, 1.5f, 1.3f, 0.9f)
        CyberbotState.EXECUTING -> OrbVisuals(GOLD, CYAN, 1.5f, 0.3f, 0.95f, 1.02f, 2.0f, 2.4f, 0.9f)
        CyberbotState.ERROR -> OrbVisuals(RED, RED_DIM, 0f, 0f, 1.0f, 1.0f, 0f, 0f, 0.9f)
    }

    companion object {
        private const val TAG = "HologramGL"
        private const val CIRCLE_SEG = 64
        private const val RAY_COUNT = 8
        private const val DATA_TICKS = 24
        private const val PARTICLE_COUNT = 12

        // RGB 0..1, matching the design spec hex values.
        private val WHITE = floatArrayOf(0.667f, 0.667f, 0.667f)      // #AAAAAA
        private val WHITE_DIM = floatArrayOf(0.4f, 0.45f, 0.55f)
        private val GREEN = floatArrayOf(0f, 1f, 0.533f)              // #00FF88
        private val GREEN_DIM = floatArrayOf(0.4f, 1f, 0.7f)
        private val PURPLE = floatArrayOf(0.667f, 0.267f, 1f)         // #AA44FF
        private val PURPLE_DIM = floatArrayOf(0.8f, 0.53f, 1f)
        private val CYAN = floatArrayOf(0f, 1f, 1f)                   // #00FFFF
        private val CYAN_DIM = floatArrayOf(0.6f, 1f, 1f)
        private val GOLD = floatArrayOf(1f, 0.843f, 0f)              // #FFD700
        private val RED = floatArrayOf(1f, 0.2f, 0.2f)               // #FF3333
        private val RED_DIM = floatArrayOf(1f, 0.45f, 0.45f)

        private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t
        private fun frac(x: Float): Float = x - floor(x)

        /** Smooth pseudo-random 0..1 used to fake voice amplitude while SPEAKING. */
        private fun voiceNoise(t: Float): Float {
            val n = 0.5f * sin(t * 7.3f) + 0.3f * sin(t * 13.1f + 1f) + 0.2f * sin(t * 23.7f + 2f)
            return (n + 1f) / 2f
        }

        private fun newFloatBuffer(floats: Int): FloatBuffer =
            ByteBuffer.allocateDirect(floats * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

        private const val LINE_VS = """
            uniform mat4 uMVP;
            uniform mat4 uMV;
            attribute vec4 aPosition;
            varying float vEyeZ;
            void main() {
                gl_Position = uMVP * aPosition;
                vEyeZ = (uMV * aPosition).z; // eye-space depth for shading
            }
        """

        private const val LINE_FS = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uCameraDist;
            uniform float uRadius;
            uniform float uAlpha;
            varying float vEyeZ;
            void main() {
                // Map eye-z across the sphere so fragments nearer the camera glow brighter.
                float zFront = -(uCameraDist - uRadius);
                float zBack  = -(uCameraDist + uRadius);
                float f = clamp((vEyeZ - zBack) / (zFront - zBack), 0.0, 1.0);
                float bright = 0.35 + 0.65 * f;
                gl_FragColor = vec4(uColor * bright, uAlpha * bright);
            }
        """

        private const val POINT_VS = """
            uniform mat4 uMVP;
            uniform float uPointSize;
            attribute vec4 aPosition;
            void main() {
                gl_Position = uMVP * aPosition;
                gl_PointSize = uPointSize;
            }
        """

        private const val POINT_FS = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uAlpha;
            void main() {
                // Soft radial falloff inside the point sprite for a glow dot.
                float d = distance(gl_PointCoord, vec2(0.5));
                float a = smoothstep(0.5, 0.0, d);
                gl_FragColor = vec4(uColor, uAlpha * a);
            }
        """
    }
}
