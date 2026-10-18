package com.eunho.tagohub.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp

private val Light = lightColorScheme(
    primary = Color(0xFF294653),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8E7),
    onPrimaryContainer = Color(0xFF172F36),
    secondary = Color(0xFFB45F3D),
    onSecondary = Color.White,
    background = Color(0xFFF5F3EE),
    onBackground = Color(0xFF232A2C),
    surface = Color(0xFFFBFAF6),
    onSurface = Color(0xFF232A2C),
    surfaceVariant = Color(0xFFECE9E1),
    onSurfaceVariant = Color(0xFF5D6666),
    outline = Color(0xFF858B86),
    outlineVariant = Color(0xFFD8D6CE),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFA8C5C5),
    onPrimary = Color(0xFF173138),
    primaryContainer = Color(0xFF304A50),
    onPrimaryContainer = Color(0xFFDCE8E7),
    secondary = Color(0xFFE4A184),
    onSecondary = Color(0xFF3E1B0E),
    background = Color(0xFF171C1D),
    onBackground = Color(0xFFE7E6DF),
    surface = Color(0xFF1B2122),
    onSurface = Color(0xFFE7E6DF),
    surfaceVariant = Color(0xFF303737),
    onSurfaceVariant = Color(0xFFC0C8C4),
    outline = Color(0xFF899390),
    outlineVariant = Color(0xFF424A49),
)

private val AppShapes = Shapes().copy(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
)

@Composable
fun TagoTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = if (isSystemInDarkTheme()) Dark else Light, shapes = AppShapes, content = content)
}
