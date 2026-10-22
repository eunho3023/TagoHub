package com.eunho.tagohub.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.MutableStateFlow

/** 화면 모드: 기기 설정을 따르거나(시스템), 항상 밝게·어둡게. 안내 중 화면도 고른 모드를 그대로 따른다. */
enum class ThemeMode(val label: String) {
    System("시스템"), Light("라이트"), Dark("다크")
}

/** 고른 화면 모드 (기기에 저장) */
object ThemeSettings {
    val mode = MutableStateFlow(ThemeMode.System)

    private const val PREFS = "ui"
    private const val KEY_MODE = "theme_mode"

    fun load(context: Context) {
        val saved = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY_MODE, null)
        mode.value = ThemeMode.entries.firstOrNull { it.name == saved } ?: ThemeMode.System
    }

    fun set(context: Context, next: ThemeMode) {
        mode.value = next
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_MODE, next.name).apply()
    }
}

/** 지금 앱이 어두운 화면인지 (시스템 모드면 기기 설정). 지도 바탕처럼 Compose 밖에서 정하는 곳이 이걸 쓴다. */
val LocalDarkTheme = compositionLocalOf { false }

/** 안내 화면 등에서 쓰는 앰버 포인트색: 밝은 화면에서는 글자로 읽히게 진하게, 어두운 화면에서는 밝게 */
object BoardColor {
    val AmberFill = Color(0xFFFFB224)       // 경고·단속 알림 바탕 (위에 어두운 글자)
    val OnAmber = Color(0xFF0E1318)
    val Danger = Color(0xFFD1343B)
    val ArriveLight = Color(0xFF0F7B3F)
    val ArriveDark = Color(0xFF3DDC84)
}

private val Light = lightColorScheme(
    primary = Color(0xFF101828),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFFFEFD2),
    onPrimaryContainer = Color(0xFF3F2700),
    secondary = Color(0xFFA65F00),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFFFE6BF),
    onSecondaryContainer = Color(0xFF3F2700),
    background = Color(0xFFF7F8FA),
    onBackground = Color(0xFF101828),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF101828),
    surfaceVariant = Color(0xFFF0F2F5),
    onSurfaceVariant = Color(0xFF4B5662),
    outline = Color(0xFF7B8794),
    outlineVariant = Color(0xFFE3E8ED),
)

private val Dark = darkColorScheme(
    primary = Color(0xFFFFB224),
    onPrimary = Color(0xFF1A1100),
    primaryContainer = Color(0xFF3A2A08),
    onPrimaryContainer = Color(0xFFFFDFA3),
    secondary = Color(0xFFFFB224),
    onSecondary = Color(0xFF1A1100),
    secondaryContainer = Color(0xFF2E230C),
    onSecondaryContainer = Color(0xFFFFE0A0),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFF2F4F5),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFF2F4F5),
    surfaceVariant = Color(0xFF1A222B),
    onSurfaceVariant = Color(0xFFA3ADB8),
    outline = Color(0xFF6B7683),
    outlineVariant = Color(0xFF1E2630),
)

private val AppShapes = Shapes().copy(
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(18.dp),
)

@Composable
fun TagoTheme(content: @Composable () -> Unit) {
    val mode by ThemeSettings.mode.collectAsStateWithLifecycle()
    val dark = when (mode) {
        ThemeMode.System -> isSystemInDarkTheme()
        ThemeMode.Light -> false
        ThemeMode.Dark -> true
    }
    // 앱 모드가 기기 설정과 달라도 상태 표시줄·내비게이션 막대 아이콘이 배경에서 읽히게
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            val controller = WindowCompat.getInsetsController(window, view)
            controller.isAppearanceLightStatusBars = !dark
            controller.isAppearanceLightNavigationBars = !dark
        }
    }
    CompositionLocalProvider(LocalDarkTheme provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes, content = content)
    }
}
