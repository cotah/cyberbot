package com.cyberbot.ai.hologram

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.compose.ui.Modifier
import com.cyberbot.ai.network.models.CyberbotState
import com.google.android.filament.Skybox
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import kotlinx.coroutines.delay
import kotlin.math.atan
import kotlin.math.max
import kotlin.math.sin
import kotlin.random.Random

/**
 * Full-body 3D avatar rendered with SceneView (Filament). Loads a single GLB that
 * bundles the rigged character together with every Mixamo animation as a named
 * clip, then plays the clip that matches the current [CyberbotState] and
 * [emotion].
 *
 * The GLB (assets/models/avatar.glb) is produced offline from the Mixamo FBX files
 * by scripts/merge_animations.py (SceneView/Filament cannot load FBX directly).
 *
 * Replaces the previous OpenGL energy-orb renderer.
 */
@Composable
fun AvatarRenderer(
    state: CyberbotState,
    emotion: String,
    modifier: Modifier = Modifier,
) {
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val cameraNode = rememberCameraNode(engine) {
        // Lens zoom (vertical FOV ~= 2*atan(12/focalLength)). Position/clip planes
        // are computed from the model's measured size once it loads (see below).
        focalLength = CAMERA_FOCAL_LENGTH
        position = Position(z = 5f) // placeholder; auto-framed after load
    }
    val childNodes = rememberNodes()

    // The avatar node, created once the GLB finishes loading off the main thread.
    var modelNode by remember { mutableStateOf<ModelNode?>(null) }
    LaunchedEffect(Unit) {
        val instance = modelLoader.loadModelInstance(AVATAR_ASSET) ?: return@LaunchedEffect
        // Build WITHOUT scaleToUnits: the GLB's rig carries a 0.01 armature scale
        // that makes scaleToUnits unreliable, so we measure the real size instead.
        val node = ModelNode(modelInstance = instance, autoAnimate = false)

        // Center the model's bounding box on the origin so the camera can look
        // straight at its middle.
        node.centerOrigin(Position(0f, 0f, 0f))

        // Auto-frame: measure the real bounding box and pull the camera back just
        // far enough that the whole body (plus a margin for animated limbs) fits.
        val size = node.size
        val center = node.center
        val maxDim = max(size.x, max(size.y, size.z))
        val cameraZ = computeCameraDistance(maxDim)
        cameraNode.position = Position(x = 0f, y = 0f, z = cameraZ)
        cameraNode.near = (cameraZ / 100f).coerceIn(0.01f, 1f)
        cameraNode.far = max(cameraZ * 4f, 100f)
        Log.i(
            TAG,
            "Avatar framed: size=$size center=$center maxDim=$maxDim -> camera z=$cameraZ",
        )

        modelNode = node
        childNodes.add(node)
    }

    // While idle, rotate through the standby animations every 30-60 seconds.
    var standbyTick by remember { mutableIntStateOf(0) }
    LaunchedEffect(state) {
        if (state == CyberbotState.STANDBY) {
            while (true) {
                delay(Random.nextLong(STANDBY_MIN_MS, STANDBY_MAX_MS))
                standbyTick++
            }
        }
    }

    // Whenever the state, emotion, model, or standby tick changes, switch clip.
    LaunchedEffect(modelNode, state, emotion, standbyTick) {
        val node = modelNode ?: return@LaunchedEffect
        val clip = selectAnimation(state, emotion)
        // Clear any currently-playing clip so the new one plays cleanly (no blend).
        node.playingAnimations = mutableMapOf()
        node.playAnimation(clip, loop = true)
    }

    Scene(
        modifier = modifier,
        engine = engine,
        modelLoader = modelLoader,
        cameraNode = cameraNode,
        childNodes = childNodes,
        // No orbit manipulator: the camera stays exactly where we put it (kiosk).
        // Without this, SceneView's default manipulator overrides cameraNode and
        // parks the camera ~1 unit away, putting it inside the model.
        cameraManipulator = null,
        // Absolute black background (lambda receiver is the SceneView).
        onViewCreated = {
            skybox = Skybox.Builder().color(0f, 0f, 0f, 1f).build(engine)
        },
    )
}

/**
 * Pick the animation clip name for the given state and emotion. Names match the
 * clips baked into avatar.glb (the Mixamo FBX file names without extension).
 */
fun selectAnimation(state: CyberbotState, emotion: String): String = when (state) {
    CyberbotState.STANDBY -> randomStandbyAnimation()
    CyberbotState.LISTENING -> "Talking On Phone"
    CyberbotState.THINKING, CyberbotState.EXECUTING -> "Typing"
    CyberbotState.ERROR -> "Dying"
    CyberbotState.SPEAKING -> emotionToAnimation(emotion)
}

/** Map a backend emotion to a speaking animation; falls back to a neutral talk. */
fun emotionToAnimation(emotion: String): String = when (emotion) {
    "greeting" -> "Standing Greeting"
    "funny" -> "Laughing"
    "celebration" -> "Clapping"
    "weather_sun" -> "Excited"
    "weather_rain" -> "Defeated"
    "weather_storm" -> "Terrified"
    "confused" -> "Shrugging"
    "explaining" -> "Having A Meeting, Female"
    "informative" -> "Talking On Phone"
    else -> "Talking On Phone"
}

private val STANDBY_ANIMATIONS = listOf(
    "Idle",
    "Hip Hop Dancing",
    "Warming Up",
    "Punch To Elbow Combo",
    "Dribble",
    "Golf Drive",
    "Mutant Flexing Muscles",
)

fun randomStandbyAnimation(): String = STANDBY_ANIMATIONS.random()

private const val TAG = "AvatarRenderer"
private const val AVATAR_ASSET = "models/avatar.glb"
private const val STANDBY_MIN_MS = 30_000L
private const val STANDBY_MAX_MS = 60_000L

// --- Auto-framing (independent of the model's real size) -------------------
// The camera distance is computed from the measured model size so the whole
// body always fits, whatever scale the GLB happens to be. Tunables:
private const val CAMERA_FOCAL_LENGTH = 28.0 // lens; bigger = tighter (zoom in)
// Portrait kiosk screen aspect (width/height). The horizontal FOV is narrower
// than the vertical on a tall screen, so it is the binding constraint.
private const val DEVICE_ASPECT = 720f / 1480f
// Extra room so arms/legs swung out by an animation never clip. 1.0 = none.
private const val POSE_SAFETY = 1.35f
// Breathing room around the avatar. 1.0 = touches the edges; bigger = smaller.
private const val FRAME_MARGIN = 1.15f

/**
 * Distance at which a model whose largest dimension is [maxDim] fits inside the
 * (narrower) horizontal field of view, with margin. Falls back to a sane default
 * if the bounding box is not available yet.
 */
private fun computeCameraDistance(maxDim: Float): Float {
    if (maxDim <= 0f) return 5f
    val radius = 0.5f * maxDim * POSE_SAFETY
    // tan(verticalFov/2) = 12 / focalLength; horizontal is that times the aspect.
    val tanHalfHorizontal = (12.0 / CAMERA_FOCAL_LENGTH).toFloat() * DEVICE_ASPECT
    val halfHorizontalFov = atan(tanHalfHorizontal.toDouble())
    return (radius / sin(halfHorizontalFov)).toFloat() * FRAME_MARGIN
}
