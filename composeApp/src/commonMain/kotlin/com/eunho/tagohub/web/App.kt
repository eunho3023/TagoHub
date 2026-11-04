package com.eunho.tagohub.web

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.eunho.tagohub.data.ArrivalText
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.PassedBus
import kotlinx.coroutines.delay
import kotlin.time.Clock
import kotlin.time.ExperimentalTime
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.toLocalDateTime

/** 도착 정보를 다시 받는 간격 (폰 앱과 같게 15초) */
internal const val REFRESH_MS = 15_000L

@OptIn(ExperimentalTime::class)
internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * 웹·PC 버전 첫 화면: 정류장 이름으로 찾고, 고르면 실시간 도착 (15초마다 새로, 남은 시간은 매초 줄어듦).
 * 모양은 폰 앱과 같게 ([WebTheme]). [font] 는 한글 글꼴 (웹은 브라우저 글꼴을 못 써서 따로 받음, PC 는 null → 시스템 글꼴).
 */
/** 화면 (뒤로 가기 쌓기) */
internal sealed interface Screen {
    data object Search : Screen
    /** 지도 ([focus] 가 있으면 그 정류장 자리를 열고 골라 둠) */
    data class Map(val focus: WebStop? = null) : Screen
    data class Stop(val stop: WebStop) : Screen
    data class Route(val routeId: String, val fromStopId: String?) : Screen
    data class Subway(val station: SubwayStationW) : Screen
}

@Composable
fun App(repo: WebRepo, font: FontFamily? = null, http: io.ktor.client.HttpClient? = null) {
    WebTheme(font) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 720.dp).fillMaxSize()) {
                    val stack = remember { androidx.compose.runtime.mutableStateListOf<Screen>(Screen.Search) }
                    fun go(s: Screen) { stack += s }
                    fun back() { if (stack.size > 1) stack.removeAt(stack.lastIndex) }
                    // 검색어·결과·지도 위치는 다른 화면에 다녀와도 그대로
                    val search = remember { SearchState() }
                    val map = remember { MapState(36.3504, 127.3845, 16) }
                    val tiles = remember { TileLoader(http ?: io.ktor.client.HttpClient()) }
                    val transit = remember { TransitRepo(repo.server, repo.client) }
                    var mode by remember { mutableStateOf(Mode.Bus) }
                    val trainState = remember { TrainState() }
                    val subwayState = remember { SubwaySearchState() }
                    val coachState = remember { CoachState() }
                    val air = remember { AirRepo(repo.server, repo.client) }
                    val airState = remember { AirState() }
                    val chips: @Composable () -> Unit = { ModeChips(mode) { mode = it } }
                    when (val top = stack.last()) {
                        Screen.Search -> when (mode) {
                            Mode.Bus -> SearchPane(repo, search, chips, onMap = { go(Screen.Map()) }, onLocate = { go(Screen.Map(it)) }) { go(Screen.Stop(it)) }
                            Mode.Subway -> SubwayPane(transit, subwayState, chips, onMap = { go(Screen.Map()) }) { go(Screen.Subway(it)) }
                            Mode.Train -> TrainPane(transit, trainState, chips, onMap = { go(Screen.Map()) })
                            Mode.Coach -> CoachPane(transit, coachState, chips, onMap = { go(Screen.Map()) })
                            Mode.Air -> AirPane(air, airState, chips, onMap = { go(Screen.Map()) })
                        }
                        is Screen.Subway -> SubwayStationPane(transit, top.station, onBack = ::back)
                        is Screen.Map -> MapPane(repo, map, tiles, top.focus, onBack = ::back) { go(Screen.Stop(it)) }
                        is Screen.Stop -> StopPane(repo, top.stop, onBack = ::back, onLocate = { go(Screen.Map(top.stop)) }) { go(Screen.Route(it, top.stop.id)) }
                        is Screen.Route -> RoutePane(repo, top.routeId, top.fromStopId, tiles, onBack = ::back) { go(Screen.Stop(it)) }
                    }
                }
            }
        }
    }
}

/** 정류장 모양 (지도의 정류장 점과 같은 동그라미). 누르면 지도에서 위치를 여는 곳에 씀 */
@Composable
internal fun StopMark(modifier: Modifier = Modifier) {
    Box(modifier) {
        Box(
            Modifier.size(22.dp).background(MaterialTheme.colorScheme.primary, androidx.compose.foundation.shape.CircleShape)
                .padding(5.dp).background(Color.White, androidx.compose.foundation.shape.CircleShape),
        )
    }
}

/** 앱 화면 위쪽의 남색 머리띠 (제목·부제목, 왼쪽에 뒤로 가기) */
@Composable
internal fun Header(title: String, sub: String? = null, onBack: (() -> Unit)? = null, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().background(headerColor()).padding(horizontal = 16.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            Text(
                "←", Modifier.clickable(onClick = onBack).padding(end = 14.dp),
                color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold,
            )
        }
        Column(Modifier.weight(1f)) {
            Text(title, color = Color.White, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (sub != null) Text(sub, color = Color.White.copy(alpha = 0.75f), style = MaterialTheme.typography.bodySmall)
        }
        if (action != null) {
            Text(
                action, Modifier.clickable(onClick = onAction).background(Color.White.copy(alpha = 0.15f), RoundedCornerShape(10.dp))
                    .padding(horizontal = 12.dp, vertical = 6.dp),
                color = Color.White, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold,
            )
        }
    }
}

/** 찾기 화면 상태 (정류장 화면에 다녀와도 남게 [App] 이 들고 있음) */
private class SearchState {
    var query by mutableStateOf("")
    var result by mutableStateOf<List<WebStop>>(emptyList())
    var message by mutableStateOf<String?>("정류장 이름을 입력해 주세요")
    /** 결과를 만든 검색어 (돌아왔을 때 다시 찾지 않게) */
    var doneFor: String? = null
}

@Composable
private fun SearchPane(repo: WebRepo, st: SearchState, modeChips: @Composable () -> Unit, onMap: () -> Unit, onLocate: (WebStop) -> Unit, onPick: (WebStop) -> Unit) {
    var query by st::query
    var result by st::result
    var message by st::message
    LaunchedEffect(query) {
        if (st.doneFor == query) return@LaunchedEffect
        st.doneFor = query
        if (query.isBlank()) {
            result = emptyList()
            message = "정류장 이름을 입력해 주세요"
            return@LaunchedEffect
        }
        delay(250)
        message = "찾는 중..."
        runCatching { repo.search(query) }
            .onSuccess { result = it; message = if (it.isEmpty()) "찾는 정류장이 없어요" else null }
            .onFailure { result = emptyList(); message = "정류장 자료를 받지 못했어요" }
    }
    Header("TagoHub", "버스 정류장 실시간 도착", action = "지도", onAction = onMap)
    modeChips()
    Surface(color = MaterialTheme.colorScheme.surface) {
        OutlinedTextField(
            query, { query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            placeholder = { Text("정류장 이름") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        )
    }
    message?.let {
        Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
    }
    if (result.isNotEmpty()) {
        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.surface)) {
            items(result, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(start = 6.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    // 정류장 모양: 누르면 지도에서 위치
                    StopMark(Modifier.clickable { onLocate(s) }.padding(10.dp))
                    Column(Modifier.weight(1f).padding(vertical = 12.dp)) {
                        Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        var city by remember(s.city) { mutableStateOf<String?>(null) }
                        LaunchedEffect(s.city) { city = repo.cityName(s.city) }
                        Text(
                            listOfNotNull(city, s.no?.let { "정류장 번호 $it" }).joinToString("  ·  "),
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** 노선 하나: 서버의 노선 요약(바로 나옴) + 실시간 도착(가장 빠른 버스와 그다음 버스) */
internal data class RouteRow(
    val routeId: String, val routeNo: String, val routeType: String?, val arrivals: List<BusArrival>, val sum: RouteSum? = null,
)

/**
 * 노선 요약과 실시간 도착을 합친 줄. 실시간이 있는 노선 먼저(빨리 오는 순), 없는 노선은 번호순.
 * 실시간에만 있는 노선(서버 자료에 아직 없는 새 노선)도 넣음.
 */
internal fun mergeRows(sums: List<RouteSum>, arrivals: List<BusArrival>): List<RouteRow> {
    val byId = arrivals.groupBy { it.routeId.ifEmpty { it.routeNo } }
    val fromSums = sums.map { s ->
        val a = (byId[s.id] ?: arrivals.filter { it.routeId.isEmpty() && it.routeNo == s.no }).sortedBy { it.arrSeconds ?: Int.MAX_VALUE }
        RouteRow(s.id, s.no, s.type, a, s)
    }
    val known = sums.map { it.id }.toSet()
    val extra = byId.filterKeys { it !in known && fromSums.none { r -> r.arrivals.any { a -> a.routeId == it } } }
        .map { (id, l) -> RouteRow(id, l.first().routeNo, l.first().routeType, l.sortedBy { it.arrSeconds ?: Int.MAX_VALUE }, null) }
    return (fromSums + extra).sortedWith(
        compareBy<RouteRow>({ it.arrivals.isEmpty() }, { it.arrivals.firstOrNull()?.arrSeconds ?: Int.MAX_VALUE })
            .thenComparator { x, y -> naturalCompare(x.routeNo, y.routeNo) },
    )
}

/** "10" < "100", "2" < "10" 처럼 숫자는 크기로 */
internal fun naturalCompare(a: String, b: String): Int {
    val na = a.takeWhile { it.isDigit() }.toIntOrNull()
    val nb = b.takeWhile { it.isDigit() }.toIntOrNull()
    if (na != null && nb != null && na != nb) return na.compareTo(nb)
    if (na != null && nb == null) return -1
    if (na == null && nb != null) return 1
    return a.compareTo(b)
}

@OptIn(ExperimentalTime::class)
internal fun todayIso(): Int = Clock.System.now().toLocalDateTime(com.eunho.tagohub.data.ArrivalStats.SEOUL).dayOfWeek.isoDayNumber

@Composable
internal fun StopPane(repo: WebRepo, stop: WebStop, onBack: () -> Unit, onLocate: () -> Unit, onRoute: (String) -> Unit) {
    var state by remember(stop.id) { mutableStateOf<Arrivals?>(null) }
    var sums by remember(stop.id) { mutableStateOf<List<RouteSum>?>(null) }
    var now by remember { mutableLongStateOf(nowMs()) }
    var city by remember(stop.city) { mutableStateOf<String?>(null) }
    LaunchedEffect(stop.city) { city = repo.cityName(stop.city) }
    // 서버에 저장한 노선·시간표를 먼저 (작은 파일이라 바로 나옴), 실시간은 그 위에 채움
    LaunchedEffect(stop.id) { sums = runCatching { repo.stopRoutes(stop) }.getOrDefault(emptyList()) }
    LaunchedEffect(stop.id) {
        while (true) {
            state = repo.arrivals(stop, nowMs())
            delay(REFRESH_MS)
        }
    }
    LaunchedEffect(Unit) {
        while (true) {
            delay(1000)
            now = nowMs()
        }
    }
    Header(stop.name, listOfNotNull(city, stop.no?.let { "정류장 번호 $it" }).joinToString("  ·  ").ifEmpty { null }, onBack, action = "위치", onAction = onLocate)
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val s = state
    val live = (s as? Arrivals.Ok)
    val elapsed = live?.let { ((now - it.atMs) / 1000).toInt().coerceAtLeast(0) } ?: 0
    val arrivals = live?.list?.filterNot { PassedBus.passed(it, elapsed) }.orEmpty()
    val rows = mergeRows(sums.orEmpty(), arrivals)
    val day = remember { todayIso() }
    // 실시간 상태 한 줄 (노선 목록은 그대로 보임)
    when {
        sums == null && s == null -> Note("불러오는 중...")
        s == null -> Note("실시간 도착을 받는 중...")
        s == Arrivals.Unsupported -> Note("이 지역(서울·경기)의 실시간 도착은 웹·PC 버전에서 아직 볼 수 없어요. 폰 앱에서 볼 수 있어요.")
        s is Arrivals.Failed -> Note("실시간 도착을 받지 못했어요. 잠시 뒤 다시 시도해 주세요.")
        rows.isEmpty() -> Note("이 정류장에 서는 버스 정보가 없어요")
        arrivals.isEmpty() -> Note("지금 도착 예정인 버스가 없어요")
    }
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        items(rows, key = { it.routeId }) { row ->
            RouteItem(row, elapsed, variant, day, liveKnown = live != null, onClick = { onRoute(row.routeId) })
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
internal fun Note(text: String) {
    Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

/**
 * 앱 정류장 화면의 한 줄과 같은 모양: 왼쪽 노선 색 칩에 번호 + 기점 → 종점, 오른쪽 남은 시간 크게 + 정류장 수, 다음 버스 작게.
 * 실시간이 없으면 서버에 저장한 시간표(첫차·막차·배차)를 보여 줌.
 */
@Composable
private fun RouteItem(row: RouteRow, elapsed: Int, variant: Color, isoDay: Int, liveKnown: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                row.routeNo,
                Modifier.background(busRouteColor(row.routeType), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                color = Color.White, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            val ends = listOfNotNull(row.sum?.start, row.sum?.end).joinToString(" → ")
            val sub = ends.ifEmpty { row.routeType.orEmpty() }
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text(sub, style = MaterialTheme.typography.bodyMedium, color = variant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val first = row.arrivals.firstOrNull()
            if (first == null) {
                Text(if (liveKnown) "도착 정보 없음" else "", style = MaterialTheme.typography.bodyLarge, color = variant, textAlign = TextAlign.End)
                val t = row.sum
                val plan = listOfNotNull(
                    t?.first?.let { "첫차 $it" }, t?.last?.let { "막차 $it" }, t?.intervalOn(isoDay)?.takeIf { it > 0 }?.let { "배차 ${it}분" },
                ).joinToString(" · ")
                if (plan.isNotEmpty()) Text(plan, style = MaterialTheme.typography.bodySmall, color = variant, textAlign = TextAlign.End)
                return@Column
            }
            val parts = ArrivalText.line(first, elapsed).split(" · ")
            val soon = (first.arrSeconds ?: 0) - elapsed <= 180
            Text(
                parts.first(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Black,
                color = if (soon) SoonColor else MaterialTheme.colorScheme.onSurface, textAlign = TextAlign.End,
            )
            if (parts.size > 1) Text(parts.drop(1).joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = variant, textAlign = TextAlign.End)
            row.arrivals.getOrNull(1)?.let { second ->
                Text("다음 " + ArrivalText.line(second, elapsed), style = MaterialTheme.typography.bodySmall, color = variant, textAlign = TextAlign.End)
            }
        }
    }
}
