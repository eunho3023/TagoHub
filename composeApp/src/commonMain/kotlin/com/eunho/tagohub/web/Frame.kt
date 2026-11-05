package com.eunho.tagohub.web

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

// ── 화면 비율에 맞춘 틀 ─────────────────────────────────────────────────────────
// 넓은 화면(PC·태블릿 가로): 왼쪽에 앱 화면(목록), 오른쪽 넓은 칸에 지도. 좁은 화면(폰): 앱처럼 한 화면 + 아래 탭.
// 카카오 지도는 한 번에 하나만 띄울 수 있어서, 넓은 화면에서는 각 화면의 지도를 오른쪽 칸으로 옮겨 그린다 ([AppMap]).

/** 넓은 화면으로 보는 기준 (이보다 넓으면 왼쪽 목록 + 오른쪽 지도) */
val WIDE_MIN: Dp = 860.dp
/** 넓은 화면의 왼쪽 목록 너비 */
val PANEL_WIDTH: Dp = 430.dp

/** 넓은 화면의 오른쪽 지도 칸: 지금 화면이 맡긴 지도 (없으면 정류장 지도) */
internal class MapPortal {
    var content by mutableStateOf<(@Composable (Modifier) -> Unit)?>(null)
    var owner: Any? = null
}

internal val LocalMapPortal = staticCompositionLocalOf<MapPortal?> { null }

/** 넓은 화면이면 true (오른쪽에 지도 칸이 있음) */
@Composable
internal fun isWide(): Boolean = LocalMapPortal.current != null

/**
 * 화면의 지도. 좁은 화면이면 그 자리에, 넓은 화면이면 오른쪽 지도 칸에 그림 (그 자리는 비움).
 * 인자는 [MapHost] 와 같음.
 */
@Composable
internal fun AppMap(
    state: MapState,
    tiles: TileLoader,
    modifier: Modifier = Modifier,
    lines: List<MapLine> = emptyList(),
    dots: List<MapDot> = emptyList(),
    onDot: (MapDot) -> Unit = {},
    onTap: () -> Unit = {},
) {
    val portal = LocalMapPortal.current
    if (portal == null) {
        MapHost(state, tiles, modifier, lines, dots, onDot, onTap)
        return
    }
    val latest by rememberUpdatedState(@Composable { m: Modifier -> MapHost(state, tiles, m, lines, dots, onDot, onTap) })
    val token = remember { Any() }
    DisposableEffect(portal) {
        portal.owner = token
        portal.content = { m -> latest(m) }
        onDispose { if (portal.owner === token) { portal.owner = null; portal.content = null } }
    }
}

/** 아래 탭 (폰 앱과 같음): 홈 · 대중교통 · 지도 · 길찾기 · 설정 */
internal enum class AppTab(val label: String, val glyph: Glyph) {
    Home("홈", Glyph.Home), Transit("대중교통", Glyph.Bus), Map("지도", Glyph.Map), Navi("길찾기", Glyph.Route), Settings("설정", Glyph.Gear),
}

@Composable
internal fun BottomNav(active: AppTab, onSelect: (AppTab) -> Unit) {
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            Row(Modifier.fillMaxWidth().height(66.dp)) {
                for (tab in AppTab.entries) {
                    val on = tab == active
                    val tint = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).fillMaxSize().clickable { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // 고른 탭은 아이콘 뒤에 연한 파란 알약
                        Box(
                            Modifier.size(width = 52.dp, height = 28.dp)
                                .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) { GlyphIcon(tab.glyph, tint, 22.dp) }
                        Spacer(Modifier.height(2.dp))
                        Text(tab.label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = tint, maxLines = 1)
                    }
                }
            }
        }
    }
}

/** 간단한 선 아이콘 (웹은 아이콘 글꼴을 따로 받지 않으려고 직접 그림) */
internal enum class Glyph { Home, Work, Star, Clock, Bus, Map, Route, Gear, Pin }

@Composable
internal fun GlyphIcon(g: Glyph, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    Canvas(modifier.size(size)) {
        val u = this.size.width / 24f
        val st = Stroke(width = 2f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
        fun p(vararg xy: Float) = Path().apply { moveTo(xy[0] * u, xy[1] * u); var i = 2; while (i < xy.size) { lineTo(xy[i] * u, xy[i + 1] * u); i += 2 } }
        fun DrawScope.line(vararg xy: Float) = drawPath(p(*xy), tint, style = st)
        when (g) {
            Glyph.Home -> { line(3f, 11f, 12f, 3.5f, 21f, 11f); line(5.5f, 9.5f, 5.5f, 20f, 18.5f, 20f, 18.5f, 9.5f); line(10f, 20f, 10f, 14f, 14f, 14f, 14f, 20f) }
            Glyph.Work -> {
                drawRoundRect(tint, Offset(3f * u, 7f * u), androidx.compose.ui.geometry.Size(18f * u, 13f * u), androidx.compose.ui.geometry.CornerRadius(2.5f * u), style = st)
                line(9f, 7f, 9f, 4.5f, 15f, 4.5f, 15f, 7f); line(3f, 12.5f, 21f, 12.5f)
            }
            Glyph.Star -> {
                val pts = (0 until 10).flatMap { i ->
                    val r = if (i % 2 == 0) 9.5f else 4f
                    val a = -PI / 2 + i * PI / 5
                    listOf(12f + r * cos(a).toFloat(), 12.5f + r * sin(a).toFloat())
                }
                drawPath(p(*(pts + pts.take(2)).toFloatArray()), tint, style = st)
            }
            Glyph.Clock -> { drawCircle(tint, 9f * u, Offset(12f * u, 12f * u), style = st); line(12f, 7f, 12f, 12f, 15.5f, 14f) }
            Glyph.Bus -> {
                drawRoundRect(tint, Offset(5f * u, 3f * u), androidx.compose.ui.geometry.Size(14f * u, 15f * u), androidx.compose.ui.geometry.CornerRadius(3f * u), style = st)
                line(5f, 11f, 19f, 11f); line(7.5f, 18f, 7.5f, 21f); line(16.5f, 18f, 16.5f, 21f)
                drawCircle(tint, 1.2f * u, Offset(8.5f * u, 14.5f * u)); drawCircle(tint, 1.2f * u, Offset(15.5f * u, 14.5f * u))
            }
            Glyph.Map -> { line(3f, 6f, 9f, 3.5f, 15f, 6f, 21f, 3.5f, 21f, 18f, 15f, 20.5f, 9f, 18f, 3f, 20.5f, 3f, 6f); line(9f, 3.5f, 9f, 18f); line(15f, 6f, 15f, 20.5f) }
            Glyph.Route -> { line(12f, 2.5f, 21.5f, 12f, 12f, 21.5f, 2.5f, 12f, 12f, 2.5f); line(9f, 15f, 9f, 11f, 14.5f, 11f); line(12.5f, 9f, 14.5f, 11f, 12.5f, 13f) }
            Glyph.Gear -> {
                drawCircle(tint, 3f * u, Offset(12f * u, 12f * u), style = st)
                drawCircle(tint, 7f * u, Offset(12f * u, 12f * u), style = st)
                for (i in 0 until 8) {
                    val a = i * PI / 4
                    line(12f + 7f * cos(a).toFloat(), 12f + 7f * sin(a).toFloat(), 12f + 9.5f * cos(a).toFloat(), 12f + 9.5f * sin(a).toFloat())
                }
            }
            Glyph.Pin -> { drawPath(p(12f, 21f, 6.5f, 13.5f, 6f, 9f, 8f, 5f, 12f, 3f, 16f, 5f, 18f, 9f, 17.5f, 13.5f, 12f, 21f), tint, style = st); drawCircle(tint, 2.2f * u, Offset(12f * u, 9.5f * u), style = st) }
        }
    }
}

/** 위쪽 로고 (폰 앱 BrandHeader 와 같은 환승 경로 마크 + 앱 이름) */
@Composable
internal fun BrandHeader(modifier: Modifier = Modifier, action: String? = null, onAction: () -> Unit = {}) {
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        Canvas(Modifier.size(34.dp)) {
            val u = size.width / 48f
            val stroke = Stroke(width = 6f * u, cap = StrokeCap.Round, join = StrokeJoin.Round)
            val path = Path().apply {
                moveTo(10f * u, 11f * u); lineTo(24f * u, 11f * u); lineTo(24f * u, 25f * u); lineTo(39f * u, 25f * u)
                moveTo(24f * u, 25f * u); lineTo(24f * u, 38f * u)
            }
            drawPath(path, accent, style = stroke)
            drawCircle(Color(0xFF13887E), 4.5f * u, Offset(10f * u, 11f * u))
            drawCircle(Color(0xFFF0A33A), 4.5f * u, Offset(39f * u, 25f * u))
            drawCircle(accent, 4.5f * u, Offset(24f * u, 38f * u))
        }
        Spacer(Modifier.width(10.dp))
        Column(Modifier.weight(1f)) {
            Text("TagoHub", fontSize = 19.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onSurface)
            Text("한 번에 연결되는 교통 정보", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (action != null) {
            Text(
                action,
                Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(16.dp))
                    .border1(MaterialTheme.colorScheme.outlineVariant, 16.dp)
                    .clickable(onClick = onAction).padding(horizontal = 18.dp, vertical = 10.dp),
                fontSize = 15.sp, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.primary,
            )
        }
    }
}

/** 1dp 테두리 */
internal fun Modifier.border1(color: Color, radius: Dp): Modifier = this.border(1.dp, color, RoundedCornerShape(radius))

/** 설정: 화면 모드, 공지사항·제보, 정보 (폰 앱 설정의 웹에서 되는 것만) */
@Composable
internal fun SettingsPane(onNotices: () -> Unit) {
    val uri = LocalUriHandler.current
    Header("설정")
    LazyColumn(Modifier.fillMaxWidth()) {
        item(key = "th") { RecentHead("화면 모드", null) }
        item(key = "tm") {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((mode, label) in listOf("auto" to "기기 설정", "light" to "밝게", "dark" to "어둡게")) {
                    val on = ThemePref.mode == mode
                    Text(
                        label,
                        Modifier.weight(1f).background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .clickable { ThemePref.set(mode) }.padding(vertical = 12.dp),
                        color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                        fontWeight = if (on) FontWeight.Bold else FontWeight.Normal, style = MaterialTheme.typography.labelLarge,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    )
                }
            }
        }
        item(key = "ih") { RecentHead("정보", null) }
        item(key = "n") { SettingRow("공지사항", null, onNotices) }
        item(key = "r") { SettingRow("앱 제보", "불편한 점이나 틀린 정보를 알려 주세요") { runCatching { uri.openUri(NoticesW.reportUrl()) } } }
        item(key = "v") {
            Text(
                "웹·PC 버전 · 폰 앱과 같은 공공 자료를 씁니다. 즐겨찾기·집·회사·최근 기록은 이 기기(브라우저)에만 저장돼요.",
                Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun SettingRow(title: String, sub: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 14.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
