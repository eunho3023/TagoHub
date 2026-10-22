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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.AirportShuttle
import androidx.compose.material.icons.outlined.Apps
import androidx.compose.material.icons.outlined.DirectionsBus
import androidx.compose.material.icons.outlined.DirectionsSubway
import androidx.compose.material.icons.outlined.Flight
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Train
import androidx.compose.material.icons.outlined.Work
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
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
import com.eunho.tagohub.ui.bus.arrivalsFor
import com.eunho.tagohub.ui.map.MapMode
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.theme.LocalDarkTheme
import com.eunho.tagohub.ui.theme.NumberFont
import com.eunho.tagohub.ui.theme.busRouteColor
import kotlinx.coroutines.delay

/** 아래 탭: 홈(지도)·버스·지하철·기차·더보기 */
enum class AppTab(val label: String, val icon: ImageVector) {
    Home("지도", Icons.Outlined.Map),
    Bus("버스", Icons.Outlined.DirectionsBus),
    Subway("지하철", Icons.Outlined.DirectionsSubway),
    Train("기차", Icons.Outlined.Train),
    More("더보기", Icons.Outlined.Apps),
}

@Composable
fun AppBottomNav(active: AppTab, onSelect: (AppTab) -> Unit, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column {
            Box(Modifier.fillMaxWidth().height(1.dp).background(MaterialTheme.colorScheme.outlineVariant))
            Row(Modifier.fillMaxWidth().height(64.dp)) {
                AppTab.entries.forEach { tab ->
                    val on = tab == active
                    val tint = if (on) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant
                    Column(
                        Modifier.weight(1f).fillMaxSize().clickable(role = Role.Tab) { onSelect(tab) },
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center,
                    ) {
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(22.dp))
                        Spacer(Modifier.height(3.dp))
                        Text(tab.label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = tint)
                    }
                }
            }
        }
    }
}

private class Tile(val label: String, val icon: ImageVector, val light: Color, val dark: Color, val onClick: () -> Unit)

/**
 * 첫 화면: "어디로 가시나요?" 검색, 교통수단 타일 6개, 집·회사, 자주 가는 곳(즐겨찾기 정류장의 실시간 도착).
 * 대중교통 화면은 노선도(Line Map) 방식, 자동차 안내는 전광판(Board) 방식이라는 디자인 방향의 출발점.
 */
@Composable
fun HomeScreen(
    vm: MapViewModel,
    onSearch: () -> Unit,
    onOpenMap: (MapMode) -> Unit,
    onOpenPage: (ModeJump.Kind) -> Unit,
    onGoTo: (SavedPlace) -> Unit,
    onFavorite: (Favorite) -> Unit,
    modifier: Modifier = Modifier,
) {
    val dark = LocalDarkTheme.current
    val tiles = listOf(
        Tile("지도", Icons.Outlined.Map, Color(0xFF294653), Color(0xFF294653)) { onOpenMap(MapMode.Transit) },
        Tile("버스", Icons.Outlined.DirectionsBus, Color(0xFF1F7A4A), Color(0xFF1F7A4A)) { onOpenMap(MapMode.Bus) },
        Tile("지하철", Icons.Outlined.DirectionsSubway, Color(0xFF4B5563), Color(0xFF556070)) { onOpenMap(MapMode.Subway) },
        Tile("기차", Icons.Outlined.Train, Color(0xFF1B3E8A), Color(0xFF2F55B5)) { onOpenPage(ModeJump.Kind.Train) },
        Tile("고속·시외", Icons.Outlined.AirportShuttle, Color(0xFF0A7E8C), Color(0xFF0A7E8C)) { onOpenPage(ModeJump.Kind.Coach) },
        Tile("항공", Icons.Outlined.Flight, Color(0xFFA85F0E), Color(0xFFA85F0E)) { onOpenPage(ModeJump.Kind.Air) },
    )
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 560.dp) 3 else 2
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                Text(
                    "어디로\n가시나요?",
                    fontSize = 26.sp,
                    lineHeight = 31.sp,
                    fontWeight = FontWeight.Black,
                    letterSpacing = (-0.5).sp,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(14.dp))
                Surface(
                    onClick = onSearch,
                    shape = RoundedCornerShape(24.dp),
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    Row(Modifier.padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Search, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text("장소 · 정류소 · 역 검색", fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Spacer(Modifier.height(16.dp))
                tiles.chunked(columns).forEach { row ->
                    Row(Modifier.fillMaxWidth().padding(bottom = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { t -> ModeTile(t, dark, Modifier.weight(1f)) }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
                SavedPlaces(vm, onGoTo)
                Spacer(Modifier.height(10.dp))
                Favorites(vm, onFavorite)
            }
        }
    }
}

@Composable
private fun ModeTile(t: Tile, dark: Boolean, modifier: Modifier = Modifier) {
    Surface(
        onClick = t.onClick,
        shape = RoundedCornerShape(16.dp),
        color = if (dark) t.dark else t.light,
        border = if (dark) BorderStroke(1.dp, Color.White.copy(alpha = 0.22f)) else null,
        modifier = modifier.heightIn(min = 76.dp),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 10.dp).heightIn(min = 56.dp), verticalArrangement = Arrangement.SpaceBetween) {
            Icon(t.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
            Text(t.label, fontSize = 15.sp, fontWeight = FontWeight.Black, color = Color.White)
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
                Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (kind == SavedKind.Home) Icons.Outlined.Home else Icons.Outlined.Work, contentDescription = null, tint = MaterialTheme.colorScheme.secondary, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(6.dp))
                    Text("${kind.label}으로", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** 자주 가는 곳: 즐겨찾기(앞에서 4개). 정류장은 가장 먼저 오는 버스와 남은 시간을 30초마다 새로 받아 보여줌 */
@Composable
private fun Favorites(vm: MapViewModel, onFavorite: (Favorite) -> Unit) {
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val shown = remember(favs) { favs.take(4) }
    Text("자주 가는 곳", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    if (shown.isEmpty()) {
        Text(
            "정류장이나 노선의 ★ 를 누르면 여기에 모여요.",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    val etas by produceState(emptyMap<String, Eta>(), shown) {
        while (true) {
            value = shown.filter { it.kind == Favorite.Kind.Stop }.associate { f ->
                f.id to when (val r = runCatching { arrivalsFor(Graph.repo, f.toStop()) }.getOrNull()) {
                    is TagoResult.Success -> HomeText.firstArrival(r.data)
                    else -> Eta(null, null, null)
                }
            }
            delay(30_000L)
        }
    }
    shown.forEach { f ->
        val eta = etas[f.id]
        val badgeText = if (f.kind == Favorite.Kind.Route) f.name else eta?.routeNo
        val badgeColor = if (f.kind == Favorite.Kind.Route) busRouteColor(f.extra) else busRouteColor(eta?.routeType)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable { onFavorite(f) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.heightIn(min = 32.dp).widthIn(min = 32.dp).background(badgeColor, RoundedCornerShape(16.dp)).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (badgeText != null) Text(badgeText, fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1)
                else Icon(Icons.Outlined.DirectionsBus, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (f.kind == Favorite.Kind.Route) (f.sub ?: "노선") else f.name,
                Modifier.weight(1f),
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (eta?.text != null) {
                Text(eta.text, fontFamily = NumberFont, fontSize = 14.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
