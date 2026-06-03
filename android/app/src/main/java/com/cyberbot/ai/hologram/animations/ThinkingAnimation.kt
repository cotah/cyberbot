package com.cyberbot.ai.hologram.animations

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.cos
import kotlin.math.sin

/** THINKING: purple particles orbiting a faint ring. */
@Composable
fun ThinkingAnimation(modifier: Modifier = Modifier) {
    val color = Color(0xFFAA44FF)
    val particleCount = 6
    val transition = rememberInfiniteTransition(label = "thinking")
    val angle by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2000, easing = LinearEasing)),
        label = "angle",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val orbitRadius = size.minDimension * 0.25f

        // Faint orbit guide.
        drawCircle(
            color = color.copy(alpha = 0.25f),
            radius = orbitRadius,
            center = center,
            style = Stroke(width = 2f),
        )

        // Orbiting particles.
        for (i in 0 until particleCount) {
            val theta = Math.toRadians((angle + i * (360.0 / particleCount))).toFloat()
            val x = center.x + orbitRadius * cos(theta)
            val y = center.y + orbitRadius * sin(theta)
            drawCircle(
                color = color.copy(alpha = 0.85f),
                radius = 10f,
                center = Offset(x, y),
            )
        }
    }
}
