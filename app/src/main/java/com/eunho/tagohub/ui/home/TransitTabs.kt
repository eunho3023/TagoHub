package com.eunho.tagohub.ui.home

import androidx.compose.foundation.background
import kotlinx.coroutines.launch
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunho.tagohub.data.Favorite
import com.eunho.tagohub.data.SubwayRegion
import com.eunho.tagohub.data.SubwayMap
import com.eunho.tagohub.ui.common.AnimatedValueText
import com.eunho.tagohub.ui.common.Pill
import com.eunho.tagohub.ui.map.MapViewModel
import com.eunho.tagohub.ui.theme.subwayLineColor

/** 버스 탭: 정류소·노선 검색(초성·자동), 즐겨찾기. 지도는 "지도에서 보기"로 */
@Composable
fun BusTab(
    vm: MapViewModel,
    search: SearchActions,
    onFavorite: (Favorite) -> Unit,
    onShowMap: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(top = 12.dp, bottom = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Column(Modifier.widthIn(max = 720.dp).fillMaxWidth().padding(horizontal = 16.dp)) {
            SearchPanel(vm, SearchScope.Bus, query, { query = it }, search)
            if (query.isBlank()) {
                Spacer(Modifier.height(16.dp))
                NearbyStops(search.onStop)
                Spacer(Modifier.height(16.dp))
                Favorites(vm, onFavorite)
                Spacer(Modifier.height(16.dp))
                androidx.compose.material3.Surface(
                    onClick = onShowMap,
                    shape = androidx.compose.foundation.shape.CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp),
                ) {
                    androidx.compose.foundation.layout.Box(contentAlignment = Alignment.Center) { Text("지도에서 보기", fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.ExtraBold) }
                }
            }
        }
    }
}

/**
 * 내 주변 정류장: 지금 위치에서 700m 안의 가까운 정류장 3곳과 오는 버스·남은 시간 (45초마다 새로 받음).
 * 위치 권한이 없거나 위치를 모르면 아무것도 보이지 않음 (여기서 권한을 새로 묻지 않음).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun NearbyStops(onStop: (com.eunho.tagohub.data.BusStop) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val stops by androidx.compose.runtime.produceState<List<Pair<com.eunho.tagohub.data.BusStop, Double>>>(emptyList()) {
        if (!com.eunho.tagohub.ui.bus.hasLocationPermission(context)) return@produceState
        val loc = com.eunho.tagohub.ui.bus.lastKnownLocation(context) ?: com.eunho.tagohub.ui.bus.currentLocation(context) ?: return@produceState
        val db = com.eunho.tagohub.data.StopDb.get(context) ?: return@produceState
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            db.near(loc.latitude, loc.longitude, 700.0, 30)
                .filter { it.first.name.isNotBlank() }
                .distinctBy { it.first.nodeId }
                .take(3)
        }
    }
    if (stops.isEmpty()) return
    val etas by androidx.compose.runtime.produceState(emptyMap<String, List<RouteEta>>(), stops) {
        while (true) {
            com.eunho.tagohub.core.AppState.awaitForeground()
            val got = LinkedHashMap<String, List<RouteEta>>(value)
            kotlinx.coroutines.coroutineScope {
                stops.forEach { (s, _) ->
                    launch {
                        got[s.nodeId] = when (val r = runCatching { com.eunho.tagohub.ui.bus.arrivalsFor(com.eunho.tagohub.data.Graph.repo, s) }.getOrNull()) {
                            is com.eunho.tagohub.core.TagoResult.Success -> HomeText.routeEtas(r.data)
                            else -> got[s.nodeId] ?: emptyList()
                        }
                        value = LinkedHashMap(got)
                    }
                }
            }
            kotlinx.coroutines.delay(45_000L)
        }
    }
    val red = if (com.eunho.tagohub.ui.theme.LocalDarkTheme.current) androidx.compose.ui.graphics.Color(0xFFFF8A80) else androidx.compose.ui.graphics.Color(0xFFD92D20)
    Text("내 주변 정류장", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
    Spacer(Modifier.height(6.dp))
    androidx.compose.material3.Surface(
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column {
            stops.forEachIndexed { i, (s, meters) ->
                if (i > 0) androidx.compose.material3.HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                Column(Modifier.fillMaxWidth().clickable { onStop(s) }.padding(horizontal = 14.dp, vertical = 10.dp)) {
                    Row(verticalAlignment = Alignment.Top) {
                        Text(s.name, Modifier.weight(1f), fontSize = 16.sp, fontWeight = FontWeight.ExtraBold)
                        Text(if (meters >= 1000) "%.1fkm".format(meters / 1000.0) else "${meters.toInt()}m", fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    s.nodeNo?.let { Text(it, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                    val routes = etas[s.nodeId].orEmpty()
                    if (routes.isNotEmpty()) {
                        FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.padding(top = 8.dp)) {
                            routes.forEach { r ->
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text(
                                        r.routeNo,
                                        Modifier.background(com.eunho.tagohub.ui.theme.busRouteColor(r.routeType), RoundedCornerShape(6.dp)).padding(horizontal = 7.dp, vertical = 2.dp),
                                        fontSize = 12.sp, fontWeight = FontWeight.ExtraBold, color = androidx.compose.ui.graphics.Color.White, maxLines = 1,
                                    )
                                    if (r.text != null) AnimatedValueText(" ${r.text}", label = "nearby-stop-arrival") { value ->
                                        Text(value, fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, color = if (r.seconds != null && r.seconds <= 180) red else MaterialTheme.colorScheme.onSurface)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 지하철 탭: 역 검색(초성·자동)과 권역별 노선도(수도권·부산·대구·광주·대전).
 * 노선도는 두 손가락으로 확대·이동, 확대하면 역 이름이 보이고 역을 누르면 지도에서 그 역 정보가 열린다.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SubwayTab(
    vm: MapViewModel,
    search: SearchActions,
    onShowMap: () -> Unit,
) {
    var query by rememberSaveable { mutableStateOf("") }
    var regionName by rememberSaveable { mutableStateOf(SubwayRegion.Capital.name) }
    var highlight by rememberSaveable { mutableStateOf<String?>(null) }
    // 수도권은 노선도 그림(역 이름을 누르면 열림), 나머지 권역은 역 위치로 그린 노선도
    val region = SubwayRegion.valueOf(regionName)
    val officialMap = region == SubwayRegion.Capital
    val stations = remember { vm.metroStations() }
    val lines = remember(stations, region) { SubwayMap.lines(stations, region) }

    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp).padding(top = 12.dp)) {
        if (query.isNotBlank()) {
            // 검색 중에는 결과 목록이 화면을 차지함
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                SearchPanel(vm, SearchScope.Subway, query, { query = it }, search)
            }
            return@Column
        }
        SearchPanel(vm, SearchScope.Subway, query, { query = it }, search)
        Spacer(Modifier.height(10.dp))
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            SubwayRegion.entries.forEach { r ->
                FilterChip(selected = r == region, onClick = { regionName = r.name; highlight = null }, label = { Text(r.label, maxLines = 1) })
            }
        }
        Spacer(Modifier.height(6.dp))
        // 노선 이름 칩: 누르면 그 노선만 진하게 (다시 누르면 전체). 공식 노선도는 그림이라 강조 없음
        if (!officialMap) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach { l ->
                    val on = highlight == l.name
                    Pill(l.name, subwayLineColor(l.name), Modifier.clickable { highlight = if (on) null else l.name }, filled = on || highlight == null)
                }
            }
            Spacer(Modifier.height(8.dp))
        }
        if (officialMap) {
            SubwayImageMap(
                onStation = { name -> vm.stationGroupNamed(name)?.let(search.onStation) },
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            )
        } else if (lines.isEmpty()) {
            Text("이 권역의 노선 자료가 없어요.", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 15.sp)
        } else {
            SubwayLineMap(
                lines, highlight,
                onStation = { s -> vm.groupOfMetro(s)?.let(search.onStation) },
                modifier = Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(16.dp)),
            )
        }
        Text(
            if (officialMap) "노선도 · 두 손가락으로 확대·이동 · 역 이름을 누르면 지도에서 열려요" else "두 손가락으로 확대·이동 · 역을 누르면 지도에서 열려요",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(vertical = 6.dp),
        )
    }
}
