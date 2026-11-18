package com.minids.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1B1B1B),
    onPrimary = Color(0xFFFFFFFF),
    background = Color(0xFFFCFCFA),
    onBackground = Color(0xFF171717),
    surface = Color(0xFFFCFCFA),
    onSurface = Color(0xFF171717),
    surfaceVariant = Color(0xFFF1F1EE),
    onSurfaceVariant = Color(0xFF6B6B66),
    outline = Color(0xFFE4E4DF),
    error = Color(0xFFB23A2E)
)

private val Dark = darkColorScheme(
    primary = Color(0xFFEDEDED),
    onPrimary = Color(0xFF121212),
    background = Color(0xFF0D0D0D),
    onBackground = Color(0xFFF0F0F0),
    surface = Color(0xFF0D0D0D),
    onSurface = Color(0xFFF0F0F0),
    surfaceVariant = Color(0xFF2C2C2E),
    onSurfaceVariant = Color(0xFFA6A6A6),
    outline = Color(0xFF3A3A3D),
    error = Color(0xFFE0766A)
)

@Composable
fun MiniTheme(dark: Boolean, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (dark) Dark else Light,
        typography = Typography(),
        content = content
    )
}
