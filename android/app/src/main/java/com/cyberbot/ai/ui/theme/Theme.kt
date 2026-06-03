package com.cyberbot.ai.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// Cyberpunk palette.
val CyberCyan = Color(0xFF00FFFF)
val CyberPurple = Color(0xFFAA44FF)
val CyberBlack = Color(0xFF000000)
val CyberSurface = Color(0xFF0A0A0A)
val CyberErrorRed = Color(0xFFFF3333)

private val CyberColorScheme = darkColorScheme(
    primary = CyberCyan,
    secondary = CyberPurple,
    background = CyberBlack,
    surface = CyberSurface,
    error = CyberErrorRed,
    onPrimary = CyberBlack,
    onSecondary = CyberBlack,
    onBackground = CyberCyan,
    onSurface = CyberCyan,
    onError = CyberBlack,
)

@Composable
fun CyberBotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = CyberColorScheme,
        content = content,
    )
}
