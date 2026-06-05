package com.cyberbot.ai.hologram.animations

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Glowing particles orbiting the energy orb. Each particle rides the equatorial
 * plane (tilted by the orb's X rotation so it tracks the sphere) and advances by
 * [orbitAngle]. Particles near the viewer are drawn larger and brighter to sell
 * the 3D depth.
 */
internal fun DrawScope.drawParticles(
    center: Offset,
    scale: Float,
    distance: Float,
    radius: Float,
    rotX: Float,
    orbitAngle: Float,
    count: Int,
    color: Color,
    alpha: Float,
) {
    for (i in 0 until count) {
        val phi = (2.0 * PI * i / count).toFloat() + orbitAngle
        val p = V3(radius * cos(phi), 0f, radius * sin(phi)).rotateX(rotX)
        val o = p.project(center, scale, distance)

        val depth = frontFactor(p.z, radius)
        val dot = 2.5f + 4f * depth
        val a = (alpha * (0.35f + 0.65f * depth)).coerceIn(0f, 1f)

        // Soft halo then a bright core.
        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(color.copy(alpha = a), color.copy(alpha = 0f)),
                center = o,
                radius = dot * 3f,
            ),
            radius = dot * 3f,
            center = o,
        )
        drawCircle(color = color.copy(alpha = a), radius = dot, center = o)
    }
}
