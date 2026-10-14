package com.eunho.tagohub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Light = lightColorScheme(
    primary = Color(0xFF1F4E8C),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD9E4F5),
    onPrimaryContainer = Color(0xFF0E2747),
    secondary = Color(0xFF4A5A70),
    background = Color(0xFFF5F6F8),
    onBackground = Color(0xFF16202E),
    surface = Color(0xFFF5F6F8),
    onSurface = Color(0xFF16202E),
    surfaceVariant = Color(0xFFE6E9EE),
    onSurfaceVariant = Color(0xFF586273),
    outlineVariant = Color(0xFFD4D9E0),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF9DB9E6),
    onPrimary = Color(0xFF0E2747),
    background = Color(0xFF101418),
    onBackground = Color(0xFFE3E6EB),
    surface = Color(0xFF101418),
    onSurface = Color(0xFFE3E6EB),
    surfaceVariant = Color(0xFF262C34),
    onSurfaceVariant = Color(0xFFA9B1BD),
    outlineVariant = Color(0xFF39414C),
)

@Composable
fun TagoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, content = content)
}
