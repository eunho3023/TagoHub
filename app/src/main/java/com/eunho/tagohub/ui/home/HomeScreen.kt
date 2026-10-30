package com.eunho.tagohub.ui.home

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentHeight
import com.eunho.tagohub.ui.common.pressScale
import com.eunho.tagohub.ui.common.riseIn
import com.eunho.tagohub.ui.map.MapMode
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirportShuttle
import androidx.compose.material.icons.outlined.Directions
import androidx.compose.material.icons.outlined.DirectionsTransit
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsSubway
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Navigation
import androidx.compose.material.icons.outlined.Traffic
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.data.SavedKind
import com.eunho.tagohub.data.SavedPlace
import com.eunho.tagohub.ui.ModeJump
import com.eunho.tagohub.ui.common.AnimatedValueText
import com.eunho.tagohub.ui.bus.arrivalsFor
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.theme.LocalDarkTheme
import com.eunho.tagohub.ui.theme.NumberFont
import com.eunho.tagohub.ui.theme.ModeColor
import com.eunho.tagohub.ui.theme.busRouteColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 아래 탭: 홈·대중교통(버스·지하철·기차·고속시외·항공)·지도·내비게이션·설정 */
enum class AppTab(val label: String, val icon: ImageVector) {
    Home("홈", Icons.Outlined.Home),
    Transit("대중교통", Icons.Outlined.DirectionsTransit),
    Map("지도", Icons.Outlined.Map),
    Navi("길찾기", Icons.Outlined.Directions),
    Settings("설정", Icons.Outlined.Settings),
}

@Composable
fun AppBottomNav(active: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Row(Modifier.fillMaxWidth().height(72.dp)) {
                AppTab.entries.forEach { tab ->
                    val on = tab == active
                    val tint = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).fillMaxSize().clickable(role = Role.Tab) { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        // 고른 탭은 아이콘 뒤에 연한 파란 알약
                        Box(
                            Modifier
                                .size(width = 52.dp, height = 28.dp)
                                .background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(14.dp)),
                            contentAlignment = Alignment.Center,
                        ) {
                            Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        }
                        Spacer(Modifier.height(2.dp))
                        // 글자 크기 설정이 커져도 탭 이름이 잘리지 않게 최대 두 줄로 표시
                        Text(
                            tab.label,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold,
                            color = tint,
                            maxLines = 2,
                            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        )
                    }
                }
            }
        }
    }
}

private class Tile(val label: String, val icon: ImageVector, val light: Color, val dark: Color, val onClick: () -> Unit)

/**
 * 첫 화면: "어디로 가시나요?" 검색(결과도 이 화면 안에), 교통수단 타일(대중교통 · 자동차), 집·회사, 자주 가는 곳(즐겨찾기 정류장의 실시간 도착).
 * 검색어를 쓰면 타일 대신 결과를 보여 주고, 결과를 눌러야 지도가 열린다.
 */
@Composable
fun HomeScreen(
    vm: MapViewModel,
    search: SearchActions,
    onOpenMap: (MapMode) -> Unit,
    onOpenTab: (AppTab) -> Unit,
    onOpenTransit: (TransitMode) -> Unit,
    onOpenPage: (ModeJump.Kind) -> Unit,
    onGoTo: (SavedPlace) -> Unit,
    onFavorite: (Favorite) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    val unusedTiles = listOf(
        Tile("버스", Icons.Outlined.DirectionsBus, Color(0xFF1F7A4A), Color(0xFF1F7A4A)) { onOpenTransit(TransitMode.Bus) },
        Tile("지하철", Icons.Outlined.DirectionsSubway, Color(0xFF4B5563), Color(0xFF556070)) { onOpenTransit(TransitMode.Subway) },
        Tile("기차", Icons.Outlined.Train, Color(0xFF1B3E8A), Color(0xFF2F55B5)) { onOpenTransit(TransitMode.Train) },
        Tile("고속·시외", Icons.Outlined.AirportShuttle, Color(0xFF0A7E8C), Color(0xFF0A7E8C)) { onOpenTransit(TransitMode.Coach) },
        Tile("항공", Icons.Outlined.Flight, Color(0xFFA85F0E), Color(0xFFA85F0E)) { onOpenTransit(TransitMode.Air) },
    )
    val road = listOf(
        Tile("길찾기", Icons.Outlined.Directions, Color(0xFF294653), Color(0xFF3A6073)) { onOpenTab(AppTab.Navi) },
        Tile(ModeJump.Kind.Road.label, Icons.Outlined.Traffic, Color(0xFF8A4B1F), Color(0xFF8A4B1F)) { onOpenPage(ModeJump.Kind.Road) },
        Tile("지도", Icons.Outlined.Map, Color(0xFF294653), Color(0xFF3A6073)) { onOpenTab(AppTab.Map) },
    )
    var query by rememberSaveable { mutableStateOf("") }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = 3
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(4.dp))
                if (query.isBlank()) {
                    BrandHeader(Modifier.riseIn(0))
                    Spacer(Modifier.height(18.dp))
                    Text(
                        "다음 여정,\n더 또렷하게.",
                        Modifier.riseIn(1),
                        fontSize = 30.sp,
                        lineHeight = 38.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-1.2).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(16.dp))
                }
                Box(Modifier.riseIn(2)) { SearchPanel(vm, SearchScope.All, query, { query = it }, search) }
                if (query.isBlank()) {
                    RecentChips(onGoTo, Modifier.riseIn(3))
                    SavedPlaces(vm, onGoTo)
                    Spacer(Modifier.height(10.dp))
                    Column(Modifier.riseIn(4)) { Favorites(vm, onFavorite) }
                    Spacer(Modifier.height(16.dp))
                    Column(Modifier.riseIn(5)) { NearbyStops(search.onStop, onShowMap = { onOpenMap(MapMode.Bus) }) }
                }
            }
        }
    }
}

/** 위쪽 로고: 환승 경로 마크 + 앱 이름 */
@Composable
private fun BrandHeader(modifier: Modifier = Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    Row(modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        androidx.compose.foundation.Canvas(Modifier.size(34.dp)) {
            val u = size.width / 48f
            val stroke = androidx.compose.ui.graphics.drawscope.Stroke(width = 6f * u, cap = androidx.compose.ui.graphics.StrokeCap.Round, join = androidx.compose.ui.graphics.StrokeJoin.Round)
            val path = androidx.compose.ui.graphics.Path().apply {
                moveTo(10f * u, 11f * u); lineTo(24f * u, 11f * u); lineTo(24f * u, 25f * u); lineTo(39f * u, 25f * u)
                moveTo(24f * u, 25f * u); lineTo(24f * u, 38f * u)
            }
            drawPath(path, accent, style = stroke)
            drawCircle(Color(0xFF13887E), 4.5f * u, androidx.compose.ui.geometry.Offset(10f * u, 11f * u))
            drawCircle(Color(0xFFF0A33A), 4.5f * u, androidx.compose.ui.geometry.Offset(39f * u, 25f * u))
            drawCircle(accent, 4.5f * u, androidx.compose.ui.geometry.Offset(24f * u, 38f * u))
        }
        Spacer(Modifier.width(10.dp))
        Column {
            Text("TagoHub", fontSize = 18.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.3).sp, color = MaterialTheme.colorScheme.onSurface)
            Text("한 번에 연결되는 교통 정보", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** 최근에 길찾기한 목적지 (눌러서 바로 길찾기) */
@Composable
private fun RecentChips(onGoTo: (SavedPlace) -> Unit, modifier: Modifier = Modifier) {
    val items by com.eunho.tagohub.data.RecentDestinations.items.collectAsStateWithLifecycle()
    if (items.isEmpty()) return
    Row(modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        items.take(2).forEach { r ->
            val src = remember { androidx.compose.foundation.interaction.MutableInteractionSource() }
            Surface(
                onClick = { onGoTo(SavedPlace(r.name, r.lat, r.lng)) },
                interactionSource = src,
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surface,
                border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                modifier = Modifier.pressScale(src),
            ) {
                Text(r.name, Modifier.heightIn(min = 32.dp).padding(horizontal = 14.dp).wrapContentHeight(Alignment.CenterVertically), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun TileSection(title: String, tiles: List<Tile>, columns: Int, dark: Boolean) {
    Text(title, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface, modifier = Modifier.padding(bottom = 8.dp, top = 4.dp))
    tiles.chunked(columns).forEach { row ->
        Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEach { t -> ModeTile(t, dark, Modifier.weight(1f)) }
            repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
        }
    }
}

@Composable
private fun ModeTile(t: Tile, dark: Boolean, modifier: Modifier = Modifier) {
    // 흰 카드에 수단 색 원형 아이콘 + 굵은 이름 (색은 아이콘에만)
    // 한 줄에 세 칸: 흰 카드, 위에 수단 색 원형 아이콘, 아래에 이름 (긴 이름도 줄바꿈 없이 한 줄에 들어가는 크기)
    Surface(
        onClick = t.onClick,
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 76.dp),
    ) {
        Column(
            Modifier.padding(horizontal = 4.dp, vertical = 10.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically),
        ) {
            Box(
                Modifier.size(34.dp).background(if (dark) t.dark else t.light, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(t.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(20.dp))
            }
            Text(
                t.label,
                fontSize = if (t.label.length >= 5) 13.sp else 14.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
            )
        }
    }
}

/** 집·회사 한 번에 가기 (저장해 둔 것만 보임) */
@Composable
private fun SavedPlaces(vm: MapViewModel, onGoTo: (SavedPlace) -> Unit) {
    val saved by vm.trip.homeWork.state.collectAsStateWithLifecycle()
    if (saved.home == null && saved.work == null) return
    val first = remember { com.eunho.tagohub.data.HomeWork.suggested(java.util.Calendar.getInstance().get(java.util.Calendar.HOUR_OF_DAY)) }
    val order = listOf(first, SavedKind.entries.first { it != first })
    Row(Modifier.padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        order.forEach { kind ->
            val place = saved[kind] ?: return@forEach
            Surface(
                onClick = { onGoTo(place) },
                shape = RoundedCornerShape(50),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {
                Row(Modifier.heightIn(min = 48.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == SavedKind.Home) Icons.Outlined.Home else Icons.Outlined.Work, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${kind.label}으로", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** 자주 가는 곳: 즐겨찾기(앞에서 4개). 정류장은 가장 먼저 오는 버스와 남은 시간을 30초마다 새로 받아 보여줌 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun Favorites(vm: MapViewModel, onFavorite: (Favorite) -> Unit) {
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val shown = remember(favs) { favs.take(4) }
    Text("즐겨찾기", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(6.dp))
    if (shown.isEmpty()) {
        Text(
            "정류장이나 노선의 ★ 를 누르면 여기에 모여요.",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    val etas by produceState(emptyMap<String, List<RouteEta>>(), shown) {
        val stops = shown.filter { it.kind == Favorite.Kind.Stop }
        while (true) {
            // 앱이 내려가 있으면 기다림. 즐겨찾기마다 동시에 받고(공공 API 동시 요청은 앱이 4개로 제한), 오는 대로 화면에 채움.
            // 전에는 하나씩 차례로 받아서 즐겨찾기가 늘수록 오래 걸리고, 다 받은 뒤에야 한꺼번에 보였음
            com.eunho.tagohub.core.AppState.awaitForeground()
            val got = LinkedHashMap<String, List<RouteEta>>(value)
            kotlinx.coroutines.coroutineScope {
                stops.forEach { f ->
                    launch {
                        got[f.id] = when (val r = runCatching { arrivalsFor(Graph.repo, f.toStop()) }.getOrNull()) {
                            is TagoResult.Success -> HomeText.routeEtas(r.data)
                            else -> got[f.id] ?: emptyList()   // 이번에 못 받으면 지난 값을 그대로 둠
                        }
                        value = LinkedHashMap(got)
                    }
                }
            }
            delay(45_000L)
        }
    }
    // 하나의 흰 카드 안에 줄로 나눠서, 도착 시간은 빨간 글씨
    val red = if (LocalDarkTheme.current) Color(0xFFFF8A80) else Color(0xFFD92D20)
    Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            shown.forEachIndexed { i, f ->
                val routes = etas[f.id].orEmpty()
                val first = routes.firstOrNull()
                val badgeText = when (f.kind) {
                    Favorite.Kind.Route -> f.name
                    Favorite.Kind.Stop -> first?.routeNo
                    Favorite.Kind.Station -> null
                }
                val badgeColor = when (f.kind) {
                    Favorite.Kind.Route -> busRouteColor(f.extra)
                    Favorite.Kind.Stop -> busRouteColor(first?.routeType)
                    Favorite.Kind.Station -> ModeColor.Subway
                }
                if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
                Row(
                    Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable { onFavorite(f) }.padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.heightIn(min = 28.dp).widthIn(min = 34.dp).background(badgeColor, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        if (badgeText != null) Text(badgeText, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
                        else Icon(Icons.Outlined.DirectionsBus, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text(
                            if (f.kind == Favorite.Kind.Route) (f.sub ?: "노선") else f.name,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Bold,
                        )
                        // 정류장: 여기 서는 노선을 모두, 노선마다 가장 빨리 오는 버스. 역: 지나는 노선
                        if (f.kind == Favorite.Kind.Stop && routes.isNotEmpty()) {
                            androidx.compose.foundation.layout.FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                                modifier = Modifier.padding(top = 4.dp),
                            ) {
                                routes.forEach { r ->
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            r.routeNo,
                                            Modifier.background(busRouteColor(r.routeType), RoundedCornerShape(6.dp)).padding(horizontal = 6.dp, vertical = 1.dp),
                                            fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1,
                                        )
                                        if (r.text != null) AnimatedValueText(" ${r.text}", label = "home-favorite-arrival") { value ->
                                            Text(value, fontFamily = NumberFont, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = if (r.seconds != null && r.seconds <= 180) red else MaterialTheme.colorScheme.onSurface)
                                        }
                                    }
                                }
                            }
                        }
                        if (f.kind == Favorite.Kind.Station && !f.sub.isNullOrBlank()) {
                            Text(f.sub, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        }
    }
}
