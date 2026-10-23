package com.eunho.tagohub.ui.timetable

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.TextButton
import androidx.compose.material3.VerticalDivider
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.core.Fmt
import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.data.DelayNote
import com.eunho.tagohub.data.RailFacilities
import com.eunho.tagohub.data.RunSummary
import com.eunho.tagohub.data.StopTime
import com.eunho.tagohub.data.TrainTransfer
import com.eunho.tagohub.data.Trip
import com.eunho.tagohub.ui.common.DateStrip
import com.eunho.tagohub.ui.common.GradeStrip
import com.eunho.tagohub.ui.common.LoadView
import com.eunho.tagohub.ui.common.MessageBox
import com.eunho.tagohub.ui.common.OdSelector
import com.eunho.tagohub.ui.common.Pill
import com.eunho.tagohub.ui.common.PlacePickerSheet
import com.eunho.tagohub.ui.common.Tnum
import java.time.LocalDateTime
import java.time.temporal.ChronoUnit

/** 목록을 열지 않고 바로 고를 수 있는 추천 칸 (예: 내 위치에서 가까운 역과 거리) */
data class QuickPick(val place: com.eunho.tagohub.data.Place, val label: String)

@Composable
fun TimetablePane(
    c: TimetableController,
    accent: Color,
    pickerNoun: String,
    gradeAllLabel: String,
    idleText: String,
    quickPicks: List<QuickPick> = emptyList(),
) {
    val dep by c.dep.collectAsStateWithLifecycle()
    val arr by c.arr.collectAsStateWithLifecycle()
    val date by c.date.collectAsStateWithLifecycle()
    val grades by c.grades.collectAsStateWithLifecycle()
    val grade by c.grade.collectAsStateWithLifecycle()
    val trips by c.trips.collectAsStateWithLifecycle()
    val stopsMap by c.stops.collectAsStateWithLifecycle()
    val historyMap by c.history.collectAsStateWithLifecycle()
    val notes by c.delayNotes.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Boolean?>(null) } // true 출발, false 도착

    LaunchedEffect(c) { c.ensureGrades() }

    Column(Modifier.fillMaxSize()) {
        // 화면 제목 머리띠 바로 아래로 이어지는 같은 색 띠 안에 출발·도착 (철도 예매 앱처럼)
        Surface(color = com.eunho.tagohub.ui.theme.headerColor(), contentColor = Color.White) {
            Column(Modifier.padding(bottom = 12.dp)) {
                OdSelector(
                    dep, arr, accent, "출발", "도착",
                    onDep = { picking = true },
                    onArr = { picking = false },
                    onSwap = c::swap,
                )
            }
        }
        if (quickPicks.isNotEmpty()) {
            // 가까운 역: 누르면 출발역(아직 없으면) → 도착역 순서로 채움
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                item { Text("가까운 역", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                itemsIndexed(quickPicks) { _, q ->
                    androidx.compose.material3.AssistChip(
                        onClick = { if (dep == null) c.setDep(q.place) else c.setArr(q.place) },
                        label = { Text("${q.place.name} · ${q.label}") },
                    )
                }
            }
        }
        Spacer(Modifier.height(8.dp))
        DateStrip(date, c::setDate)
        (grades as? Load.Ok)?.value?.takeIf { it.isNotEmpty() }?.let { list ->
            GradeStrip(list, grade, accent, gradeAllLabel, c::setGrade)
        }
        Spacer(Modifier.height(4.dp))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("출발 빠른 순", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        TripList(
            trips, accent, idleText, onRetry = c::search,
            stops = stopsMap,
            stopsKey = c::stopsKey,
            onStops = if (c.supportsStops) c::toggleStops else null,
            history = historyMap,
            onHistory = if (c.supportsHistory) c::toggleHistory else null,
            notes = notes,
            footer = if (c.supportsTransfer) ({ TransferFooter(c, accent) }) else null,
        )
    }

    picking?.let { isDep ->
        PlacePickerSheet(
            title = (if (isDep) "출발 " else "도착 ") + pickerNoun,
            onDismiss = { picking = null },
            onPick = { p ->
                if (isDep) c.setDep(p) else c.setArr(p)
                picking = null
            },
            loader = c.placeLoader,
            browse = c.browseLoader != null,
        )
    }
}

/** 출발 안내판 형태의 운행편 목록. 이미 떠난 편은 흐리게, 다음 편으로 자동 스크롤. */
@Composable
fun TripList(
    state: Load<List<Trip>>,
    accent: Color,
    idleText: String,
    onRetry: () -> Unit,
    /** 기차: 정차역을 펼친 열차들 */
    stops: Map<String, Load<List<StopTime>>> = emptyMap(),
    stopsKey: (Trip) -> String = { "" },
    onStops: ((Trip) -> Unit)? = null,
    /** 기차: 지난 7일 운행(지연)을 펼친 열차들 */
    history: Map<String, Load<RunSummary>> = emptyMap(),
    onHistory: ((Trip) -> Unit)? = null,
    /** 기차: 카드마다 지난주 도착 지연 요약 (열차번호 → 요약) */
    notes: Map<String, DelayNote> = emptyMap(),
    /** 기차: 목록 아래에 붙는 환승 경로. 있으면 운행편이 하나도 없어도 목록 화면을 보여줌 */
    footer: (@Composable () -> Unit)? = null,
) {
    LoadView(
        state,
        onRetry = onRetry,
        isEmpty = { it.isEmpty() && footer == null },
        emptyText = "해당 날짜에 운행편이 없습니다.",
        idle = { MessageBox(idleText) },
    ) { trips ->
        val now = remember(trips) { LocalDateTime.now() }
        val next = remember(trips) { trips.indexOfFirst { t -> t.dep?.isBefore(now) == false } }
        val listState = rememberLazyListState()
        LaunchedEffect(trips) { if (next > 0) listState.scrollToItem(next) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            if (trips.isEmpty()) item { MessageBox("해당 날짜에 직통 운행편이 없습니다.") }
            itemsIndexed(trips) { i, t ->
                Column {
                    TripRow(t, accent, past = t.dep?.isBefore(now) == true, next = i == next)
                    t.number?.let { notes[it] }?.let { DelayLine(it) }
                    if (onStops != null) StopsSection(stops[stopsKey(t)], accent) { onStops(t) }
                    if (onHistory != null) HistorySection(history[stopsKey(t)], accent) { onHistory(t) }
                }
            }
            if (footer != null) item { footer() }
        }
    }
}

/**
 * 운행편 카드(철도 예매 앱처럼): 위에 차종 배지·편명, 가운데 큰 출발 시각 → 도착 시각과 역 이름,
 * 아래에 소요시간 칩. 오른쪽은 세로 줄로 나눠 요금을 보여준다.
 */
@Composable
fun TripRow(t: Trip, accent: Color, past: Boolean, next: Boolean) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(top = 6.dp)
            .alpha(if (past) 0.4f else 1f),
        color = MaterialTheme.colorScheme.surface,
        border = if (next) BorderStroke(2.dp, accent) else null,
    ) {
        Row(Modifier.height(IntrinsicSize.Min)) {
            Column(Modifier.weight(1f).padding(start = 16.dp, end = 12.dp, top = 12.dp, bottom = 12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    if (t.kind.isNotBlank()) Pill(t.kind, accent)
                    t.number?.let { Text(it, style = MaterialTheme.typography.bodyLarge.merge(Tnum), fontWeight = FontWeight.Black) }
                    if (next) Text("다음 출발", style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.Black)
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.Top) {
                    Column {
                        Text(
                            t.dep?.let(TagoTime::hm) ?: "--:--",
                            style = MaterialTheme.typography.headlineMedium.merge(Tnum),
                            fontWeight = FontWeight.Black,
                        )
                        Text(t.depName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                    Icon(
                        Icons.AutoMirrored.Filled.ArrowForward,
                        contentDescription = "에서",
                        tint = variant,
                        modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp).size(20.dp),
                    )
                    val dayShift = if (t.dep != null && t.arr != null) ChronoUnit.DAYS.between(t.dep.toLocalDate(), t.arr.toLocalDate()) else 0
                    Column {
                        Text(
                            (t.arr?.let(TagoTime::hm) ?: "--:--") + if (dayShift > 0) " +${dayShift}일" else "",
                            style = MaterialTheme.typography.headlineMedium.merge(Tnum),
                            fontWeight = FontWeight.Black,
                        )
                        Text(t.arrName, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
                Fmt.duration(t.dep, t.arr)?.let {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "$it 소요",
                        Modifier
                            .clip(RoundedCornerShape(4.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant)
                            .padding(horizontal = 8.dp, vertical = 3.dp),
                        style = MaterialTheme.typography.bodySmall,
                        color = variant,
                    )
                }
            }
            VerticalDivider(Modifier.padding(vertical = 10.dp), color = MaterialTheme.colorScheme.outlineVariant)
            Column(
                Modifier.widthIn(min = 120.dp).padding(start = 12.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.End,
            ) {
                if (t.prices.isEmpty()) {
                    Text("요금 정보 없음", style = MaterialTheme.typography.bodySmall, color = variant)
                } else {
                    t.prices.forEach { (label, won) ->
                        Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            if (t.prices.size > 1) Text(label, style = MaterialTheme.typography.bodySmall, color = variant)
                            Text(Fmt.won(won), style = MaterialTheme.typography.titleMedium.merge(Tnum), fontWeight = FontWeight.Black)
                        }
                    }
                }
            }
        }
    }
}

/** 열차 카드 아래 "정차역 보기": 누르면 그 열차가 서는 역을 시각과 함께 펼침 (국토교통부 시간표를 구간별로 맞춰 본 추정) */
@Composable
private fun StopsSection(open: Load<List<StopTime>>?, accent: Color, onToggle: () -> Unit) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = onToggle, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp)) {
                Text(if (open == null) "정차역 보기" else "정차역 닫기", color = accent, fontWeight = FontWeight.Bold)
            }
            when (open) {
                null, is Load.Idle -> Unit
                is Load.Loading -> Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("정차역을 찾는 중…", style = MaterialTheme.typography.bodySmall, color = variant)
                }
                is Load.Fail -> Text(open.message, Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                is Load.Ok -> Column(Modifier.padding(bottom = 10.dp)) {
                    open.value.forEachIndexed { i, s ->
                        val end = i == 0 || i == open.value.lastIndex
                        Row(Modifier.padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(9.dp).clip(CircleShape)
                                    .background(if (end) accent else MaterialTheme.colorScheme.surface)
                                    .border(2.dp, accent, CircleShape),
                            )
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(s.name, style = MaterialTheme.typography.bodyMedium, fontWeight = if (end) FontWeight.Black else FontWeight.Normal)
                                RailFacilities.summary(s.name)?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = variant) }
                            }
                            Text(s.time?.let(TagoTime::hm) ?: "--:--", style = MaterialTheme.typography.bodyMedium.merge(Tnum), color = variant)
                        }
                    }
                    Text("한국철도공사 열차시간표 기준이에요. 시각은 출발(종착역은 도착)이고, 운행 요일에 따라 다를 수 있어요. 편의시설은 ${RailFacilities.AS_OF} 기준이에요.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = variant)
                }
            }
        }
    }
}

/** 기차 목록 아래: 환승해서 가는 방법 (직통이 없으면 자동으로 찾고, 있으면 버튼) */
@Composable
private fun TransferFooter(c: TimetableController, accent: Color) {
    val state by c.transfers.collectAsStateWithLifecycle()
    val stopsMap by c.stops.collectAsStateWithLifecycle()
    val historyMap by c.history.collectAsStateWithLifecycle()
    val trips by c.trips.collectAsStateWithLifecycle()
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
        when (val s = state) {
            is Load.Idle -> if ((trips as? Load.Ok)?.value?.isNotEmpty() == true) {
                OutlinedButton(onClick = c::loadTransfers, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) { Text("환승해서 가는 방법도 보기") }
            }
            is Load.Loading -> Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                Text("환승 경로를 찾는 중… (주요 환승역을 확인해서 몇 초 걸려요)", style = MaterialTheme.typography.bodyMedium, color = variant)
            }
            is Load.Fail -> Column(Modifier.padding(16.dp)) {
                Text(s.message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = c::loadTransfers) { Text("다시 시도") }
            }
            is Load.Ok -> if (s.value.isEmpty()) {
                MinWaitChips(c)
                MessageBox("환승 1회로 갈 수 있는 열차를 찾지 못했습니다. 2회 이상 환승이 필요하거나 그날 운행이 없을 수 있어요.")
            } else {
                Text("환승해서 가기  ·  1회 환승만", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
                MinWaitChips(c)
                s.value.forEach { t -> TransferCard(t, accent, stopsMap, historyMap, c) }
            }
        }
    }
}

@Composable
private fun TransferCard(t: TrainTransfer, accent: Color, stopsMap: Map<String, Load<List<StopTime>>>, historyMap: Map<String, Load<RunSummary>>, c: TimetableController) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.fillMaxWidth().padding(top = 6.dp), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(top = 12.dp)) {
            Row(Modifier.padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(t.dep?.let(TagoTime::hm) ?: "--:--", style = MaterialTheme.typography.headlineSmall.merge(Tnum), fontWeight = FontWeight.Black)
                Icon(Icons.AutoMirrored.Filled.ArrowForward, contentDescription = "에서", tint = variant, modifier = Modifier.size(18.dp))
                Text(t.arr?.let(TagoTime::hm) ?: "--:--", style = MaterialTheme.typography.headlineSmall.merge(Tnum), fontWeight = FontWeight.Black)
                Spacer(Modifier.weight(1f))
                Fmt.duration(t.dep, t.arr)?.let { Text("$it 소요", style = MaterialTheme.typography.bodySmall, color = variant) }
            }
            t.legs.forEachIndexed { i, leg ->
                Column(Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        if (leg.kind.isNotBlank()) Pill(leg.kind, accent)
                        leg.number?.let { Text(it, style = MaterialTheme.typography.bodyMedium.merge(Tnum), fontWeight = FontWeight.Black) }
                        Spacer(Modifier.weight(1f))
                        leg.prices.firstOrNull()?.let { Text(Fmt.won(it.second), style = MaterialTheme.typography.bodyMedium.merge(Tnum), fontWeight = FontWeight.Bold) }
                    }
                    Text(
                        "${leg.depName} ${leg.dep?.let(TagoTime::hm) ?: "--:--"}  →  ${leg.arrName} ${leg.arr?.let(TagoTime::hm) ?: "--:--"}",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
                if (c.supportsStops) StopsSection(stopsMap[c.stopsKey(leg)], accent) { c.toggleStops(leg) }
                if (c.supportsHistory) HistorySection(historyMap[c.stopsKey(leg)], accent) { c.toggleHistory(leg) }
                if (i == 0) {
                    Text(
                        "${t.hub}에서 ${t.waitMinutes}분 기다려 갈아타기",
                        Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceVariant).padding(horizontal = 16.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
            t.fare?.let {
                Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
                    Text("합계", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, color = variant)
                    Text(Fmt.won(it), style = MaterialTheme.typography.titleMedium.merge(Tnum), fontWeight = FontWeight.Black)
                }
            }
        }
    }
}

/** 열차 카드 아래 "지난주 운행 보기": 어제까지 7일 동안 이 열차가 실제로 얼마나 늦었는지 (한국철도공사 운행기록) */
@Composable
private fun HistorySection(open: Load<RunSummary>?, accent: Color, onToggle: () -> Unit) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp)) {
            TextButton(onClick = onToggle, contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 2.dp)) {
                Text(if (open == null) "지난주 운행 보기" else "지난주 운행 닫기", color = accent, fontWeight = FontWeight.Bold)
            }
            when (open) {
                null, is Load.Idle -> Unit
                is Load.Loading -> Row(Modifier.padding(bottom = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                    Text("지난 7일 운행을 불러오는 중…", style = MaterialTheme.typography.bodySmall, color = variant)
                }
                is Load.Fail -> Text(open.message, Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                is Load.Ok -> Column(Modifier.padding(bottom = 10.dp)) {
                    val s = open.value
                    if (s.days.isEmpty()) {
                        Text("지난 7일 기록이 없어요. 그 기간에 운행하지 않은 열차일 수 있어요.", style = MaterialTheme.typography.bodySmall, color = variant)
                    } else {
                        Text(
                            buildString {
                                append("도착 평균 ").append(delayText(s.average))
                                s.worst?.let { append(" · 가장 늦은 날 ").append(delayText(it)) }
                                if (s.lateDays > 0) append(" · 5분 이상 늦은 날 ").append(s.lateDays).append("일")
                            },
                            style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
                        )
                        Spacer(Modifier.height(4.dp))
                        s.days.forEach { d ->
                            Row(Modifier.padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(
                                    "%d/%d (%s)".format(d.day.monthValue, d.day.dayOfMonth, d.day.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT, java.util.Locale.KOREAN)),
                                    Modifier.width(84.dp), style = MaterialTheme.typography.bodyMedium.merge(Tnum), color = variant,
                                )
                                Text("출발 ${delayText(d.departure)}", Modifier.width(96.dp), style = MaterialTheme.typography.bodyMedium)
                                val late = (d.arrival ?: 0) >= 5
                                Text("도착 ${delayText(d.arrival)}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
                            }
                        }
                        Text("계획 시각과 실제 운행기록의 차이예요. 어제까지 기록만 있어요.", Modifier.padding(top = 4.dp), style = MaterialTheme.typography.labelSmall, color = variant)
                    }
                }
            }
        }
    }
}

private fun delayText(min: Int?): String = when {
    min == null -> "-"
    min == 0 -> "정시"
    min > 0 -> "+${min}분"
    else -> "${min}분"
}

/** 카드 아래 한 줄: 지난주(어제까지 7일) 도착 지연 요약. 5분 이상 늦었던 열차는 강조 */
@Composable
private fun DelayLine(n: DelayNote) {
    val late = n.average >= 5
    Surface(Modifier.fillMaxWidth(), color = MaterialTheme.colorScheme.surface) {
        Text(
            "지난주 도착 평균 ${delayText(n.average)} · 가장 늦은 날 ${delayText(n.worst)} (${n.days}일 기록)",
            Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelMedium,
            color = if (late) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** 갈아타는 데 필요한 최소 시간 고르기 (5·10·15·20분) */
@Composable
private fun MinWaitChips(c: TimetableController) {
    val wait by c.minWait.collectAsStateWithLifecycle()
    androidx.compose.foundation.lazy.LazyRow(
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        item { Text("갈아타는 시간", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        itemsIndexed(listOf(5, 10, 15, 20)) { _, m ->
            androidx.compose.material3.FilterChip(selected = wait == m, onClick = { c.setMinWait(m) }, label = { Text("${m}분 이상") })
        }
    }
}
