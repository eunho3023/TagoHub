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
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.eunho.tagohub.data.AirTable
import com.eunho.tagohub.data.TrainTable
import com.eunho.tagohub.data.WebData
import com.eunho.tagohub.data.getText
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus

/** 항공 시간표 자료 (서버에 올린 air_schedule.tsv·공항·항공사 이름, 한 번만) */
class AirRepo(base: String, private val http: HttpClient) {
    private val base = base.trimEnd('/') + "/"
    private val lock = Mutex()
    private var data: Triple<List<AirTable.Row>, Map<String, String>, Map<String, String>>? = null

    /** (운항 행, 공항 코드 → 이름, 항공사 코드 → 이름) */
    suspend fun load(): Triple<List<AirTable.Row>, Map<String, String>, Map<String, String>> = lock.withLock {
        data ?: Triple(
            AirTable.parse(http.getText(base + WebData.filePath("air_schedule.tsv"))),
            AirTable.parseNames(http.getText(base + WebData.filePath("air_airports.tsv"))),
            AirTable.parseNames(http.getText(base + WebData.filePath("air_airlines.tsv"))),
        ).also { data = it }
    }
}

internal class AirState {
    var dep by mutableStateOf("GMP")
    var arr by mutableStateOf("CJU")
    var tomorrow by mutableStateOf(false)
    var editing by mutableStateOf<String?>(null)
}

/**
 * 항공(국내선): 출발·도착 공항을 고르면 그날 운항편이 바로 (서버에 올린 운항 시간표). 파일 기간이 지난 날은 "예상".
 */
@Composable
internal fun AirPane(repo: AirRepo, st: AirState, modeChips: @Composable () -> Unit, onMap: () -> Unit) {
    Header("TagoHub", "국내선 항공 시간표", action = "지도", onAction = onMap)
    modeChips()
    var data by remember { mutableStateOf<Triple<List<AirTable.Row>, Map<String, String>, Map<String, String>>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { data = runCatching { repo.load() }.getOrElse { failed = true; null } }
    val d = data
    if (failed) { Note("시간표를 받지 못했어요. 잠시 뒤 다시 시도해 주세요."); return }
    if (d == null) { Note("시간표를 불러오는 중..."); return }
    val (rows, airports, airlines) = d
    fun name(code: String) = airports[code]?.let { if (it.endsWith("공항")) it else it + "공항" } ?: code
    val date = seoulDate().let { if (st.tomorrow) it.plus(DatePeriod(days = 1)) else it }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaceBox("출발", name(st.dep), st.editing == "dep", Modifier.weight(1f)) { st.editing = "dep" }
                Text(
                    "⇄", Modifier.padding(horizontal = 8.dp).clickable { val t = st.dep; st.dep = st.arr; st.arr = t },
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                )
                PlaceBox("도착", name(st.arr), st.editing == "arr", Modifier.weight(1f)) { st.editing = "arr" }
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
            }
        }
    }
    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    val editing = st.editing
    if (editing != null) {
        val codes = AirTable.domesticAirports(rows)
        LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            items(codes, key = { it }) { c ->
                Row(
                    Modifier.fillMaxWidth().clickable { if (editing == "dep") st.dep = c else st.arr = c; st.editing = null }
                        .padding(horizontal = 16.dp, vertical = 12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(name(c), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    Text(c, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        return
    }
    val r = remember(rows, st.dep, st.arr, date) { AirTable.flights(rows, st.dep, st.arr, date) }
    if (r.flights.isEmpty()) { Note("${name(st.dep)} → ${name(st.arr)} 그날 운항편이 없어요"); return }
    val now = if (st.tomorrow) -1 else nowMinute()
    val state = rememberLazyListState()
    LaunchedEffect(r, st.tomorrow) { state.scrollToItem(r.flights.indexOfFirst { it.depMin >= now }.coerceAtLeast(0)) }
    Text(
        "${r.flights.size}편" + (if (!st.tomorrow) " · 남은 편 ${r.flights.count { it.depMin >= now }}" else "") +
            if (r.projected) " · 시간표 기간이 지나 지난주 운항으로 예상" else "",
        Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), state = state) {
        items(r.flights, key = { it.flight + "|" + it.depMin }) { f ->
            val gone = f.depMin < now
            val main = if (gone) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
            val sub = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (gone) 0.6f else 1f)
            Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("${TrainTable.clock(f.depMin)}  →  ${TrainTable.clock(f.arrMin)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = main)
                    Text(duration(f.arrMin - f.depMin), style = MaterialTheme.typography.bodySmall, color = sub)
                }
                Column(horizontalAlignment = Alignment.End) {
                    Text(airlines[f.airline] ?: f.airline, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = main)
                    Text(f.flight, style = MaterialTheme.typography.bodySmall, color = sub)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}
