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

/** 도착 정보를 다시 받는 간격 (폰 앱과 같게 15초) */
private const val REFRESH_MS = 15_000L

@OptIn(ExperimentalTime::class)
internal fun nowMs(): Long = Clock.System.now().toEpochMilliseconds()

/**
 * 웹·PC 버전 첫 화면: 정류장 이름으로 찾고, 고르면 실시간 도착 (15초마다 새로, 남은 시간은 매초 줄어듦).
 * 모양은 폰 앱과 같게 ([WebTheme]). [font] 는 한글 글꼴 (웹은 브라우저 글꼴을 못 써서 따로 받음, PC 는 null → 시스템 글꼴).
 */
@Composable
fun App(repo: WebRepo, font: FontFamily? = null) {
    WebTheme(font) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
                Column(Modifier.widthIn(max = 640.dp).fillMaxSize()) {
                    var picked by remember { mutableStateOf<WebStop?>(null) }
                    // 검색어·결과는 정류장 화면에서 돌아와도 그대로
                    val search = remember { SearchState() }
                    val stop = picked
                    if (stop == null) SearchPane(repo, search) { picked = it } else StopPane(repo, stop) { picked = null }
                }
            }
        }
    }
}

/** 앱 화면 위쪽의 남색 머리띠 (제목·부제목, 왼쪽에 뒤로 가기) */
@Composable
private fun Header(title: String, sub: String? = null, onBack: (() -> Unit)? = null) {
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
private fun SearchPane(repo: WebRepo, st: SearchState, onPick: (WebStop) -> Unit) {
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
    Header("TagoHub", "버스 정류장 실시간 도착")
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
                Column(Modifier.fillMaxWidth().clickable { onPick(s) }.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    Text(s.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    var city by remember(s.city) { mutableStateOf<String?>(null) }
                    LaunchedEffect(s.city) { city = repo.cityName(s.city) }
                    Text(
                        listOfNotNull(city, s.no?.let { "정류장 번호 $it" }).joinToString("  ·  "),
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

/** 노선 하나: 가장 빠른 버스와 그다음 버스 */
private data class RouteRow(val routeId: String, val routeNo: String, val routeType: String?, val arrivals: List<BusArrival>)

private fun rows(list: List<BusArrival>): List<RouteRow> =
    list.groupBy { it.routeId.ifEmpty { it.routeNo } }
        .map { (id, l) -> RouteRow(id, l.first().routeNo, l.first().routeType, l.sortedBy { it.arrSeconds ?: Int.MAX_VALUE }) }
        .sortedBy { it.arrivals.firstOrNull()?.arrSeconds ?: Int.MAX_VALUE }

@Composable
private fun StopPane(repo: WebRepo, stop: WebStop, onBack: () -> Unit) {
    var state by remember(stop.id) { mutableStateOf<Arrivals?>(null) }
    var now by remember { mutableLongStateOf(nowMs()) }
    var city by remember(stop.city) { mutableStateOf<String?>(null) }
    LaunchedEffect(stop.city) { city = repo.cityName(stop.city) }
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
    Header(stop.name, listOfNotNull(city, stop.no?.let { "정류장 번호 $it" }).joinToString("  ·  ").ifEmpty { null }, onBack)
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    when (val s = state) {
        null -> Note("도착 정보를 받는 중...")
        Arrivals.Unsupported -> Note("이 지역(서울·경기)의 실시간 도착은 웹·PC 버전에서 아직 볼 수 없어요. 폰 앱에서 볼 수 있어요.")
        is Arrivals.Failed -> Note("도착 정보를 받지 못했어요. 잠시 뒤 다시 시도해 주세요.")
        is Arrivals.Ok -> {
            val elapsed = ((now - s.atMs) / 1000).toInt().coerceAtLeast(0)
            val shown = rows(s.list.filterNot { PassedBus.passed(it, elapsed) })
            if (shown.isEmpty()) Note("지금 도착 예정인 버스가 없어요")
            LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
                items(shown, key = { it.routeId }) { row ->
                    RouteItem(row, elapsed, variant)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun Note(text: String) {
    Text(text, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyMedium)
}

/** 앱 정류장 화면의 한 줄과 같은 모양: 왼쪽 노선 색 칩에 번호, 오른쪽 남은 시간 크게 + 정류장 수, 다음 버스 작게 */
@Composable
private fun RouteItem(row: RouteRow, elapsed: Int, variant: Color) {
    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(
                row.routeNo,
                Modifier.background(busRouteColor(row.routeType), RoundedCornerShape(10.dp)).padding(horizontal = 12.dp, vertical = 4.dp),
                color = Color.White, fontSize = 20.sp, lineHeight = 24.sp, fontWeight = FontWeight.ExtraBold, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            row.routeType?.let {
                Spacer(Modifier.height(4.dp))
                Text(it, style = MaterialTheme.typography.bodyMedium, color = variant)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val first = row.arrivals.firstOrNull() ?: return@Column
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
