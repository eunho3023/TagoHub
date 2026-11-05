package com.eunho.tagohub.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Surface
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.data.ArrivalText
import com.eunho.tagohub.data.BusArrival
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/** 지금 위치 한 번 (못 받으면 null, 15초까지 기다림) */
suspend fun currentLocation(timeoutMs: Long = 15_000): Pair<Double, Double>? = withTimeoutOrNull(timeoutMs) {
    suspendCancellableCoroutine { cont ->
        var id = -1
        id = watchLocation(
            onPos = { la, lo, _ -> if (cont.isActive) { if (id >= 0) stopLocation(id); cont.resume(la to lo) } },
            onError = { if (cont.isActive) cont.resume(null) },
        )
        cont.invokeOnCancellation { if (id >= 0) stopLocation(id) }
    }
}

/** 홈 화면 상태 (다른 화면에 다녀와도 남게 [App] 이 들고 있음) */
internal class HomeState {
    var query by mutableStateOf("")
    var places by mutableStateOf<List<WebPlace>>(emptyList())
    var near by mutableStateOf<List<WebStop>?>(null)
    var nearNote by mutableStateOf<String?>(null)
    /** 즐겨찾기 정류장 id → 가장 먼저 오는 버스 (받은 시각 ms) */
    val first = mutableStateMapOf<String, Pair<BusArrival?, Long>>()
}


/**
 * 첫 화면 (폰 앱 홈과 같은 모양): 위 흰 카드에 로고·"어디로 가시나요?" 장소 찾기(누르면 길찾기),
 * 아래에 바로가기 네 칸(집·회사·즐겨찾기·최근), 최근 검색, 즐겨찾기(정류장은 가장 먼저 오는 버스, 30초마다), 내 주변 정류장, 공지.
 * [onGo] 는 길찾기 (출발은 내 위치를 받아 보고, 못 받으면 고르게). [onNavi] 는 집·회사를 아직 안 정했을 때 길찾기 탭.
 */
@Composable
internal fun HomePane(
    repo: WebRepo, directions: DirectionsRepo, st: HomeState, modeChips: @Composable () -> Unit,
    notice: NoticeW?, onMap: () -> Unit, onNotices: () -> Unit,
    onGo: (to: WebPlace) -> Unit, onStop: (WebStop) -> Unit, onStation: (SubwayStationW) -> Unit,
    onNavi: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val list = rememberLazyListState()
    LaunchedEffect(st.query) {
        val q = st.query.trim()
        if (q.length < 2) { st.places = emptyList(); return@LaunchedEffect }
        delay(400)
        st.places = try { directions.places(q) } catch (e: CancellationException) { throw e } catch (e: Exception) { emptyList() }
    }
    // 즐겨찾기 정류장 앞 4곳: 가장 먼저 오는 버스 (30초마다)
    val favStops = Favorites.stops.take(4)
    LaunchedEffect(favStops.map { it.id }) {
        while (true) {
            for (s in favStops) scope.launch {
                val a = repo.arrivals(s, nowMs())
                if (a is Arrivals.Ok) st.first[s.id] = a.list.firstOrNull() to a.atMs
                else if (a == Arrivals.Unsupported) st.first[s.id] = null to nowMs()
            }
            delay(30_000)
        }
    }
    // 위 카드: 로고 + 검색 (아래쪽 모서리 둥글게)
    Surface(
        color = MaterialTheme.colorScheme.surface, shape = RoundedCornerShape(bottomStart = 24.dp, bottomEnd = 24.dp), shadowElevation = 6.dp,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 14.dp, bottom = 14.dp)) {
            BrandHeader(action = if (isWide()) null else "지도", onAction = onMap)
            Spacer(Modifier.height(12.dp))
            OutlinedTextField(
                st.query, { st.query = it }, Modifier.fillMaxWidth(),
                placeholder = { Text("어디로 가시나요?") }, singleLine = true, shape = RoundedCornerShape(16.dp),
                leadingIcon = { GlyphIcon(Glyph.Pin, MaterialTheme.colorScheme.onSurfaceVariant, 20.dp) },
                colors = OutlinedTextFieldDefaults.colors(
                    unfocusedBorderColor = Color.Transparent, focusedBorderColor = MaterialTheme.colorScheme.primary,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surfaceVariant, focusedContainerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        }
    }
    LazyColumn(Modifier.fillMaxWidth(), state = list) {
        if (st.query.isNotBlank()) {
            item(key = "pn") { Spacer(Modifier.height(8.dp)) }
            if (st.places.isEmpty()) item(key = "pw") { Note(if (st.query.trim().length < 2) "두 글자 이상 입력해 주세요" else "찾는 중...") }
            items(st.places, key = { "p${it.name}|${it.lat}|${it.lng}" }) { p ->
                ListRow(p.name, p.address, Glyph.Pin) { st.query = ""; onGo(p) }
            }
            return@LazyColumn
        }
        // 바로가기 네 칸: 집 · 회사(정해 둔 곳으로 바로 길찾기) · 즐겨찾기 · 최근(아래 목록으로)
        item(key = "tiles") {
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val favIndex = 2 + (if (Recent.places.isNotEmpty()) 1 else 0)
                val tiles = listOf(
                    Triple("집", Glyph.Home) { HomeWork.home?.let(onGo) ?: onNavi() },
                    Triple("회사", Glyph.Work) { HomeWork.work?.let(onGo) ?: onNavi() },
                    Triple("즐겨찾기", Glyph.Star) { scope.launch { list.animateScrollToItem(favIndex) } },
                    Triple("최근", Glyph.Clock) { scope.launch { list.animateScrollToItem(1) } },
                )
                for ((label, glyph, click) in tiles) {
                    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(
                            Modifier.fillMaxWidth().height(56.dp).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(16.dp))
                                .clickable { click() },
                            contentAlignment = Alignment.Center,
                        ) { GlyphIcon(glyph, MaterialTheme.colorScheme.secondary, 26.dp) }
                        Text(label, Modifier.padding(top = 6.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                    }
                }
            }
        }
        // 최근 검색 (눌러서 바로 길찾기)
        if (Recent.places.isNotEmpty()) item(key = "recent") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp)) {
                SectionTitle("최근 검색")
                Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (p in Recent.places.take(6)) {
                        Text(
                            p.name,
                            Modifier.background(MaterialTheme.colorScheme.surface, RoundedCornerShape(50)).border1(MaterialTheme.colorScheme.outlineVariant, 50.dp)
                                .clickable { onGo(p) }.padding(horizontal = 14.dp, vertical = 7.dp),
                            fontSize = 13.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
                        )
                    }
                }
            }
        }
        // 즐겨찾기: 흰 카드 하나에 줄로, 정류장은 가장 먼저 오는 버스 (도착 시간은 빨간 글씨)
        item(key = "fav") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp)) {
                SectionTitle("즐겨찾기")
                if (favStops.isEmpty() && Favorites.stations.isEmpty()) {
                    Text("정류장이나 역 화면의 ☆ 를 누르면 여기에 모여요.", Modifier.padding(vertical = 8.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                } else Card {
                    favStops.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        val got = st.first[s.id]
                        val a = got?.first
                        BadgeRow(
                            badge = a?.routeNo, badgeColor = busRouteColor(a?.routeType), title = s.name,
                            right = when {
                                got == null -> "..."
                                a == null -> "도착 정보 없음"
                                else -> ArrivalText.line(a, ((nowMs() - got.second) / 1000).toInt()).substringBefore(" · ")
                            },
                            soon = a != null && (a.arrSeconds ?: 999) <= 180,
                        ) { onStop(s) }
                    }
                    Favorites.stations.forEachIndexed { i, s ->
                        if (i > 0 || favStops.isNotEmpty()) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        BadgeRow(badge = null, badgeColor = Color(0xFF4B5563), title = s.name, right = s.line, soon = false) { onStation(s) }
                    }
                }
            }
        }
        // 내 주변 정류장 (누르면 위치를 한 번 받음)
        item(key = "near") {
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(top = 18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionTitle("내 주변 정류장", Modifier.weight(1f))
                    if (locationSupported) Text(
                        if (st.near == null) "찾기" else "다시 찾기",
                        Modifier.clickable {
                            st.nearNote = "위치를 받는 중..."
                            scope.launch {
                                val here = currentLocation()
                                if (here == null) { st.nearNote = "위치를 받지 못했어요 (위치 사용을 허락해 주세요)"; return@launch }
                                val (la, lo) = here
                                val got = runCatching { repo.stopsIn(la - 0.004, lo - 0.005, la + 0.004, lo + 0.005) }.getOrDefault(emptyList())
                                    .sortedBy { WebRepo.meters(la, lo, it.lat, it.lng) }.take(6)
                                st.near = got
                                st.nearNote = if (got.isEmpty()) "근처 400m 안에 정류장이 없어요" else null
                            }
                        }.padding(vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary,
                    )
                }
                val note = if (!locationSupported) "PC 버전에서는 위치를 받을 수 없어요" else st.nearNote ?: if (st.near == null) "\"찾기\"를 누르면 지금 있는 곳 근처 정류장이 보여요" else null
                note?.let { Text(it, Modifier.padding(vertical = 6.dp), fontSize = 14.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                val near = st.near.orEmpty()
                if (near.isNotEmpty()) Card {
                    near.forEachIndexed { i, s ->
                        if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        BadgeRow(badge = null, badgeColor = MaterialTheme.colorScheme.primary, title = s.name, right = s.no?.let { "정류장 번호 $it" }, soon = false) { onStop(s) }
                    }
                }
            }
        }
        // 공지
        item(key = "notice") {
            Column(Modifier.fillMaxWidth().padding(16.dp)) {
                Card { NoticeLine(notice, onNotices) }
            }
        }
    }
}

/** 구역 제목 (굵게) */
@Composable
private fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text, modifier, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
}

/** 흰 카드 (테두리, 둥근 모서리) */
@Composable
private fun Card(content: @Composable () -> Unit) {
    Surface(
        shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.surface,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
    ) { Column { content() } }
}

/** 카드 안 한 줄: 왼쪽 색 배지(노선 번호, 없으면 아이콘) + 이름 + 오른쪽 글자 */
@Composable
private fun BadgeRow(badge: String?, badgeColor: Color, title: String, right: String?, soon: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().heightIn(min = 52.dp).clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier.heightIn(min = 28.dp).widthIn(min = 34.dp).background(badgeColor, RoundedCornerShape(8.dp)).padding(horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            if (badge != null) Text(badge, fontSize = 14.sp, fontWeight = FontWeight.ExtraBold, color = Color.White, maxLines = 1)
            else GlyphIcon(Glyph.Bus, Color.White, 16.dp)
        }
        Spacer(Modifier.width(12.dp))
        Text(title, Modifier.weight(1f), fontSize = 15.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
        right?.let {
            Text(
                it, Modifier.padding(start = 8.dp), fontSize = 13.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1,
                color = if (soon) SoonColor else MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** 목록 한 줄 (아이콘 + 이름 + 아래 작은 글자) */
@Composable
private fun ListRow(name: String, sub: String?, glyph: Glyph, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        GlyphIcon(glyph, MaterialTheme.colorScheme.onSurfaceVariant, 20.dp)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
