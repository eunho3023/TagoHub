package com.eunho.tagohub.ui.theme

import android.app.Activity
import android.content.Context
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.ui.unit.sp
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
    primary = Color(0xFF1D4ED8),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE3EBFF),
    onPrimaryContainer = Color(0xFF0F2A6B),
    secondary = Color(0xFF1D4ED8),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE3EBFF),
    onSecondaryContainer = Color(0xFF0F2A6B),
    background = Color(0xFFFFFFFF),
    onBackground = Color(0xFF0B1220),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0B1220),
    surfaceVariant = Color(0xFFF3F5F8),
    onSurfaceVariant = Color(0xFF44505E),
    outline = Color(0xFF7B8794),
    outlineVariant = Color(0xFFD5DBE3),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF8FB0FF),
    onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF1B2A4A),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF8FB0FF),
    onSecondary = Color(0xFF0B1220),
    secondaryContainer = Color(0xFF1B2A4A),
    onSecondaryContainer = Color(0xFFDCE6FF),
    background = Color(0xFF0B0F14),
    onBackground = Color(0xFFF5F7FA),
    surface = Color(0xFF0B0F14),
    onSurface = Color(0xFFF5F7FA),
    surfaceVariant = Color(0xFF161C24),
    onSurfaceVariant = Color(0xFFB4BECA),
    outline = Color(0xFF6B7683),
    outlineVariant = Color(0xFF2A3440),
)

/** 적당한 글자 크기: 본문 15~16, 보조 설명 13, 가장 작은 글자 12. 숫자·제목은 굵게 */
private val AppTypography = Typography().let { d ->
    d.copy(
        bodyLarge = d.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = d.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = d.bodySmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        labelLarge = d.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = d.labelMedium.copy(fontSize = 13.sp, lineHeight = 17.sp),
        labelSmall = d.labelSmall.copy(fontSize = 12.sp, lineHeight = 16.sp),
        titleSmall = d.titleSmall.copy(fontSize = 15.sp, lineHeight = 21.sp),
        titleMedium = d.titleMedium.copy(fontSize = 17.sp, lineHeight = 23.sp),
        titleLarge = d.titleLarge.copy(fontSize = 22.sp, lineHeight = 28.sp),
        headlineSmall = d.headlineSmall.copy(fontSize = 24.sp, lineHeight = 30.sp),
        headlineMedium = d.headlineMedium.copy(fontSize = 28.sp, lineHeight = 34.sp),
    )
}

/** 화면 제목 머리띠 색: 모든 화면에서 한 가지 남색 (교통수단 색은 번호 배지·아이콘에만) */
@Composable
fun headerColor(): Color = if (LocalDarkTheme.current) Color(0xFF111B2B) else Color(0xFF0F2747)

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
        MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes, typography = AppTypography, content = content)
    }
}
