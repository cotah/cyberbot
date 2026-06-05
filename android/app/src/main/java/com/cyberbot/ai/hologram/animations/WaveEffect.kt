package com.cyberbot.ai.hologram.animations

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs
import kotlin.math.sin

/**
 * Concentric waves radiating from the orb. Used for LISTENING (steady sound
 * waves expanding) and SPEAKING (voice waves whose [amplitude] is jittered to
 * mimic speech). Each ring grows from [baseRadiusPx] and fades as it expands.
 */
internal fun DrawScope.drawWaves(
    center: Offset,
    baseRadiusPx: Float,
    t: Float,
    color: Color,
    count: Int,
    speed: Float,
    amplitude: Float,
    alpha: Float,
) {
    for (i in 0 until count) {
        // phase in [0,1): 0 = just emitted at the orb, 1 = fully expanded/faded.
        val phase = ((t * speed + i.toFloat() / count) % 1f + 1f) % 1f
        val r = baseRadiusPx * (1f + phase * amplitude)
        val a = (alpha * (1f - phase)).coerceIn(0f, 1f)
        if (a > 0.01f) {
            drawCircle(
                color = color.copy(alpha = a),
                radius = r,
                center = center,
                style = Stroke(width = 1f + 2f * (1f - phase)),
            )
        }
    }
}

/**
 * Pseudo-random voice amplitude in roughly [0,1], built from a few incommensurate
 * sines so the SPEAKING waves vary organically without a real RNG.
 */
internal fun voiceNoise(t: Float): Float =
    abs(sin(t * 11.0f) + sin(t * 19.3f) + sin(t * 5.7f)) / 3f
