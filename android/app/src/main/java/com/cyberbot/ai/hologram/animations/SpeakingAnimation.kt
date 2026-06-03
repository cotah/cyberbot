package com.cyberbot.ai.hologram.animations

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color

/** SPEAKING: cyan equalizer bars pulsing like a voice waveform. */
@Composable
fun SpeakingAnimation(modifier: Modifier = Modifier) {
    val color = Color(0xFF00FFFF)
    val barCount = 5
    val transition = rememberInfiniteTransition(label = "speaking")

    val barHeights = (0 until barCount).map { index ->
        transition.animateFloat(
            initialValue = 0.2f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(
                animation = tween(400 + index * 80, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse,
            ),
            label = "bar$index",
        )
    }

    Canvas(modifier = modifier.fillMaxSize()) {
        val barWidth = size.minDimension * 0.04f
        val gap = barWidth * 0.8f
        val totalWidth = barCount * barWidth + (barCount - 1) * gap
        val maxHeight = size.minDimension * 0.3f
        var x = center.x - totalWidth / 2f

        barHeights.forEach { state ->
            val barHeight = maxHeight * state.value
            drawRoundRect(
                color = color,
                topLeft = Offset(x, center.y - barHeight / 2f),
                size = Size(barWidth, barHeight),
                cornerRadius = CornerRadius(barWidth / 2f, barWidth / 2f),
            )
            x += barWidth + gap
        }
    }
}
