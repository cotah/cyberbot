package com.cyberbot.ai.hologram

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import android.util.Log
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.cyberbot.ai.network.models.CyberbotState
import com.google.android.filament.Skybox
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.math.Scale
import io.github.sceneview.node.ModelNode
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberModelLoader
import io.github.sceneview.rememberNodes
import kotlinx.coroutines.delay
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
        val node = ModelNode(modelInstance = instance, autoAnimate = false)

        // Measure the model as loaded. The GLB now ships upright and at real scale
        // (~1.8 m tall) because scripts/merge_animations.py bakes the Mixamo 90-deg
        // rotation and 0.01 scale into the geometry at export. We still auto-detect
        // the measured height and normalize to a fixed TARGET_HEIGHT so the on-screen
        // size stays predictable for any GLB and the body can never render "too big".
        val rawSize = node.size
        val rawCenter = node.center
        val rawHeight = rawSize.y
        val autoScale = if (rawHeight > MIN_VALID_HEIGHT) TARGET_HEIGHT / rawHeight else 1f

        // Apply the normalizing scale and re-center (the measured center is in the
        // unscaled model space, so it scales by the same factor).
        node.scale = Scale(autoScale, autoScale, autoScale)
        node.position = Position(
            x = -rawCenter.x * autoScale,
            y = -rawCenter.y * autoScale,
            z = -rawCenter.z * autoScale,
        )

        // Frame on HEIGHT for the portrait kiosk. The bind-pose bounding box has the
        // arms out (T-pose), so rawSize.x is the full arm span (~as wide as tall) and
        // framing on width would push the camera far back on a narrow portrait screen.
        // Fit the standing height into the vertical FOV instead; wide poses may reach
        // the screen sides, which keeps the avatar large. POSE_SAFETY leaves headroom
        // for arms raised above the head.
        val finalHeight = rawSize.y * autoScale  // normalized to ~TARGET_HEIGHT
        val cameraZ = computeCameraDistance(finalHeight)
        cameraNode.position = Position(x = 0f, y = 0f, z = cameraZ)
        cameraNode.near = (cameraZ * 0.05f).coerceIn(0.01f, 1f)
        cameraNode.far = cameraZ * 10f
        Log.i(
            TAG,
            "Avatar framed: rawSize=$rawSize autoScale=$autoScale " +
                "finalHeight=$finalHeight cameraZ=$cameraZ",
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

    // The clip currently playing, so a state/emotion/tick change only switches when
    // the clip actually differs, and so we can stop exactly the previous clip.
    var currentClip by remember { mutableStateOf<String?>(null) }

    // Switch clips WITHOUT reloading the model or blanking the pose. The model is
    // loaded once (above); here we only change the animation. The old approach
    // cleared playingAnimations to empty, which left the rig with no pose for a
    // frame and snapped it to the bind T-pose — that was the flash / "half avatar".
    // Instead we stop only the previous clip and start the new one in the same
    // frame, so a valid pose is applied on every rendered frame.
    LaunchedEffect(modelNode, state, emotion, standbyTick) {
        val node = modelNode ?: return@LaunchedEffect
        val clip = selectAnimation(state, emotion)
        if (clip == currentClip) return@LaunchedEffect
        currentClip?.let { node.stopAnimation(it) }
        node.playAnimation(clip, loop = true)
        currentClip = clip
    }

    // Paint the surface black BEHIND the Scene so there is never a white/gray flash
    // while the GLB loads and before the Filament skybox is created.
    Box(modifier = modifier.background(Color.Black)) {
        Scene(
            modifier = Modifier.fillMaxSize(),
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

// --- Auto-framing -----------------------------------------------------------
// The model is normalized to TARGET_HEIGHT and the camera distance is computed
// from that, so the on-screen size is the same for any GLB scale.
//
// To make the avatar BIGGER on screen: lower FRAME_MARGIN (e.g. 1.0) or lower
// POSE_SAFETY. To make it SMALLER: raise FRAME_MARGIN (e.g. 1.4). TARGET_HEIGHT
// itself does not change the on-screen size (camera scales with it); it only
// keeps the math at a sane magnitude.
private const val TARGET_HEIGHT = 1.7f // world units the avatar is scaled to
private const val MIN_VALID_HEIGHT = 1e-4f // below this the measurement is bad
private const val CAMERA_FOCAL_LENGTH = 28.0 // lens; bigger = tighter (zoom in)
// Extra room so arms/legs swung out by an animation never clip. 1.0 = none.
private const val POSE_SAFETY = 1.35f
// Breathing room around the avatar (the ~10-15% margin). 1.0 = touches edges.
private const val FRAME_MARGIN = 1.15f

/**
 * Camera distance (looking down -Z at the origin) that fits a model of the given
 * [height] inside the vertical field of view, with [POSE_SAFETY] and
 * [FRAME_MARGIN] headroom for raised-arm poses.
 */
private fun computeCameraDistance(height: Float): Float {
    if (height <= 0f) return 5f
    // tan(verticalFov/2) = 12 / focalLength (Filament sensor half-height = 12mm).
    val tanHalfVertical = (12.0 / CAMERA_FOCAL_LENGTH).toFloat()
    val pad = POSE_SAFETY * FRAME_MARGIN
    // distance so half the height fits within the vertical frustum half-angle.
    return (height * 0.5f * pad) / tanHalfVertical
}
