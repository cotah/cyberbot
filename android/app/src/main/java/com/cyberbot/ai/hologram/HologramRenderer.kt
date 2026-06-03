package com.cyberbot.ai.hologram

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import com.cyberbot.ai.hologram.animations.ListeningAnimation
import com.cyberbot.ai.hologram.animations.SpeakingAnimation
import com.cyberbot.ai.hologram.animations.StandbyAnimation
import com.cyberbot.ai.hologram.animations.ThinkingAnimation
import com.cyberbot.ai.network.models.CyberbotState

/**
 * Root holographic renderer. Draws on absolute black (required for the optical
 * holographic effect) and dispatches to the animation matching the current
 * [CyberbotState].
 */
@Composable
fun HologramRenderer(state: CyberbotState, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center,
    ) {
        when (state) {
            CyberbotState.STANDBY -> StandbyAnimation()
            CyberbotState.LISTENING -> ListeningAnimation()
            CyberbotState.THINKING -> ThinkingAnimation()
            CyberbotState.SPEAKING -> SpeakingAnimation()
            CyberbotState.EXECUTING -> ExecutingAnimation()
            CyberbotState.ERROR -> ErrorAnimation()
        }
    }
}

/** EXECUTING: a yellow circular progress arc spinning. */
@Composable
private fun ExecutingAnimation(modifier: Modifier = Modifier) {
    val color = Color(0xFFFFD700)
    val transition = rememberInfiniteTransition(label = "executing")
    val sweepStart by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(1200, easing = LinearEasing)),
        label = "sweepStart",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val radius = size.minDimension * 0.2f
        val topLeft = Offset(center.x - radius, center.y - radius)
        val arcSize = Size(radius * 2, radius * 2)

        drawArc(
            color = color.copy(alpha = 0.2f),
            startAngle = 0f,
            sweepAngle = 360f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = 8f),
        )
        drawArc(
            color = color,
            startAngle = sweepStart,
            sweepAngle = 90f,
            useCenter = false,
            topLeft = topLeft,
            size = arcSize,
            style = Stroke(width = 8f, cap = StrokeCap.Round),
        )
    }
}

/** ERROR: a pulsing red X. */
@Composable
private fun ErrorAnimation(modifier: Modifier = Modifier) {
    val color = Color(0xFFFF3333)
    val transition = rememberInfiniteTransition(label = "error")
    val alpha by transition.animateFloat(
        initialValue = 0.3f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "alpha",
    )

    Canvas(modifier = modifier.fillMaxSize()) {
        val radius = size.minDimension * 0.18f
        val c = center
        val lineColor = color.copy(alpha = alpha)
        drawLine(
            color = lineColor,
            start = Offset(c.x - radius, c.y - radius),
            end = Offset(c.x + radius, c.y + radius),
            strokeWidth = 10f,
            cap = StrokeCap.Round,
        )
        drawLine(
            color = lineColor,
            start = Offset(c.x + radius, c.y - radius),
            end = Offset(c.x - radius, c.y + radius),
            strokeWidth = 10f,
            cap = StrokeCap.Round,
        )
    }
}
