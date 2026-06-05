package com.cyberbot.ai.hologram.animations

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * 3D math and the core "energy orb" layers (wireframe sphere, equatorial ring,
 * central glow, circuit background) for the JARVIS-style hologram.
 *
 * Everything is rendered with a simple manual perspective projection -- no
 * OpenGL -- so it composes directly inside a Jetpack Compose [Canvas]. All
 * functions are [DrawScope] extensions so the whole hologram shares one Canvas
 * (and one consistent projection) for smooth 60fps drawing.
 */

/** Degrees-to-radians factor (the per-state speeds are expressed in degrees). */
internal const val DEG: Float = (PI / 180.0).toFloat()

/** A point in 3D model space (unit-sphere coordinates, before projection). */
internal data class V3(val x: Float, val y: Float, val z: Float)

/** Rotate around the vertical Y axis. */
internal fun V3.rotateY(a: Float): V3 {
    val c = cos(a)
    val s = sin(a)
    return V3(x * c + z * s, y, -x * s + z * c)
}

/** Rotate around the horizontal X axis. */
internal fun V3.rotateX(a: Float): V3 {
    val c = cos(a)
    val s = sin(a)
    return V3(x, y * c - z * s, y * s + z * c)
}

/**
 * Perspective projection to 2D screen space:
 *   x2d = cx + (x3d / (z3d + distance)) * scale
 *   y2d = cy + (y3d / (z3d + distance)) * scale
 */
internal fun V3.project(center: Offset, scale: Float, distance: Float): Offset {
    val f = scale / (z + distance)
    return Offset(center.x + x * f, center.y + y * f)
}

/** Brightness factor: 1.0 for points facing the viewer (low z), 0.0 for the back. */
internal fun frontFactor(z: Float, radius: Float): Float =
    ((radius - z) / (2f * radius)).coerceIn(0f, 1f)

/** The rotating wireframe sphere: [latLines] horizontal rings + [lonLines] meridians. */
internal fun DrawScope.drawWireframeSphere(
    center: Offset,
    scale: Float,
    distance: Float,
    radius: Float,
    rotY: Float,
    rotX: Float,
    latLines: Int,
    lonLines: Int,
    color: Color,
    alpha: Float,
) {
    val segments = 40

    // Latitude rings (horizontal circles stacked along Y).
    for (i in 1..latLines) {
        val theta = (PI * i / (latLines + 1)).toFloat()
        val y = radius * cos(theta)
        val ringR = radius * sin(theta)
        val path = Path()
        var depthSum = 0f
        for (s in 0..segments) {
            val phi = (2.0 * PI * s / segments).toFloat()
            val p = V3(ringR * cos(phi), y, ringR * sin(phi)).rotateY(rotY).rotateX(rotX)
            depthSum += p.z
            val o = p.project(center, scale, distance)
            if (s == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        val a = (alpha * (0.45f + 0.55f * frontFactor(depthSum / (segments + 1), radius)))
            .coerceIn(0f, 1f)
        drawPath(path, color = color.copy(alpha = a), style = Stroke(width = 2.2f))
    }

    // Longitude meridians (vertical half-circles around Y).
    for (j in 0 until lonLines) {
        val phi = (2.0 * PI * j / lonLines).toFloat()
        val path = Path()
        var depthSum = 0f
        for (s in 0..segments) {
            val theta = (PI * s / segments).toFloat()
            val p = V3(
                radius * sin(theta) * cos(phi),
                radius * cos(theta),
                radius * sin(theta) * sin(phi),
            ).rotateY(rotY).rotateX(rotX)
            depthSum += p.z
            val o = p.project(center, scale, distance)
            if (s == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        val a = (alpha * (0.45f + 0.55f * frontFactor(depthSum / (segments + 1), radius)))
            .coerceIn(0f, 1f)
        drawPath(path, color = color.copy(alpha = a), style = Stroke(width = 2.2f))
    }
}

/** The pulsing equatorial ring around the sphere. */
internal fun DrawScope.drawEquatorRing(
    center: Offset,
    scale: Float,
    distance: Float,
    radius: Float,
    rotY: Float,
    rotX: Float,
    color: Color,
    alpha: Float,
    thickness: Float,
) {
    val segments = 64
    val path = Path()
    for (s in 0..segments) {
        val phi = (2.0 * PI * s / segments).toFloat()
        val p = V3(radius * cos(phi), 0f, radius * sin(phi)).rotateY(rotY).rotateX(rotX)
        val o = p.project(center, scale, distance)
        if (s == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
    }
    drawPath(path, color = color.copy(alpha = alpha.coerceIn(0f, 1f)), style = Stroke(width = thickness))
}

/** A soft radial glow at the core of the orb. */
internal fun DrawScope.drawCentralGlow(
    center: Offset,
    radiusPx: Float,
    color: Color,
    intensity: Float,
) {
    drawCircle(
        brush = Brush.radialGradient(
            colors = listOf(
                color.copy(alpha = (0.55f * intensity).coerceIn(0f, 1f)),
                color.copy(alpha = 0f),
            ),
            center = center,
            radius = radiusPx,
        ),
        radius = radiusPx,
        center = center,
    )
}

/** Faint geometric "circuits" behind the orb: rotating polygon rings + radial spokes. */
internal fun DrawScope.drawCircuitBackground(
    center: Offset,
    radiusPx: Float,
    rot: Float,
    color: Color,
    alpha: Float,
) {
    val sides = 6
    for (r in 1..2) {
        val rr = radiusPx * (1.6f + r * 0.5f)
        val path = Path()
        for (i in 0..sides) {
            val a = rot * (if (r % 2 == 0) -1f else 1f) + (2.0 * PI * i / sides).toFloat()
            val x = center.x + rr * cos(a)
            val y = center.y + rr * sin(a)
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color = color.copy(alpha = (alpha * 0.5f).coerceIn(0f, 1f)), style = Stroke(width = 1.5f))
    }

    val spokes = 12
    val inner = radiusPx * 1.5f
    val outer = radiusPx * 2.6f
    for (i in 0 until spokes) {
        val a = -rot * 0.5f + (2.0 * PI * i / spokes).toFloat()
        drawLine(
            color = color.copy(alpha = (alpha * 0.3f).coerceIn(0f, 1f)),
            start = Offset(center.x + inner * cos(a), center.y + inner * sin(a)),
            end = Offset(center.x + outer * cos(a), center.y + outer * sin(a)),
            strokeWidth = 1f,
        )
    }
}
