package com.eunho.tagohub.web

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.data.TrainTable
import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Clock
import kotlin.time.ExperimentalTime

/** 버스·지하철·기차 고르기 (찾기 화면 위) */
internal enum class Mode(val label: String) { Bus("버스"), Subway("지하철"), Train("기차"), Coach("고속·시외"), Air("항공"), Road("도로") }

@Composable
internal fun ModeChips(current: Mode, onPick: (Mode) -> Unit) {
    Row(
        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface).horizontalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        for (m in Mode.entries) {
            val on = m == current
            Text(
                m.label,
                Modifier.background(if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(50))
                    .clickable { onPick(m) }.padding(horizontal = 16.dp, vertical = 7.dp),
                color = if (on) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                style = MaterialTheme.typography.labelLarge, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
            )
        }
    }
}

@OptIn(ExperimentalTime::class)
internal fun seoulNow() = Clock.System.now().toLocalDateTime(com.eunho.tagohub.data.ArrivalStats.SEOUL)

/** 오늘 날짜 (한국 시각) */
internal fun seoulDate() = seoulNow().date

/** 출발·도착 칸 (기차역·터미널) */
@Composable
internal fun PlaceBox(label: String, name: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) = StationBox(label, name, active, modifier, onClick)

/** 지금 하루 중 분 (한국 시각) */
internal fun nowMinute(): Int = seoulNow().let { it.hour * 60 + it.minute }

/** "2시간 5분" / "48분" */
internal fun duration(min: Int): String = if (min >= 60) "${min / 60}시간" + (if (min % 60 > 0) " ${min % 60}분" else "") else "${min}분"

internal fun won(v: Int): String {
    val s = v.toString()
    return s.reversed().chunked(3).joinToString(",").reversed() + "원"
}

// ── 기차 ─────────────────────────────────────────────

/** 기차 화면 상태 (다른 화면에 다녀와도 남게 App 이 들고 있음) */
internal class TrainState {
    var dep by mutableStateOf("서울")
    var arr by mutableStateOf("부산")
    var tomorrow by mutableStateOf(false)
    /** 지금 고르는 칸: "dep"·"arr"·null */
    var editing by mutableStateOf<String?>(null)
    var query by mutableStateOf("")
}

/**
 * 기차: 출발역·도착역을 고르면 그날 열차가 바로 나옴 (서버에 올린 한국철도공사 시간표).
 * 열차 종류·요금은 국토교통부 조회로 뒤이어 채움. 지난 열차는 흐리게, 다음 열차로 목록을 옮김.
 */
@Composable
internal fun TrainPane(repo: TransitRepo, st: TrainState, modeChips: @Composable () -> Unit, onMap: () -> Unit) {
    Header("TagoHub", "기차 시간표", action = "지도", onAction = onMap)
    modeChips()
    var stations by remember { mutableStateOf<List<TrainStation>>(emptyList()) }
    var trips by remember { mutableStateOf<List<TrainTrip>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { stations = runCatching { repo.trainStations() }.getOrDefault(emptyList()) }
    val date = seoulNow().date.let { if (st.tomorrow) it.plus(DatePeriod(days = 1)) else it }
    val iso = date.dayOfWeek.isoDayNumber
    val ymd = "${date.year}${date.monthNumber.toString().padStart(2, '0')}${date.dayOfMonth.toString().padStart(2, '0')}"
    LaunchedEffect(st.dep, st.arr, ymd) {
        trips = null; failed = false
        val legs = runCatching { repo.trainLegs(st.dep, st.arr, iso) }.getOrElse { failed = true; emptyList() }
        trips = legs
        // 종류·요금 (국토교통부): 역 id 를 알면 (역 목록이 아직 안 왔으면 기다림)
        val stations = runCatching { repo.trainStations() }.getOrDefault(stations)
        val d = stations.firstOrNull { TrainTable.norm(it.name) == TrainTable.norm(st.dep) }
        val a = stations.firstOrNull { TrainTable.norm(it.name) == TrainTable.norm(st.arr) }
        if (d != null && a != null && legs.isNotEmpty()) {
            val info = repo.trainInfo(d.id, a.id, ymd)
            if (info.isNotEmpty()) trips = TransitRepo.withInfo(legs, info)
        }
    }
    // 출발·도착 칸
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                StationBox("출발", st.dep, st.editing == "dep", Modifier.weight(1f)) { st.editing = "dep"; st.query = "" }
                Text(
                    "⇄", Modifier.padding(horizontal = 8.dp).clickable { val t = st.dep; st.dep = st.arr; st.arr = t },
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                )
                StationBox("도착", st.arr, st.editing == "arr", Modifier.weight(1f)) { st.editing = "arr"; st.query = "" }
            }
            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for ((label, t) in listOf("오늘" to false, "내일" to true)) {
                    val on = st.tomorrow == t
                    Text(
                        label, Modifier.background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                            .clickable { st.tomorrow = t }.padding(horizontal = 12.dp, vertical = 5.dp),
                        color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
                Spacer(Modifier.weight(1f))
                Text("${date.monthNumber}월 ${date.dayOfMonth}일 (${"월화수목금토일"[iso - 1]})", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.align(Alignment.CenterVertically))
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val editing = st.editing
    if (editing != null) {
        // 역 고르기
        OutlinedTextField(
            st.query, { st.query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(if (editing == "dep") "출발역 이름" else "도착역 이름") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        )
        val q = TrainTable.norm(st.query)
        val shown = stations.filter { q.isEmpty() || TrainTable.norm(it.name).contains(q) }
            .sortedWith(compareBy({ !TrainTable.norm(it.name).startsWith(q) }, { it.name.length }, { it.name })).take(80)
        LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            items(shown, key = { it.id }) { s ->
                Column(Modifier.fillMaxWidth().clickable {
                    if (editing == "dep") st.dep = s.name else st.arr = s.name
                    st.editing = null
                }.padding(horizontal = 16.dp, vertical = 11.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium)
                    if (s.region.isNotEmpty()) Text(s.region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        return
    }
    val list = trips
    when {
        list == null -> Note("시간표를 불러오는 중...")
        failed -> Note("시간표를 받지 못했어요. 잠시 뒤 다시 시도해 주세요.")
        list.isEmpty() -> Note("${st.dep} → ${st.arr} 그날 열차가 시간표에 없어요")
        else -> {
            val now = if (st.tomorrow) -1 else nowMinute()
            val next = list.indexOfFirst { it.depMin >= now }.coerceAtLeast(0)
            val state = rememberLazyListState()
            LaunchedEffect(list, st.tomorrow) { state.scrollToItem(next) }
            Text(
                "${list.size}편" + if (!st.tomorrow) " · 남은 열차 ${list.count { it.depMin >= now }}편" else "",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), state = state) {
                items(list, key = { it.no + "|" + it.depMin }) { t -> TrainRow(t, gone = t.depMin < now) }
            }
        }
    }
}

@Composable
internal fun StationBox(label: String, name: String, active: Boolean, modifier: Modifier, onClick: () -> Unit) {
    Column(
        modifier.background(if (active) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant, MaterialTheme.shapes.medium)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun TrainRow(t: TrainTrip, gone: Boolean) {
    val main = if (gone) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
    val sub = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (gone) 0.6f else 1f)
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("${TrainTable.clock(t.depMin)}  →  ${TrainTable.clock(t.arrMin)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = main)
            Text(duration(t.arrMin - t.depMin), style = MaterialTheme.typography.bodySmall, color = sub)
        }
        Column(horizontalAlignment = Alignment.End) {
            Text(listOfNotNull(t.kind, t.no).joinToString(" "), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = main)
            t.fare?.let { Text(won(it), style = MaterialTheme.typography.bodySmall, color = sub) }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
}

// ── 지하철 ───────────────────────────────────────────

internal class SubwaySearchState {
    var query by mutableStateOf("")
    var result by mutableStateOf<List<SubwayStationW>>(emptyList())
    var message by mutableStateOf<String?>("역 이름을 입력해 주세요")
    var doneFor: String? = null
}

/** 지하철: 역 이름으로 찾기 (국토교통부) → 고르면 역 시간표 */
@Composable
internal fun SubwayPane(repo: TransitRepo, st: SubwaySearchState, modeChips: @Composable () -> Unit, onMap: () -> Unit, onPick: (SubwayStationW) -> Unit) {
    Header("TagoHub", "지하철 시간표", action = "지도", onAction = onMap)
    modeChips()
    LaunchedEffect(st.query) {
        if (st.doneFor == st.query) return@LaunchedEffect
        st.doneFor = st.query
        val q = st.query.trim()
        if (q.isEmpty()) { st.result = emptyList(); st.message = "역 이름을 입력해 주세요"; return@LaunchedEffect }
        delay(350)
        st.message = "찾는 중..."
        runCatching { repo.subwayStations(q.removeSuffix("역").ifEmpty { q }) }
            .onSuccess { st.result = it; st.message = if (it.isEmpty()) "찾는 역이 없어요" else null }
            .onFailure { st.result = emptyList(); st.message = "역 정보를 받지 못했어요" }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        OutlinedTextField(
            st.query, { st.query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text("지하철역 이름") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        )
    }
    st.message?.let { Note(it) }
    if (st.result.isNotEmpty()) {
        LazyColumn(Modifier.fillMaxWidth().padding(top = 8.dp).background(MaterialTheme.colorScheme.surface)) {
            items(st.result, key = { it.id }) { s ->
                Row(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(s.name, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(s.line, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/**
 * 지하철역 시간표: 오늘 요일(평일·토·일)의 상행·하행을 같이. 다음 열차까지 남은 시간(매초), 그 뒤 열차 몇 대, "전체 시간표"로 하루치.
 * 시간표는 서버가 하루 저장해 둬서 두 번째부터는 바로.
 */
@Composable
internal fun SubwayStationPane(repo: TransitRepo, station: SubwayStationW, seoul: SeoulRealtime? = null, onBack: () -> Unit) {
    val iso = remember { todayIso() }
    var day by remember { mutableStateOf(TransitRepo.dayCode(iso)) }
    var up by remember(station.id, day) { mutableStateOf<List<SubwayDep>?>(null) }
    var down by remember(station.id, day) { mutableStateOf<List<SubwayDep>?>(null) }
    var failed by remember(station.id, day) { mutableStateOf(false) }
    var all by remember { mutableStateOf(false) }
    var nowMs by remember { mutableLongStateOf(nowMs()) }
    LaunchedEffect(station.id, day) {
        runCatching {
            up = repo.subwaySchedule(station.id, day, "U")
            down = repo.subwaySchedule(station.id, day, "D")
        }.onFailure { failed = true }
    }
    LaunchedEffect(Unit) { while (true) { delay(1000); nowMs = nowMs() } }
    // 서울(수도권) 노선이면 실시간 도착 (20초마다 새로)
    val realtimeLine = remember(station.id) { SeoulRealtime.lineIdOf(station) }
    var live by remember(station.id) { mutableStateOf<List<SeoulArrival>?>(null) }
    if (seoul != null && realtimeLine != null) LaunchedEffect(station.id) {
        while (true) {
            live = try { seoul.arrivals(station) } catch (e: kotlinx.coroutines.CancellationException) { throw e } catch (e: Exception) { live ?: emptyList() }
            delay(20_000)
        }
    }
    Header(station.name, station.line, onBack)
    Surface(color = MaterialTheme.colorScheme.surface) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            for ((code, label) in listOf("01" to "평일", "02" to "토요일", "03" to "일·공휴일")) {
                val on = day == code
                Text(
                    label, Modifier.background(if (on) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent, RoundedCornerShape(8.dp))
                        .clickable { day = code }.padding(horizontal = 12.dp, vertical = 5.dp),
                    color = if (on) MaterialTheme.colorScheme.onSecondaryContainer else MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.labelLarge,
                )
            }
            Spacer(Modifier.weight(1f))
            Text(if (all) "다음 열차만" else "전체 시간표", Modifier.clickable { all = !all }.padding(4.dp), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge)
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    if (failed) { Note("시간표를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."); return }
    if (up == null || down == null) { Note("시간표를 불러오는 중..."); return }
    // 지금 (오늘 요일을 볼 때만 "다음 열차"), 새벽 0~2시는 24시 뒤로
    val today = day == TransitRepo.dayCode(iso)
    val nowLocal = seoulNow()
    val nowSec = nowLocal.hour * 3600 + nowLocal.minute * 60 + nowLocal.second
    val serviceNowSec = if (nowLocal.hour < 3) nowSec + 86400 else nowSec
    @Suppress("UNUSED_EXPRESSION") nowMs
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
        if (seoul != null && realtimeLine != null) liveItems(live)
        for ((title, deps) in listOf("상행" to up!!, "하행" to down!!)) {
            item(key = "h$title") {
                Text(
                    title + (deps.firstOrNull()?.end?.let { " · ${deps.groupingBy { d -> d.end }.eachCount().maxByOrNull { e -> e.value }?.key ?: it} 방면" } ?: ""),
                    Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
                    style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            val upcoming = if (today) deps.filter { it.min * 60 >= serviceNowSec - 30 } else deps
            val shown = if (all) deps else upcoming.take(6)
            if (shown.isEmpty()) item(key = "e$title") { Note(if (deps.isEmpty()) "시간표가 없어요" else "오늘 운행이 끝났어요") }
            items(shown, key = { "$title${it.min}${it.end}" }) { d ->
                val left = d.min * 60 - serviceNowSec
                val gone = today && left < -30
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        TrainTable.clock(d.min), Modifier.widthIn(min = 64.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold,
                        color = if (gone) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f) else MaterialTheme.colorScheme.onSurface,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(d.end + (if (d.arrivalOnly) " (종착)" else "") + "행", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    if (today && !gone && left < 3600) {
                        Text(
                            when { left <= 60 -> "곧 도착"; left < 180 -> "${left / 60}분 ${(left % 60).toString().padStart(2, '0')}초"; else -> "${left / 60}분" },
                            style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black,
                            color = if (left <= 180) SoonColor else MaterialTheme.colorScheme.onSurface,
                        )
                    }
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** 실시간 도착 (서울 열린데이터광장): 방향마다 다음 두 대 */
private fun androidx.compose.foundation.lazy.LazyListScope.liveItems(live: List<SeoulArrival>?) {
    item(key = "liveHead") {
        Text(
            "실시간 도착", Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.primaryContainer).padding(horizontal = 16.dp, vertical = 6.dp),
            style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer, fontWeight = FontWeight.Bold,
        )
    }
    when {
        live == null -> item(key = "liveWait") { Note("실시간 도착을 불러오는 중...") }
        live.isEmpty() -> item(key = "liveNone") { Note("지금 실시간 도착 정보가 없어요") }
        else -> {
            val shown = live.groupBy { it.upDown }.values.flatMap { it.take(2) }
            items(shown, key = { "live" + it.upDown + it.order + it.lineText }) { a ->
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(
                            (a.dest?.let { it + "행" } ?: a.lineText) + (if (a.express) " (급행)" else "") + (if (a.last) " · 막차" else ""),
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
                        )
                        Text(a.upDown, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    val soon = a.text in setOf("진입", "도착", "전역 출발", "전역 진입", "전역 도착") || (a.seconds ?: Int.MAX_VALUE) <= 180
                    Text(
                        a.seconds?.takeIf { it >= 60 && !a.text.endsWith("전역") }?.let { "${it / 60}분" } ?: a.text,
                        style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Black,
                        color = if (soon) SoonColor else MaterialTheme.colorScheme.onSurface,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}
