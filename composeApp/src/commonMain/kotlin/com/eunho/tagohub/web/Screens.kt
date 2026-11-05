package com.eunho.tagohub.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.data.TagoIds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** 지도에서 정류장이 보이기 시작하는 확대 (더 넓으면 너무 많아서 받지 않음) */
private const val STOPS_ZOOM = 15

/** 노선 화면에서 버스가 어느 정류장(순번)에 있는지: 정류장 id 가 맞으면 그것, 아니면 순번 */
internal fun busAtStops(stops: List<RouteStopW>, buses: List<BusLocation>): Map<Int, List<BusLocation>> {
    val bySeq = HashMap<Int, MutableList<BusLocation>>()
    for (b in buses) {
        val idx = b.nodeId?.let { n -> stops.indexOfFirst { it.id != null && TagoIds.current(it.id) == TagoIds.current(n) } }?.takeIf { it >= 0 }
            ?: b.ord?.let { o -> stops.indexOfFirst { it.seq == o } }?.takeIf { it >= 0 }
            ?: continue
        bySeq.getOrPut(idx) { ArrayList() } += b
    }
    return bySeq
}

/** 번호판 끝 네 자리 (지도 이름표) */
private fun plateTail(no: String?) = no?.filter(Char::isDigit)?.takeLast(4)

/**
 * 노선 화면: 위에 지도(노선 경로·정류장·실시간 버스), 아래에 정류장 목록(버스가 있는 정류장 표시, 회차 지점 구분).
 * 버스 위치는 15초마다 새로. 정류장을 누르면 그 정류장 도착 화면.
 */
@Composable
internal fun RoutePane(repo: WebRepo, routeId: String, fromStopId: String?, tiles: TileLoader, onBack: () -> Unit, onStop: (WebStop) -> Unit) {
    var route by remember(routeId) { mutableStateOf<RouteFull?>(null) }
    var failed by remember(routeId) { mutableStateOf(false) }
    var pos by remember(routeId) { mutableStateOf<Positions?>(null) }
    // 길 따라 그린 모양 (서버 저장). 받기 전에는 정류장을 잇는 선
    var road by remember(routeId) { mutableStateOf<List<Pair<Double, Double>>?>(null) }
    LaunchedEffect(routeId) { road = repo.shape(routeId) }
    val map = remember(routeId) { MapState(36.35, 127.38, 13) }
    val scope = rememberCoroutineScope()
    LaunchedEffect(routeId) {
        route = runCatching { repo.route(routeId) }.getOrNull()
        failed = route == null
    }
    LaunchedEffect(route) {
        val r = route ?: return@LaunchedEffect
        while (true) {
            pos = repo.positions(r)
            delay(REFRESH_MS)
        }
    }
    val r = route
    val color = busRouteColor(r?.type)
    Header(
        r?.no ?: "노선",
        r?.let { listOfNotNull(listOfNotNull(it.start, it.end).joinToString(" → ").ifEmpty { null }, it.region, it.type).joinToString("  ·  ") },
        onBack,
    )
    if (r == null) {
        Note(if (failed) "노선 정보를 받지 못했어요" else "불러오는 중...")
        return
    }
    val pts = r.stops.mapNotNull { s -> s.lat?.let { la -> s.lng?.let { lo -> la to lo } } }
    // 처음 한 번: 노선 전체가 보이게 (지도 크기를 안 뒤에)
    var fitted by remember(routeId) { mutableStateOf(false) }
    LaunchedEffect(map.size, pts) {
        if (!fitted && map.size.width > 0 && pts.isNotEmpty()) {
            map.fit(pts); fitted = true
        }
    }
    val buses = (pos as? Positions.Ok)?.buses.orEmpty()
    val at = busAtStops(r.stops, buses)
    // 목록에서 점을 눌러 지도에 보여 주는 정류장
    var focusSeq by remember(routeId) { mutableStateOf<Int?>(null) }
    val dots = buildList {
        r.stops.forEach { s ->
            if (s.lat != null && s.lng != null && map.zoom >= 14) {
                val here = s.id != null && fromStopId != null && TagoIds.legacy(s.id) == TagoIds.legacy(fromStopId)
                val focused = focusSeq == s.seq
                add(MapDot("s:${s.seq}", s.lat, s.lng, if (here || focused) SoonColor else Color.White, if (here || focused) 7f else 4f, ring = color,
                    label = s.name.takeIf { focused }))
            }
        }
        buses.forEach { b ->
            if (b.lat != null && b.lng != null) add(MapDot("b:${b.vehicleNo}", b.lat!!, b.lng!!, color, 8f, label = plateTail(b.vehicleNo)))
        }
    }
    val list = rememberLazyListState()
    MapHost(
        map, tiles, Modifier.fillMaxWidth().fillMaxHeight(0.42f),
        lines = listOf(MapLine(road ?: pts, color)), dots = dots,
        onDot = { d ->
            val seq = d.id.removePrefix("s:").toIntOrNull()
            val idx = if (d.id.startsWith("s:")) r.stops.indexOfFirst { it.seq == seq } else at.entries.firstOrNull { e -> e.value.any { "b:${it.vehicleNo}" == d.id } }?.key
            if (idx != null && idx >= 0) scope.launch { list.animateScrollToItem(idx) }
        },
    )
    val status = when (val p = pos) {
        null -> "버스 위치를 받는 중..."
        Positions.Unsupported -> "이 노선의 실시간 버스 위치는 웹·PC 버전에서 아직 볼 수 없어요"
        is Positions.Failed -> "버스 위치를 받지 못했어요"
        is Positions.Ok -> if (p.buses.isEmpty()) "지금 운행 중인 버스가 없어요" else "운행 중 ${p.buses.size}대 · 15초마다 새로"
    }
    val plan = r.sum?.let { s ->
        listOfNotNull(s.first?.let { "첫차 $it" }, s.last?.let { "막차 $it" }, s.intervalOn(todayIso())?.takeIf { it > 0 }?.let { "배차 ${it}분" }).joinToString(" · ")
    }.orEmpty()
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(status, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurface)
        if (plan.isNotEmpty()) Text(plan, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), state = list) {
        itemsIndexed(r.stops, key = { i, s -> "$i:${s.seq}" }) { i, s ->
            if (i > 0 && s.upDown != null && r.stops[i - 1].upDown != null && s.upDown != r.stops[i - 1].upDown) {
                Text(
                    "회차", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 4.dp),
                    style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val here = s.id != null && fromStopId != null && TagoIds.legacy(s.id) == TagoIds.legacy(fromStopId)
            val busHere = at[i].orEmpty()
            Row(
                Modifier.fillMaxWidth().clickable(enabled = s.id != null && s.lat != null) {
                    val id = s.id ?: return@clickable
                    val la = s.lat ?: return@clickable
                    val lo = s.lng ?: return@clickable
                    scope.launch { repo.stopAt(id, la, lo)?.let(onStop) }
                }.padding(horizontal = 16.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // 노선 줄 + 정류장 점 (버스가 있으면 노선 색으로 채움). 점을 누르면 위 지도에서 그 정류장 자리로
                Box(
                    Modifier.width(28.dp).height(28.dp).clickable(enabled = s.lat != null && s.lng != null) {
                        val la = s.lat ?: return@clickable
                        val lo = s.lng ?: return@clickable
                        focusSeq = s.seq
                        map.moveTo(la, lo, 17)
                    },
                    contentAlignment = Alignment.Center,
                ) {
                    Box(
                        Modifier.size(if (busHere.isNotEmpty()) 16.dp else 10.dp)
                            .background(if (busHere.isNotEmpty()) color else Color.White, CircleShape)
                            .padding(2.dp).background(if (busHere.isNotEmpty()) color else color.copy(alpha = 0.35f), CircleShape),
                    )
                }
                Spacer(Modifier.width(8.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        s.name, style = MaterialTheme.typography.bodyLarge,
                        fontWeight = if (here) FontWeight.Bold else FontWeight.Normal,
                        color = if (here) SoonColor else MaterialTheme.colorScheme.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
                if (busHere.isNotEmpty()) {
                    Text(
                        busHere.joinToString(" ") { plateTail(it.vehicleNo) ?: "버스" },
                        Modifier.background(color, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                        color = Color.White, fontSize = 12.sp, fontWeight = FontWeight.Bold,
                    )
                }
                // 하차 알림: 이 정류장에 다가가면 소리·진동으로 알려 줌 (웹 버전, 위치를 받을 수 있을 때)
                val la = s.lat
                val lo = s.lng
                if (locationSupported && la != null && lo != null) {
                    val on = AlightAlert.isTarget(s.name, la, lo)
                    Text(
                        if (on) "알림 끄기" else "내릴 곳",
                        Modifier.padding(start = 8.dp).clickable { if (on) AlightAlert.stop() else AlightAlert.start(s.name, la, lo) }
                            .padding(horizontal = 4.dp, vertical = 4.dp),
                        color = if (on) SoonColor else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium,
                    )
                }
            }
        }
    }
}

/**
 * 지도 화면: 끌어서 옮기고 확대하면([STOPS_ZOOM] 이상) 보이는 곳의 정류장이 나옴. 정류장을 누르면 아래 카드 → 도착 정보.
 */
@Composable
internal fun MapPane(repo: WebRepo, map: MapState, tiles: TileLoader, focus: WebStop?, onBack: () -> Unit, onStop: (WebStop) -> Unit) {
    var stops by remember { mutableStateOf<List<WebStop>>(emptyList()) }
    var picked by remember { mutableStateOf(focus) }
    // 정류장 모양을 눌러 왔으면 그 정류장 자리로
    LaunchedEffect(focus) { focus?.let { map.moveTo(it.lat, it.lng, 17); picked = it } }
    var city by remember { mutableStateOf<String?>(null) }
    // 지도를 옮기거나 확대하면 잠깐 기다렸다가 보이는 곳의 정류장을 받음
    val key = Triple((map.lat * 2000).toInt(), (map.lng * 2000).toInt(), map.zoom)
    LaunchedEffect(key, map.size) {
        if (map.zoom < STOPS_ZOOM || map.size.width == 0) { stops = emptyList(); return@LaunchedEffect }
        delay(300)
        val b = map.bounds()
        stops = runCatching { repo.stopsIn(b[0], b[1], b[2], b[3]) }.getOrDefault(stops)
    }
    LaunchedEffect(picked) { city = picked?.let { repo.cityName(it.city) } }
    Header("지도", if (map.zoom < STOPS_ZOOM) "확대하면 정류장이 보여요" else "정류장 ${stops.size}곳", onBack)
    // 카드는 지도 아래에 (웹 카카오 지도는 위에 겹쳐 그릴 수 없음)
    Column(Modifier.fillMaxSize()) {
        val primary = MaterialTheme.colorScheme.primary
        MapHost(
            map, tiles, Modifier.fillMaxWidth().weight(1f),
            dots = (stops.filterNot { s -> picked?.let { WebRepo.same(it, s) } == true } + listOfNotNull(picked))
                .map { s -> MapDot(s.id, s.lat, s.lng, if (picked?.id == s.id) SoonColor else primary, if (picked?.id == s.id) 8f else 5f) },
            onDot = { d -> picked = stops.firstOrNull { it.id == d.id } ?: picked?.takeIf { it.id == d.id } },
            onTap = { picked = null },
        )
        val p = picked
        if (p != null) {
            Surface(
                Modifier.fillMaxWidth().clickable { onStop(p) },
                color = MaterialTheme.colorScheme.surface, shadowElevation = 6.dp,
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(p.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            listOfNotNull(city, p.no?.let { "정류장 번호 $it" }, "노선 ${p.routes.size}개".takeIf { p.routes.isNotEmpty() }).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Text(
                        "도착 정보", Modifier.background(primary, RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 8.dp),
                        color = MaterialTheme.colorScheme.onPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
                    )
                }
            }
        }
    }
}
