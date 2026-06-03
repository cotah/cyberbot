package com.cyberbot.ai.hologram.animations

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke

/** LISTENING: green audio waves expanding outward. */
@Composable
fun ListeningAnimation(modifier: Modifier = Modifier) {
    val color = Color(0xFF00FF88)
    val transition = rememberInfiniteTransition(label = "listening")
    val duration = 1600

    val wave1 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(duration, easing = LinearEasing)),
        label = "wave1",
    )
    val wave2 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = LinearEasing),
            initialStartOffset = StartOffset(duration / 3),
        ),
        label = "wave2",
    )
    val wave3 by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(duration, easing = LinearEasing),
            initialStartOffset = StartOffset(2 * duration / 3),
        ),
        label = "wave3",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val maxRadius = size.minDimension * 0.4f
        listOf(wave1, wave2, wave3).forEach { progress ->
            drawCircle(
                color = color.copy(alpha = (1f - progress).coerceIn(0f, 1f)),
                radius = maxRadius * progress,
                center = center,
                style = Stroke(width = 5f),
            )
        }
    }
}
