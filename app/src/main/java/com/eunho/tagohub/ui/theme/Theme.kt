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
    // 실시간 보드(도착 안내판): 라이트·다크와 무관하게 늘 같은 남색 판을 쓴다.
    // "어두운 판 = 지금 바뀌는 값"을 사용자가 학습하도록 화면마다 같은 값을 쓴다.
    val Surface = Color(0xFF0C1522)         // 보드 바탕
    val SurfaceAlt = Color(0xFF121C2A)      // 보드 안 카드·열 구분 바탕
    val Line = Color(0x1FFFFFFF)            // 보드 안 구분선 (흰색 12%)
    val On = Color(0xFFF2F6FB)              // 보드 위 본문
    val Muted = Color(0xFF9FB0C6)           // 보드 위 보조 설명
    val Link = Color(0xFF9DB4FF)            // 보드 위 누를 수 있는 글자
    val Live = Color(0xFFC9F751)            // LIVE·임박 강조 (위에는 반드시 잉크색 글자)
    val OnLive = Color(0xFF0B1220)
}

// 디자인: 1(맑은 파랑) + 5(말랑 파스텔) + 9(유리 지도). 파란 강조 하나, 크게 둥근 모서리, 지도 위 반투명 카드
private val Light = lightColorScheme(
    primary = Color(0xFF2563EB),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6EEFF),
    onPrimaryContainer = Color(0xFF0F2A6B),
    secondary = Color(0xFF2563EB),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6EEFF),
    onSecondaryContainer = Color(0xFF0F2A6B),
    background = Color(0xFFEEF3FA),
    onBackground = Color(0xFF0F172A),
    surface = Color(0xFFFFFFFF),
    onSurface = Color(0xFF0F172A),
    surfaceVariant = Color(0xFFEEF2F8),
    onSurfaceVariant = Color(0xFF5B6577),
    outline = Color(0xFF7C8A9C),
    outlineVariant = Color(0xFFE3E9F2),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF6EA0FF),
    onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF1A2A4A),
    onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF6EA0FF),
    onSecondary = Color(0xFF0B1220),
    secondaryContainer = Color(0xFF1A2A4A),
    onSecondaryContainer = Color(0xFFDCE6FF),
    background = Color(0xFF0B1220),
    onBackground = Color(0xFFF2F6FB),
    surface = Color(0xFF141C2E),
    onSurface = Color(0xFFEAF0FF),
    surfaceVariant = Color(0xFF1C2640),
    onSurfaceVariant = Color(0xFF9AA6BF),
    outline = Color(0xFF74849A),
    outlineVariant = Color(0xFF243049),
)

/** 읽기 쉬운 글자 크기: 본문 15~16, 보조 설명 14, 작은 레이블도 13sp 이상. 숫자·제목은 굵게 */
private val AppTypography = Typography().let { d ->
    d.copy(
        bodyLarge = d.bodyLarge.copy(fontSize = 16.sp, lineHeight = 22.sp),
        bodyMedium = d.bodyMedium.copy(fontSize = 15.sp, lineHeight = 21.sp),
        bodySmall = d.bodySmall.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelLarge = d.labelLarge.copy(fontSize = 14.sp, lineHeight = 20.sp),
        labelMedium = d.labelMedium.copy(fontSize = 14.sp, lineHeight = 19.sp),
        labelSmall = d.labelSmall.copy(fontSize = 13.sp, lineHeight = 18.sp),
        titleSmall = d.titleSmall.copy(fontSize = 16.sp, lineHeight = 22.sp),
        titleMedium = d.titleMedium.copy(fontSize = 18.sp, lineHeight = 24.sp),
        titleLarge = d.titleLarge.copy(fontSize = 24.sp, lineHeight = 30.sp),
        headlineSmall = d.headlineSmall.copy(fontSize = 26.sp, lineHeight = 32.sp),
        headlineMedium = d.headlineMedium.copy(fontSize = 30.sp, lineHeight = 36.sp),
    )
}

/** 화면 제목 머리띠 색: 모든 화면에서 한 가지 남색 (교통수단 색은 번호 배지·아이콘에만) */
@Composable
fun headerColor(): Color = if (LocalDarkTheme.current) Color(0xFF101B2B) else Color(0xFF0B1220)

private val AppShapes = Shapes().copy(
    small = RoundedCornerShape(14.dp),
    medium = RoundedCornerShape(24.dp),
    large = RoundedCornerShape(30.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

/** 바로가기 칸마다 다른 파스텔 (파랑 · 민트 · 보라 · 살구). 아이콘은 같은 계열의 진한 색 */
object Pastel {
    private val lightBg = listOf(Color(0xFFE6EEFF), Color(0xFFDDF6EF), Color(0xFFECE6FF), Color(0xFFFFEDE2))
    private val darkBg = listOf(Color(0x2E6EA0FF), Color(0x2E2FC4A3), Color(0x33A78BFA), Color(0x2EFFAA78))
    private val lightIcon = listOf(Color(0xFF2563EB), Color(0xFF119C80), Color(0xFF7C5CFF), Color(0xFFE07A3F))
    private val darkIcon = listOf(Color(0xFF6EA0FF), Color(0xFF2FC4A3), Color(0xFFA78BFA), Color(0xFFFFAA78))

    @Composable
    fun bg(i: Int): Color = (if (LocalDarkTheme.current) darkBg else lightBg)[i % 4]

    @Composable
    fun icon(i: Int): Color = (if (LocalDarkTheme.current) darkIcon else lightIcon)[i % 4]
}

/** 지도 위에 뜨는 카드·시트 바탕: 살짝 비치는 유리 느낌 (뒤 지도가 은은하게 보임) */
@Composable
fun glassColor(): Color = MaterialTheme.colorScheme.surface.copy(alpha = if (LocalDarkTheme.current) 0.90f else 0.92f)

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
