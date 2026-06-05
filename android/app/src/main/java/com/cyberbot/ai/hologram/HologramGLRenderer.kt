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
import kotlin.math.sin

/**
 * JARVIS-style 3D energy orb rendered with real OpenGL ES 2.0 (depth-buffered).
 *
 * A wireframe sphere is drawn as GL_LINES through vertex/fragment shaders with a
 * real perspective projection and a model rotation matrix. The depth buffer is
 * enabled so back-facing lines are genuinely occluded, and the fragment shader
 * brightens fragments closer to the camera (cheap "lighting" + a glow alpha).
 *
 * The current [CyberbotState] is pushed in from the UI thread via [setState];
 * it only changes the orb colour and rotation speed -- the geometry is static.
 */
class HologramGLRenderer : GLSurfaceView.Renderer {

    @Volatile private var state: CyberbotState = CyberbotState.STANDBY

    /** Called from the UI thread when the assistant state changes. */
    fun setState(newState: CyberbotState) {
        state = newState
    }

    // Geometry (static unit-sphere wireframe as GL_LINES vertex pairs).
    private lateinit var vertexBuffer: FloatBuffer
    private var vertexCount = 0

    // GL program + locations.
    private var program = 0
    private var aPositionLoc = 0
    private var uMvpLoc = 0
    private var uMvLoc = 0
    private var uColorLoc = 0
    private var uCameraDistLoc = 0
    private var uRadiusLoc = 0

    // Matrices.
    private val projection = FloatArray(16)
    private val view = FloatArray(16)
    private val model = FloatArray(16)
    private val mv = FloatArray(16)
    private val mvp = FloatArray(16)

    // Accumulated rotation (degrees).
    private var angleY = 0f
    private var angleX = 0f

    private val cameraDistance = 4f
    private val radius = 1f

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        GLES20.glClearColor(0f, 0f, 0f, 1f) // absolute black background
        GLES20.glEnable(GLES20.GL_DEPTH_TEST) // real depth buffer
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE) // additive glow on black
        GLES20.glLineWidth(2f)

        buildSphere(latLines = 12, lonLines = 12)

        program = buildProgram(VERTEX_SHADER, FRAGMENT_SHADER)
        aPositionLoc = GLES20.glGetAttribLocation(program, "aPosition")
        uMvpLoc = GLES20.glGetUniformLocation(program, "uMVP")
        uMvLoc = GLES20.glGetUniformLocation(program, "uMV")
        uColorLoc = GLES20.glGetUniformLocation(program, "uColor")
        uCameraDistLoc = GLES20.glGetUniformLocation(program, "uCameraDist")
        uRadiusLoc = GLES20.glGetUniformLocation(program, "uRadius")

        // Camera on +Z looking at the origin.
        Matrix.setLookAtM(view, 0, 0f, 0f, cameraDistance, 0f, 0f, 0f, 0f, 1f, 0f)
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        val aspect = if (height == 0) 1f else width.toFloat() / height.toFloat()
        Matrix.perspectiveM(projection, 0, 45f, aspect, 1f, 20f)
    }

    override fun onDrawFrame(gl: GL10?) {
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)

        val s = state
        angleY = (angleY + rotSpeedY(s)) % 360f
        angleX = (angleX + rotSpeedX(s)) % 360f

        // Model rotation (Y then X) via matrices.
        Matrix.setIdentityM(model, 0)
        Matrix.rotateM(model, 0, angleX, 1f, 0f, 0f)
        Matrix.rotateM(model, 0, angleY, 0f, 1f, 0f)

        Matrix.multiplyMM(mv, 0, view, 0, model, 0)
        Matrix.multiplyMM(mvp, 0, projection, 0, mv, 0)

        GLES20.glUseProgram(program)
        GLES20.glUniformMatrix4fv(uMvpLoc, 1, false, mvp, 0)
        GLES20.glUniformMatrix4fv(uMvLoc, 1, false, mv, 0)
        val c = colorFor(s)
        GLES20.glUniform3f(uColorLoc, c[0], c[1], c[2])
        GLES20.glUniform1f(uCameraDistLoc, cameraDistance)
        GLES20.glUniform1f(uRadiusLoc, radius)

        GLES20.glEnableVertexAttribArray(aPositionLoc)
        GLES20.glVertexAttribPointer(aPositionLoc, 3, GLES20.GL_FLOAT, false, 0, vertexBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(aPositionLoc)
    }

    /** Build the wireframe unit sphere: [latLines] rings + [lonLines] meridians, as GL_LINES. */
    private fun buildSphere(latLines: Int, lonLines: Int) {
        val verts = ArrayList<Float>()
        val ringSeg = 48
        val meriSeg = 24
        val rad = radius.toDouble()

        // Latitude rings (horizontal circles).
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

        // Longitude meridians (pole-to-pole half circles).
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
        vertexCount = arr.size / 3
        vertexBuffer = ByteBuffer.allocateDirect(arr.size * 4)
            .order(ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply { put(arr); position(0) }
    }

    private fun buildProgram(vsSource: String, fsSource: String): Int {
        val vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSource)
        val fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSource)
        val prog = GLES20.glCreateProgram()
        GLES20.glAttachShader(prog, vs)
        GLES20.glAttachShader(prog, fs)
        GLES20.glLinkProgram(prog)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, linked, 0)
        if (linked[0] == 0) {
            Log.e(TAG, "Program link failed: ${GLES20.glGetProgramInfoLog(prog)}")
        }
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

    /** RGB (0..1) per state. */
    private fun colorFor(s: CyberbotState): FloatArray = when (s) {
        CyberbotState.STANDBY -> floatArrayOf(1f, 0.843f, 0f)      // gold  #FFD700
        CyberbotState.LISTENING -> floatArrayOf(0f, 1f, 0.533f)    // green #00FF88
        CyberbotState.THINKING -> floatArrayOf(0.667f, 0.267f, 1f) // purple #AA44FF
        CyberbotState.SPEAKING -> floatArrayOf(0f, 1f, 1f)         // cyan  #00FFFF
        CyberbotState.EXECUTING -> floatArrayOf(1f, 0.843f, 0f)    // gold  #FFD700
        CyberbotState.ERROR -> floatArrayOf(1f, 0.2f, 0.2f)        // red   #FF3333
    }

    /** Y-axis rotation speed in degrees/frame. */
    private fun rotSpeedY(s: CyberbotState): Float = when (s) {
        CyberbotState.STANDBY -> 0.2f
        CyberbotState.LISTENING -> 0.45f
        CyberbotState.THINKING -> 0.9f
        CyberbotState.SPEAKING -> 0.5f
        CyberbotState.EXECUTING -> 1.0f
        CyberbotState.ERROR -> 0f // error: rotation stopped
    }

    /** X-axis rotation speed in degrees/frame (much slower tilt). */
    private fun rotSpeedX(s: CyberbotState): Float = when (s) {
        CyberbotState.STANDBY -> 0.1f
        CyberbotState.LISTENING -> 0.15f
        CyberbotState.THINKING -> 0.25f
        CyberbotState.SPEAKING -> 0.15f
        CyberbotState.EXECUTING -> 0.3f
        CyberbotState.ERROR -> 0f
    }

    companion object {
        private const val TAG = "HologramGL"

        private const val VERTEX_SHADER = """
            uniform mat4 uMVP;
            uniform mat4 uMV;
            attribute vec4 aPosition;
            varying float vEyeZ;
            void main() {
                gl_Position = uMVP * aPosition;
                vEyeZ = (uMV * aPosition).z; // eye-space depth for shading
            }
        """

        private const val FRAGMENT_SHADER = """
            precision mediump float;
            uniform vec3 uColor;
            uniform float uCameraDist;
            uniform float uRadius;
            varying float vEyeZ;
            void main() {
                // Sphere spans eye-z in [-(dist+r), -(dist-r)]. Map to 0..1 so
                // fragments nearer the camera (front faces) are brighter.
                float zFront = -(uCameraDist - uRadius);
                float zBack  = -(uCameraDist + uRadius);
                float f = clamp((vEyeZ - zBack) / (zFront - zBack), 0.0, 1.0);
                float bright = 0.35 + 0.65 * f;
                float alpha  = 0.35 + 0.65 * f; // glow: front lines more intense
                gl_FragColor = vec4(uColor * bright, alpha);
            }
        """
    }
}
