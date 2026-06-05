package com.cyberbot.ai.hologram

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.util.lerp
import com.cyberbot.ai.hologram.animations.DEG
import com.cyberbot.ai.hologram.animations.drawCentralGlow
import com.cyberbot.ai.hologram.animations.drawCircuitBackground
import com.cyberbot.ai.hologram.animations.drawEquatorRing
import com.cyberbot.ai.hologram.animations.drawParticles
import com.cyberbot.ai.hologram.animations.drawWaves
import com.cyberbot.ai.hologram.animations.drawWireframeSphere
import com.cyberbot.ai.hologram.animations.voiceNoise
import com.cyberbot.ai.network.models.CyberbotState
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/**
 * JARVIS-style 3D energy-orb hologram.
 *
 * A single [Canvas] renders everything at 60fps, driven by a frame clock
 * ([withFrameNanos]). 3D points are projected to the screen with a simple manual
 * perspective (no OpenGL), so the whole thing lives inside Compose.
 *
 * Layers (back to front): circuit background, central glow, rotating wireframe
 * sphere, equatorial ring, orbiting particles, a per-state effect (waves / data
 * ring / progress arc), and the bright core. The current [CyberbotState] only
 * changes colours, speeds and which effect is shown -- the orb itself is always
 * present, so transitions between states are seamless.
 */
@Composable
fun HologramRenderer(state: CyberbotState, modifier: Modifier = Modifier) {
    // Monotonic elapsed time in seconds, advanced once per rendered frame.
    val time = remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        var startNanos = 0L
        while (true) {
            withFrameNanos { now ->
                if (startNanos == 0L) startNanos = now
                time.floatValue = (now - startNanos) / 1_000_000_000f
            }
        }
    }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black), // absolute black is required for the hologram look
        contentAlignment = Alignment.Center,
    ) {
        Canvas(modifier = Modifier.fillMaxSize()) {
            val t = time.floatValue // reading the per-frame state drives the 60fps redraw
            val v = visualsFor(state)

            val minDim = size.minDimension
            val distance = 2.6f
            val scale = 0.4f * minDim * distance // projected sphere radius at z=0 == 0.4 * screen

            // ERROR shakes the whole orb; flashes on/off every 500ms.
            val errored = state == CyberbotState.ERROR
            val shake = if (errored) Offset(sin(t * 55f) * 6f, cos(t * 48f) * 6f) else Offset.Zero
            val center = Offset(size.width / 2f + shake.x, size.height / 2f + shake.y)
            val flash = if (errored && floor(t * 2f).toInt() % 2 != 0) 0.25f else 1f
            val alpha = v.baseAlpha * flash

            // Continuous rotation + breathing pulse.
            val rotY = t * v.rotSpeedY * DEG
            val rotX = t * v.rotSpeedX * DEG + 0.4f // static tilt so latitude rings stay visible
            val pulse = lerp(
                v.pulseMin,
                v.pulseMax,
                (sin(t * 2f * PI.toFloat() * v.pulseHz) + 1f) / 2f,
            )
            val effRadius = pulse // model radius (unit sphere * pulse)
            val radiusPx = 0.4f * minDim * pulse
            val orbit = t * v.particleSpeed * DEG

            // 1. Circuit background.
            drawCircuitBackground(center, 0.4f * minDim, t * 0.15f, v.secondary, 0.5f * flash)
            // 5. Central glow (drawn behind the wireframe).
            drawCentralGlow(center, radiusPx * 1.7f, v.primary, alpha)
            // Base wireframe sphere.
            drawWireframeSphere(
                center, scale, distance, effRadius, rotY, rotX,
                latLines = 12, lonLines = 12, color = v.primary, alpha = alpha,
            )
            // 2. Equatorial ring (blinks while LISTENING).
            val ringAlpha = if (state == CyberbotState.LISTENING) {
                alpha * (0.35f + 0.65f * (sin(t * 2f * PI.toFloat() * 3f) + 1f) / 2f)
            } else {
                alpha * 0.9f
            }
            drawEquatorRing(center, scale, distance, effRadius * 1.12f, rotY, rotX, v.secondary, ringAlpha, 3f)
            // 3. Orbiting particles.
            drawParticles(center, scale, distance, effRadius * 1.35f, rotX, orbit, 12, v.secondary, alpha)

            // Per-state effect.
            when (state) {
                CyberbotState.LISTENING ->
                    drawWaves(center, radiusPx, t, v.primary, count = 4, speed = 0.45f, amplitude = 1.8f, alpha = alpha * 0.8f)
                CyberbotState.SPEAKING -> {
                    val amp = 1.2f + 1.0f * voiceNoise(t) // amplitude jitters to mimic speech
                    drawWaves(center, radiusPx, t, v.primary, count = 5, speed = 0.7f, amplitude = amp, alpha = alpha * 0.8f)
                }
                CyberbotState.THINKING ->
                    drawDataRing(center, radiusPx * 1.5f, t, v.secondary, alpha)
                CyberbotState.EXECUTING ->
                    drawProgressArc(center, radiusPx * 1.5f, t, v.primary, alpha)
                else -> Unit
            }

            // Bright core.
            drawCircle(v.primary.copy(alpha = alpha), radius = radiusPx * 0.06f, center = center)
        }
    }
}

/** EXECUTING: a circular progress arc sweeping around the orb. */
private fun DrawScope.drawProgressArc(center: Offset, radiusPx: Float, t: Float, color: Color, alpha: Float) {
    val topLeft = Offset(center.x - radiusPx, center.y - radiusPx)
    val arcSize = Size(radiusPx * 2f, radiusPx * 2f)
    drawArc(color.copy(alpha = alpha * 0.2f), 0f, 360f, false, topLeft, arcSize, style = Stroke(width = 4f))
    drawArc(
        color = color.copy(alpha = alpha),
        startAngle = (t * 120f) % 360f,
        sweepAngle = 90f,
        useCenter = false,
        topLeft = topLeft,
        size = arcSize,
        style = Stroke(width = 5f, cap = StrokeCap.Round),
    )
}

/** THINKING: rotating radial ticks forming a spinning "data ring". */
private fun DrawScope.drawDataRing(center: Offset, radiusPx: Float, t: Float, color: Color, alpha: Float) {
    val ticks = 24
    val rot = t * 2.5f
    val pulseStep = (t * 8f).toInt()
    for (i in 0 until ticks) {
        val ang = rot + (2.0 * PI * i / ticks).toFloat()
        val on = (i + pulseStep) % 3 == 0
        val inner = radiusPx
        val outer = radiusPx + (if (on) 16f else 7f)
        drawLine(
            color = color.copy(alpha = (alpha * (if (on) 0.95f else 0.4f)).coerceIn(0f, 1f)),
            start = Offset(center.x + inner * cos(ang), center.y + inner * sin(ang)),
            end = Offset(center.x + outer * cos(ang), center.y + outer * sin(ang)),
            strokeWidth = 2f,
        )
    }
}

/** Per-state colours, rotation speeds (deg/sec), pulse range and particle speed. */
private data class OrbVisuals(
    val primary: Color,
    val secondary: Color,
    val rotSpeedY: Float,
    val rotSpeedX: Float,
    val pulseMin: Float,
    val pulseMax: Float,
    val pulseHz: Float,
    val particleSpeed: Float,
    val baseAlpha: Float,
)

private val GOLD = Color(0xFFFFD700)
private val CYAN = Color(0xFF00FFFF)
private val GREEN = Color(0xFF00FF88)
private val PURPLE = Color(0xFFAA44FF)
private val RED = Color(0xFFFF3333)

private fun visualsFor(state: CyberbotState): OrbVisuals = when (state) {
    // Rotation speeds are degrees/sec (the spec's per-frame values * 60fps).
    CyberbotState.STANDBY -> OrbVisuals(GOLD, CYAN, 12f, 6f, 0.95f, 1.0f, 0.4f, 22f, 0.6f)
    CyberbotState.LISTENING -> OrbVisuals(GREEN, Color(0xFF66FFAA), 26f, 8f, 0.97f, 1.03f, 1.2f, 45f, 0.85f)
    CyberbotState.THINKING -> OrbVisuals(PURPLE, Color(0xFFCC88FF), 55f, 14f, 0.9f, 1.06f, 2.2f, 100f, 0.9f)
    CyberbotState.SPEAKING -> OrbVisuals(CYAN, GOLD, 28f, 9f, 0.96f, 1.04f, 1.5f, 48f, 0.9f)
    CyberbotState.EXECUTING -> OrbVisuals(GOLD, CYAN, 60f, 16f, 0.95f, 1.02f, 2.0f, 90f, 0.9f)
    CyberbotState.ERROR -> OrbVisuals(RED, Color(0xFFFF6666), 0f, 0f, 1.0f, 1.0f, 0f, 0f, 0.85f)
}
