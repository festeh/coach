package com.example.coach_android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF818CF8),
        onPrimary = Color.White,
        secondary = Color(0xFF6366F1),
        surface = Color(0xFF0F0F23),
        surfaceContainer = Color(0xFF161630),
        surfaceContainerHighest = Color(0xFF1E293B),
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFB0B0C0),
        outline = Color(0xFF334155),
        error = Color(0xFFFF5252),
    )

@Composable
fun CoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
