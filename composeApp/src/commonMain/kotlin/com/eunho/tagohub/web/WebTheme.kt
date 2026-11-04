package com.eunho.tagohub.web

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// 폰 앱과 같은 모양 (app/src/main/java/com/eunho/tagohub/ui/theme/Theme.kt·ModeColors.kt 와 같은 값).
// 앱 화면 코드를 공용으로 옮기면(5단계 다음 부분) 이 파일은 그쪽 테마로 바뀐다.

val LocalDark = compositionLocalOf { false }

private val Light = lightColorScheme(
    primary = Color(0xFF2563EB), onPrimary = Color.White,
    primaryContainer = Color(0xFFE8EFFE), onPrimaryContainer = Color(0xFF0F2A6B),
    secondary = Color(0xFF2563EB), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE8EFFE), onSecondaryContainer = Color(0xFF0F2A6B),
    background = Color(0xFFF4F6F9), onBackground = Color(0xFF111827),
    surface = Color(0xFFFFFFFF), onSurface = Color(0xFF111827),
    surfaceVariant = Color(0xFFECEFF3), onSurfaceVariant = Color(0xFF5B6573),
    outline = Color(0xFF7B8794), outlineVariant = Color(0xFFE1E6EC),
)

private val Dark = darkColorScheme(
    primary = Color(0xFF7DA2FF), onPrimary = Color(0xFF0B1220),
    primaryContainer = Color(0xFF1B2742), onPrimaryContainer = Color(0xFFDCE6FF),
    secondary = Color(0xFF7DA2FF), onSecondary = Color(0xFF0B1220),
    secondaryContainer = Color(0xFF1B2742), onSecondaryContainer = Color(0xFFDCE6FF),
    background = Color(0xFF0E1217), onBackground = Color(0xFFF3F5F7),
    surface = Color(0xFF171C23), onSurface = Color(0xFFF3F5F7),
    surfaceVariant = Color(0xFF222A33), onSurfaceVariant = Color(0xFFA9B4C0),
    outline = Color(0xFF6B7683), outlineVariant = Color(0xFF262E38),
)

private fun appTypography(font: FontFamily?): Typography {
    val d = Typography()
    fun TextStyle.f(size: Int, line: Int) = copy(fontSize = size.sp, lineHeight = line.sp).let { if (font != null) it.copy(fontFamily = font) else it }
    fun TextStyle.f() = if (font != null) copy(fontFamily = font) else this
    return d.copy(
        displayLarge = d.displayLarge.f(), displayMedium = d.displayMedium.f(), displaySmall = d.displaySmall.f(),
        headlineLarge = d.headlineLarge.f(), headlineMedium = d.headlineMedium.f(28, 34), headlineSmall = d.headlineSmall.f(24, 30),
        titleLarge = d.titleLarge.f(22, 28), titleMedium = d.titleMedium.f(18, 24), titleSmall = d.titleSmall.f(16, 22),
        bodyLarge = d.bodyLarge.f(16, 22), bodyMedium = d.bodyMedium.f(15, 21), bodySmall = d.bodySmall.f(14, 20),
        labelLarge = d.labelLarge.f(14, 20), labelMedium = d.labelMedium.f(14, 19), labelSmall = d.labelSmall.f(13, 18),
    )
}

private val AppShapes = Shapes(
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
)

/** 화면 제목 머리띠 색 (앱 headerColor 와 같음) */
@Composable
fun headerColor(): Color = if (LocalDark.current) Color(0xFF111B2B) else Color(0xFF0F2747)

/** 곧 오는 버스(3분 안) 시간 색 */
val SoonColor = Color(0xFFD7262E)

/** 버스 종류(routetp) → 노선 색 (앱 busRouteColor 와 같음) */
fun busRouteColor(type: String?): Color {
    val t = type ?: return Color(0xFF607080)
    return when {
        "간선" in t -> Color(0xFF3D5BAB)
        "지선" in t -> Color(0xFF3E9A2E)
        "광역" in t || "급행" in t || "직행" in t || "좌석" in t -> Color(0xFFD7262E)
        "마을" in t -> Color(0xFF7FA92A)
        "순환" in t -> Color(0xFFC9940A)
        t == "일반" -> Color(0xFF14A0A8)
        "일반형시내" in t -> Color(0xFF1E9E5A)
        "심야" in t || "올빼미" in t -> Color(0xFF2B2B5E)
        t == "농어촌" -> Color(0xFFE5622C)
        "외곽" in t || "농어촌" in t || "시외" in t -> Color(0xFF8E6E3B)
        "공항" in t -> Color(0xFF0090D2)
        else -> Color(0xFF607080)
    }
}

/** 폰 앱 TagoTheme 과 같은 색·글자 크기·모서리 (기기 설정의 밝게/어둡게를 따름) */
@Composable
fun WebTheme(font: FontFamily?, content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    CompositionLocalProvider(LocalDark provides dark) {
        MaterialTheme(colorScheme = if (dark) Dark else Light, shapes = AppShapes, typography = appTypography(font), content = content)
    }
}
