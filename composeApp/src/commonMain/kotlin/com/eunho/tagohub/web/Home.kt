package com.eunho.tagohub.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
 * 첫 화면 (폰 앱 홈과 같은 생각): "어디로 가시나요?" 장소 찾기 → 길찾기, 집·회사 바로 길찾기, 최근 목적지,
 * 즐겨찾기 정류장(가장 먼저 오는 버스, 30초마다)·역, 내 주변 정류장, 공지.
 * [onGo] 는 길찾기 (출발 null 이면 내 위치를 받아 보고, 못 받으면 출발을 고르게).
 */
@Composable
internal fun HomePane(
    repo: WebRepo, directions: DirectionsRepo, st: HomeState, modeChips: @Composable () -> Unit,
    notice: NoticeW?, onMap: () -> Unit, onNotices: () -> Unit,
    onGo: (to: WebPlace) -> Unit, onStop: (WebStop) -> Unit, onStation: (SubwayStationW) -> Unit,
) {
    val scope = rememberCoroutineScope()
    Header("TagoHub", "어디로 가시나요?", action = "지도", onAction = onMap)
    modeChips()
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
    androidx.compose.material3.Surface(color = MaterialTheme.colorScheme.surface) {
        OutlinedTextField(
            st.query, { st.query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text("장소·주소·역 이름으로 길찾기") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        )
    }
    LazyColumn(Modifier.fillMaxWidth()) {
        if (st.query.isNotBlank()) {
            if (st.places.isEmpty()) item(key = "pn") { Note(if (st.query.trim().length < 2) "두 글자 이상 입력해 주세요" else "찾는 중...") }
            items(st.places, key = { "p${it.name}|${it.lat}|${it.lng}" }) { p ->
                placeRow(p.name, p.address) { st.query = ""; onGo(p) }
            }
            return@LazyColumn
        }
        item(key = "notice") { NoticeLine(notice, onNotices) }
        // 집·회사
        item(key = "hw") {
            Row(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).padding(horizontal = 16.dp, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                for ((label, p) in listOf("집으로" to HomeWork.home, "회사로" to HomeWork.work)) {
                    Column(
                        Modifier.weight(1f).background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(12.dp))
                            .clickable(enabled = p != null) { p?.let(onGo) }.padding(horizontal = 14.dp, vertical = 10.dp),
                    ) {
                        Text(label, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                        Text(
                            p?.name ?: "길찾기에서 정해 두세요", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
        // 최근 목적지
        if (Recent.places.isNotEmpty()) {
            item(key = "hr") { RecentHead("최근 목적지", null) }
            items(Recent.places.take(5), key = { "r${it.name}|${it.lat}|${it.lng}" }) { p -> placeRow(p.name, p.address) { onGo(p) } }
        }
        // 즐겨찾기
        if (favStops.isNotEmpty() || Favorites.stations.isNotEmpty()) {
            item(key = "hf") { RecentHead("즐겨찾기", null) }
            items(favStops, key = { "f" + it.id }) { s -> favStopRow(s, st.first[s.id]) { onStop(s) } }
            items(Favorites.stations, key = { "fs" + it.id }) { s ->
                Row(
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable { onStation(s) }.padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(s.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        // 내 주변 정류장 (누르면 위치를 한 번 받음)
        item(key = "hn") {
            Row(
                Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("내 주변 정류장", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
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
                    },
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (!locationSupported) item(key = "nn") { Note("PC 버전에서는 위치를 받을 수 없어요") }
        st.nearNote?.let { n -> item(key = "nn2") { Note(n) } }
        items(st.near.orEmpty(), key = { "n" + it.id }) { s -> placeRow(s.name, s.no?.let { "정류장 번호 $it" }) { onStop(s) } }
    }
}

@Composable
private fun placeRow(name: String, sub: String?, onClick: () -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
        sub?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis) }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

/** 즐겨찾기 정류장 한 줄: 이름 + 가장 먼저 오는 버스 */
@Composable
private fun favStopRow(s: WebStop, first: Pair<BusArrival?, Long>?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            s.no?.let { Text("정류장 번호 $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        val a = first?.first
        Text(
            when {
                first == null -> "..."
                a == null -> "도착 정보 없음"
                else -> a.routeNo + "  " + ArrivalText.line(a, ((nowMs() - first.second) / 1000).toInt()).substringBefore(" · ")
            },
            style = MaterialTheme.typography.bodyMedium, fontWeight = if (a != null) FontWeight.Bold else FontWeight.Normal,
            color = if (a != null && (a.arrSeconds ?: 999) <= 180) SoonColor else MaterialTheme.colorScheme.onSurface,
        )
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}
