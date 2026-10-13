package com.example.coach_android.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

// One navy ramp for every surface. A role left out falls back to Material's
// purple-grey default, which reads as a second palette next to the navy ones.
private val DarkColorScheme =
    darkColorScheme(
        primary = Color(0xFF818CF8),
        onPrimary = Color.White,
        primaryContainer = Color(0xFF312E81),
        onPrimaryContainer = Color(0xFFE0E7FF),
        secondary = Color(0xFF6366F1),
        secondaryContainer = Color(0xFF2A2960),
        onSecondaryContainer = Color(0xFFE0E7FF),
        background = Color(0xFF0F0F23),
        onBackground = Color.White,
        surface = Color(0xFF0F0F23),
        surfaceDim = Color(0xFF0F0F23),
        surfaceBright = Color(0xFF2A2A48),
        surfaceContainerLowest = Color(0xFF0A0A1A),
        surfaceContainerLow = Color(0xFF121229),
        surfaceContainer = Color(0xFF161630),
        surfaceContainerHigh = Color(0xFF1C1C3A),
        surfaceContainerHighest = Color(0xFF1E293B),
        surfaceVariant = Color(0xFF1E293B),
        onSurface = Color.White,
        onSurfaceVariant = Color(0xFFB0B0C0),
        outline = Color(0xFF334155),
        outlineVariant = Color(0xFF2A2A48),
        error = Color(0xFFFF5252),
    )

@Composable
fun CoachTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        content = content,
    )
}
