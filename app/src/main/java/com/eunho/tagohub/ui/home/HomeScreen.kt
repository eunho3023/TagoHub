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
import com.eunho.tagohub.ui.bus.arrivalsFor
import com.eunho.tagohub.ui.map.MapMode
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.theme.LocalDarkTheme
import com.eunho.tagohub.ui.theme.NumberFont
import com.eunho.tagohub.ui.theme.busRouteColor
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 아래 탭: 홈·대중교통(버스·지하철·기차·고속시외·항공)·지도·내비게이션·설정 */
enum class AppTab(val label: String, val icon: ImageVector) {
    Home("홈", Icons.Outlined.Home),
    Transit("대중교통", Icons.Outlined.DirectionsTransit),
    Map("지도", Icons.Outlined.Map),
    Navi("내비게이션", Icons.Outlined.Navigation),
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
                        Icon(tab.icon, contentDescription = null, tint = tint, modifier = Modifier.size(24.dp))
                        Spacer(Modifier.height(3.dp))
                        // 탭이 여섯 개라 칸이 좁음: 글자가 겹치거나 줄바꿈되지 않게 긴 이름은 조금 작게 한 줄로
                        Text(
                            tab.label,
                            fontSize = if (tab.label.length >= 5) 11.sp else 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = tint,
                            maxLines = 1,
                            softWrap = false,
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
    val transit = listOf(
        Tile("버스", Icons.Outlined.DirectionsBus, Color(0xFF1F7A4A), Color(0xFF1F7A4A)) { onOpenTransit(TransitMode.Bus) },
        Tile("지하철", Icons.Outlined.DirectionsSubway, Color(0xFF4B5563), Color(0xFF556070)) { onOpenTransit(TransitMode.Subway) },
        Tile("기차", Icons.Outlined.Train, Color(0xFF1B3E8A), Color(0xFF2F55B5)) { onOpenTransit(TransitMode.Train) },
        Tile("고속·시외", Icons.Outlined.AirportShuttle, Color(0xFF0A7E8C), Color(0xFF0A7E8C)) { onOpenTransit(TransitMode.Coach) },
        Tile("항공", Icons.Outlined.Flight, Color(0xFFA85F0E), Color(0xFFA85F0E)) { onOpenTransit(TransitMode.Air) },
    )
    val road = listOf(
        Tile("내비게이션", Icons.Outlined.Navigation, Color(0xFF294653), Color(0xFF3A6073)) { onOpenTab(AppTab.Navi) },
        Tile(ModeJump.Kind.Road.label, Icons.Outlined.Traffic, Color(0xFF8A4B1F), Color(0xFF8A4B1F)) { onOpenPage(ModeJump.Kind.Road) },
        Tile("지도", Icons.Outlined.Map, Color(0xFF294653), Color(0xFF3A6073)) { onOpenTab(AppTab.Map) },
    )
    var query by rememberSaveable { mutableStateOf("") }
    BoxWithConstraints(modifier.fillMaxSize()) {
        val columns = if (maxWidth >= 560.dp) 3 else 2
        Column(
            Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
                Spacer(Modifier.height(12.dp))
                if (query.isBlank()) {
                    Text(
                        "어디로\n가시나요?",
                        fontSize = 30.sp,
                        lineHeight = 36.sp,
                        fontWeight = FontWeight.Black,
                        letterSpacing = (-0.5).sp,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.height(14.dp))
                }
                SearchPanel(vm, SearchScope.All, query, { query = it }, search)
                if (query.isBlank()) {
                    Spacer(Modifier.height(16.dp))
                    TileSection("대중교통", transit, columns, dark)
                    TileSection("자동차", road, columns, dark)
                    SavedPlaces(vm, onGoTo)
                    Spacer(Modifier.height(10.dp))
                    Favorites(vm, onFavorite)
                }
            }
        }
    }
}

@Composable
private fun TileSection(title: String, tiles: List<Tile>, columns: Int, dark: Boolean) {
    Text(title, fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 6.dp, top = 2.dp))
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
    Surface(
        onClick = t.onClick,
        shape = RoundedCornerShape(20.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = modifier.heightIn(min = 92.dp),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 14.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier.size(42.dp).background(if (dark) t.dark else t.light, androidx.compose.foundation.shape.CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Icon(t.icon, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
            }
            Spacer(Modifier.width(10.dp))
            // 칸이 좁아도 글자가 겹치거나 아이콘 밑으로 줄바꿈되지 않게: 긴 이름은 작게, 한 줄
            Text(
                t.label,
                fontSize = if (t.label.length >= 5) 15.sp else 18.sp,
                fontWeight = FontWeight.ExtraBold,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                softWrap = false,
                overflow = TextOverflow.Ellipsis,
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
                    Text("${kind.label}으로", fontSize = 16.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                }
            }
        }
    }
}

/** 자주 가는 곳: 즐겨찾기(앞에서 4개). 정류장은 가장 먼저 오는 버스와 남은 시간을 30초마다 새로 받아 보여줌 */
@Composable
internal fun Favorites(vm: MapViewModel, onFavorite: (Favorite) -> Unit) {
    val favs by vm.favorites.collectAsStateWithLifecycle()
    val shown = remember(favs) { favs.take(4) }
    Text("자주 가는 곳", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(6.dp))
    if (shown.isEmpty()) {
        Text(
            "정류장이나 노선의 ★ 를 누르면 여기에 모여요.",
            fontSize = 15.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 8.dp),
        )
        return
    }
    val etas by produceState(emptyMap<String, Eta>(), shown) {
        val stops = shown.filter { it.kind == Favorite.Kind.Stop }
        while (true) {
            // 앱이 내려가 있으면 기다림. 즐겨찾기마다 동시에 받고(공공 API 동시 요청은 앱이 4개로 제한), 오는 대로 화면에 채움.
            // 전에는 하나씩 차례로 받아서 즐겨찾기가 늘수록 오래 걸리고, 다 받은 뒤에야 한꺼번에 보였음
            com.eunho.tagohub.core.AppState.awaitForeground()
            val got = LinkedHashMap<String, Eta>(value)
            kotlinx.coroutines.coroutineScope {
                stops.forEach { f ->
                    launch {
                        got[f.id] = when (val r = runCatching { arrivalsFor(Graph.repo, f.toStop()) }.getOrNull()) {
                            is TagoResult.Success -> HomeText.firstArrival(r.data)
                            else -> got[f.id] ?: Eta(null, null, null)   // 이번에 못 받으면 지난 값을 그대로 둠
                        }
                        value = LinkedHashMap(got)
                    }
                }
            }
            delay(45_000L)
        }
    }
    shown.forEach { f ->
        val eta = etas[f.id]
        val badgeText = if (f.kind == Favorite.Kind.Route) f.name else eta?.routeNo
        val badgeColor = if (f.kind == Favorite.Kind.Route) busRouteColor(f.extra) else busRouteColor(eta?.routeType)
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).clickable { onFavorite(f) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.heightIn(min = 44.dp).widthIn(min = 44.dp).background(badgeColor, RoundedCornerShape(22.dp)).padding(horizontal = 8.dp),
                contentAlignment = Alignment.Center,
            ) {
                if (badgeText != null) Text(badgeText, fontSize = 16.sp, fontWeight = FontWeight.Black, color = Color.White, maxLines = 1)
                else Icon(Icons.Outlined.DirectionsBus, contentDescription = null, tint = Color.White, modifier = Modifier.size(16.dp))
            }
            Spacer(Modifier.width(10.dp))
            Text(
                if (f.kind == Favorite.Kind.Route) (f.sub ?: "노선") else f.name,
                Modifier.weight(1f),
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (eta?.text != null) {
                Text(eta.text, fontFamily = NumberFont, fontSize = 20.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
            }
        }
    }
}
