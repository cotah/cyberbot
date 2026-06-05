package com.cyberbot.ai.hologram

import android.opengl.GLSurfaceView
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.cyberbot.ai.network.models.CyberbotState

/**
 * Compose entry point for the JARVIS-style energy orb. Hosts a [GLSurfaceView]
 * (rendered by [HologramGLRenderer] in real OpenGL ES 2.0) and forwards the
 * current [CyberbotState] to it. The GLSurfaceView is created once; state changes
 * only call [HologramGLRenderer.setState], so the orb is never reloaded.
 */
@Composable
fun HologramRenderer(state: CyberbotState, modifier: Modifier = Modifier) {
    val renderer = remember { HologramGLRenderer() }

    // Push state into the GL renderer whenever it changes (read on the GL thread).
    LaunchedEffect(state) { renderer.setState(state) }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                // RGB + 16-bit depth buffer, no alpha/stencil (opaque black surface).
                setEGLConfigChooser(8, 8, 8, 0, 16, 0)
                setRenderer(renderer)
                renderMode = GLSurfaceView.RENDERMODE_CONTINUOUSLY // 60fps
            }
        },
    )
}
