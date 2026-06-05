package com.cyberbot.ai.hologram

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        // Pull the camera back so the whole body is framed, looking at the origin
        // (identity rotation already looks down -Z, toward the model at 0,0,0).
        position = Position(x = 0f, y = 0f, z = CAMERA_DISTANCE)
        // Lens zoom: higher focalLength = narrower FOV = tighter framing.
        // ~45mm gives roughly a 30 degrees vertical field of view.
        focalLength = CAMERA_FOCAL_LENGTH
    }
    val childNodes = rememberNodes()

    // The avatar node, created once the GLB finishes loading off the main thread.
    var modelNode by remember { mutableStateOf<ModelNode?>(null) }
    LaunchedEffect(Unit) {
        val instance = modelLoader.loadModelInstance(AVATAR_ASSET) ?: return@LaunchedEffect
        val node = ModelNode(
            modelInstance = instance,
            // No animation plays until we explicitly choose one below.
            autoAnimate = false,
            // Normalize size and center the body at the origin for predictable framing.
            scaleToUnits = MODEL_FIT_UNITS,
            centerOrigin = Position(0f, 0f, 0f),
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

private const val AVATAR_ASSET = "models/avatar.glb"
private const val STANDBY_MIN_MS = 30_000L
private const val STANDBY_MAX_MS = 60_000L

// Camera/model framing (tuned for a 720x1480 portrait screen). The avatar fills
// roughly the central half of the screen height, full body, centered.
//   - bigger CAMERA_DISTANCE  -> avatar smaller
//   - bigger CAMERA_FOCAL_LENGTH -> avatar bigger (tighter lens)
//   - bigger MODEL_FIT_UNITS -> avatar bigger
private const val CAMERA_DISTANCE = 20.0f
private const val CAMERA_FOCAL_LENGTH = 28.0 // ~46 degrees vertical FOV (wider lens)
private const val MODEL_FIT_UNITS = 1.0f
