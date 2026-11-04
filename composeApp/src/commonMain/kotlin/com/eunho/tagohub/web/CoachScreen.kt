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
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import com.eunho.tagohub.data.TrainTable
import kotlinx.coroutines.delay
import kotlinx.datetime.DatePeriod
import kotlinx.datetime.plus

internal class CoachState {
    var dep by mutableStateOf<CoachTerminal?>(null)
    var arr by mutableStateOf<CoachTerminal?>(null)
    var tomorrow by mutableStateOf(false)
    var editing by mutableStateOf<String?>("dep")
    var query by mutableStateOf("")
}

/**
 * 고속·시외버스: 출발·도착 터미널을 고르면 그날 버스 (국토교통부, 서버가 30분 저장). 고속은 고속끼리, 시외는 시외끼리.
 * 터미널은 서버에 올린 목록에서 바로 찾고, 두 글자 넘게 치면 국토교통부 시외버스 터미널 찾기도 더함.
 */
@Composable
internal fun CoachPane(repo: TransitRepo, st: CoachState, modeChips: @Composable () -> Unit, onMap: () -> Unit) {
    Header("TagoHub", "고속·시외버스 시간표", action = "지도", onAction = onMap)
    modeChips()
    var terminals by remember { mutableStateOf<List<CoachTerminal>>(emptyList()) }
    var extra by remember { mutableStateOf<List<CoachTerminal>>(emptyList()) }
    var trips by remember { mutableStateOf<List<CoachTrip>?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { terminals = runCatching { repo.coachTerminals() }.getOrDefault(emptyList()) }
    val date = seoulDate().let { if (st.tomorrow) it.plus(DatePeriod(days = 1)) else it }
    val ymd = "${date.year}${date.monthNumber.toString().padStart(2, '0')}${date.dayOfMonth.toString().padStart(2, '0')}"
    val dep = st.dep
    val arr = st.arr
    LaunchedEffect(dep, arr, ymd) {
        trips = null
        failed = false
        if (dep == null || arr == null) return@LaunchedEffect
        trips = runCatching { repo.coachTrips(dep, arr, ymd) }.getOrElse { failed = true; emptyList() }
    }
    Surface(color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PlaceBox("출발", dep?.name ?: "터미널 고르기", st.editing == "dep", Modifier.weight(1f)) { st.editing = "dep"; st.query = "" }
                Text(
                    "⇄", Modifier.padding(horizontal = 8.dp).clickable { val t = st.dep; st.dep = st.arr; st.arr = t },
                    style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.primary,
                )
                PlaceBox("도착", arr?.name ?: "터미널 고르기", st.editing == "arr", Modifier.weight(1f)) { st.editing = "arr"; st.query = "" }
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
        LaunchedEffect(st.query) {
            extra = emptyList()
            val q = st.query.trim()
            if (q.length < 2) return@LaunchedEffect
            delay(400)
            extra = repo.searchSuburbTerminals(q)
        }
        OutlinedTextField(
            st.query, { st.query = it }, Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            placeholder = { Text(if (editing == "dep") "출발 터미널 이름" else "도착 터미널 이름") }, singleLine = true, shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant),
        )
        val q = st.query.replace(" ", "")
        // 고를 때는 반대쪽과 같은 종류(고속끼리·시외끼리)만
        val other = if (editing == "arr") st.dep else st.arr
        val shown = (terminals + extra).distinctBy { it.id }
            .filter { q.isEmpty() || it.name.replace(" ", "").contains(q) || it.region.contains(q) }
            .filter { other == null || it.express == other.express }
            .sortedWith(compareBy({ !it.name.startsWith(q) }, { !it.express }, { it.name.length }, { it.name })).take(80)
        LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface)) {
            items(shown, key = { it.id }) { t ->
                Row(
                    Modifier.fillMaxWidth().clickable {
                        if (editing == "dep") {
                            st.dep = t
                            if (st.arr?.express != t.express) st.arr = null
                            st.editing = if (st.arr == null) "arr" else null
                        } else {
                            st.arr = t
                            st.editing = null
                        }
                        st.query = ""
                    }.padding(horizontal = 16.dp, vertical = 11.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(t.name, style = MaterialTheme.typography.titleMedium)
                        if (t.region.isNotEmpty()) Text(t.region, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(if (t.express) "고속" else "시외", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
        return
    }
    val list = trips
    when {
        dep == null || arr == null -> Note("출발·도착 터미널을 골라 주세요")
        failed -> Note("시간표를 받지 못했어요. 잠시 뒤 다시 시도해 주세요.")
        list == null -> Note("시간표를 불러오는 중...")
        list.isEmpty() -> Note("${dep.name} → ${arr.name} 그날 버스가 없어요")
        else -> {
            val now = if (st.tomorrow) -1 else nowMinute()
            val next = list.indexOfFirst { it.depMin >= now }.coerceAtLeast(0)
            val state = rememberLazyListState()
            LaunchedEffect(list, st.tomorrow) { state.scrollToItem(next) }
            Text(
                "${list.size}편" + if (!st.tomorrow) " · 남은 버스 ${list.count { it.depMin >= now }}편" else "",
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            LazyColumn(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surface), state = state) {
                items(list, key = { "${it.depMin}|${it.grade}" }) { t ->
                    val gone = t.depMin < now
                    val main = if (gone) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onSurface
                    val sub = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = if (gone) 0.6f else 1f)
                    Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text(
                                TrainTable.clock(t.depMin) + (t.arrMin?.let { "  →  " + TrainTable.clock(it) } ?: ""),
                                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = main,
                            )
                            t.arrMin?.let { Text(duration(it - t.depMin), style = MaterialTheme.typography.bodySmall, color = sub) }
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(t.grade, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = main)
                            t.fare?.let { Text(won(it), style = MaterialTheme.typography.bodySmall, color = sub) }
                        }
                    }
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}
