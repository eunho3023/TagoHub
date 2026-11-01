package com.eunho.tagohub.ui.timetable

import androidx.compose.foundation.BorderStroke
import com.eunho.tagohub.ui.theme.LocalDarkTheme
import com.eunho.tagohub.ui.common.PillChip
import com.eunho.tagohub.ui.common.OptionRow
import com.eunho.tagohub.ui.common.CircleButton
import com.eunho.tagohub.data.Place
import com.eunho.tagohub.data.Grade
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ModalBottomSheet
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

@Composable
fun TimetablePane(
    c: TimetableController,
    accent: Color,
    pickerNoun: String,
    gradeAllLabel: String,
    idleText: String,
    /** 최근 조회를 저장하는 이름 (시간표 종류마다 다르게) */
    recentKey: String = pickerNoun + gradeAllLabel,
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

    var searched by remember { mutableStateOf(false) }
    var sort by remember { mutableStateOf(TripSort.Dep) }
    var sorting by remember { mutableStateOf(false) }
    var optionTab by remember { mutableStateOf<Int?>(null) } // 조회옵션 시트가 열린 탭
    var viaTransfer by remember { mutableStateOf(false) }
    var calendar by remember { mutableStateOf(false) }
    val ready = dep != null && arr != null
    val context = androidx.compose.ui.platform.LocalContext.current
    var recents by remember { mutableStateOf(RecentRoutes.load(context, recentKey)) }

    LaunchedEffect(c) { c.ensureGrades() }
    LaunchedEffect(viaTransfer, searched) { if (viaTransfer && searched) c.loadTransfers() }

    Column(Modifier.fillMaxSize()) {
        if (!searched) {
            // 조회 전: 출발·도착 카드, 가는날, 등급을 고르고 아래 큰 버튼으로 조회
            Column(Modifier.padding(top = 14.dp, bottom = 12.dp)) {
                OdSelector(
                    dep, arr, accent, "출발", "도착",
                    onDep = { picking = true },
                    onArr = { picking = false },
                    onSwap = c::swap,
                )
            }
            Spacer(Modifier.height(8.dp))
            DateStrip(date, c::setDate)
            Spacer(Modifier.height(8.dp))
            (grades as? Load.Ok)?.value?.takeIf { it.isNotEmpty() }?.let { list ->
                GradeStrip(list, grade, accent, gradeAllLabel, c::setGrade)
            }
            Spacer(Modifier.height(16.dp))
            Button(
                onClick = {
                    c.search(); searched = true
                    val d = dep; val a = arr
                    if (d != null && a != null) recents = RecentRoutes.add(context, recentKey, d, a)
                },
                enabled = ready,
                shape = CircleShape,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).height(50.dp),
            ) {
                Text(if (ready) "시간표 조회" else "출발과 도착을 먼저 골라 주세요", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
            }
            if (recents.isNotEmpty()) {
                Text("최근 조회", Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 6.dp), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, fontWeight = FontWeight.Bold)
                Surface(
                    Modifier.padding(horizontal = 16.dp).fillMaxWidth(),
                    shape = RoundedCornerShape(14.dp),
                    color = MaterialTheme.colorScheme.surface,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                ) {
                    Column {
                        recents.forEachIndexed { i, r ->
                            if (i > 0) HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            Row(
                                Modifier.fillMaxWidth().heightIn(min = 52.dp)
                                    .clickable {
                                        c.setDep(r.first); c.setArr(r.second); searched = true
                                        recents = RecentRoutes.add(context, recentKey, r.first, r.second)
                                    }
                                    .padding(horizontal = 14.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Text("${r.first.name} → ${r.second.name}", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                            }
                        }
                    }
                }
            }
        } else {
            // 조회 후: 구간 요약과 수정, 날짜 막대, 필터 알약, 정렬, 운행편 카드
            Row(
                Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(accent))
                Text(
                    "${dep?.name ?: "-"} → ${arr?.name ?: "-"}",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.ExtraBold,
                    maxLines = 2,
                )
                Surface(
                    modifier = Modifier.clip(CircleShape).clickable { searched = false },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.primary,
                ) {
                    Text("수정", Modifier.padding(horizontal = 16.dp, vertical = 8.dp), style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.ExtraBold)
                }
            }
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                CircleButton(onClick = { c.setDate(date.minusDays(1)) }, enabled = date.isAfter(java.time.LocalDate.now())) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowLeft, contentDescription = "전날")
                }
                Surface(
                    modifier = Modifier.weight(1f).height(40.dp).clip(CircleShape).clickable { calendar = true },
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surfaceVariant,
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Text(com.eunho.tagohub.ui.common.dateLabel(date), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold)
                    }
                }
                CircleButton(onClick = { c.setDate(date.plusDays(1)) }) {
                    Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, contentDescription = "다음날")
                }
            }
            androidx.compose.foundation.lazy.LazyRow(
                contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                item { PillChip(grade?.name ?: gradeAllLabel, grade != null, { optionTab = 0 }, dropdown = true) }
                if (c.supportsTransfer) {
                    item { PillChip(if (viaTransfer) "환승" else "직통", viaTransfer, { optionTab = 1 }, dropdown = true) }
                    item { PillChip("조회옵션", false, { optionTab = 2 }, dropdown = true) }
                }
            }
            Row(
                Modifier.fillMaxWidth().clickable { sorting = true }.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(sort.label, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold, color = MaterialTheme.colorScheme.onSurface)
                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "정렬 바꾸기", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                if (!viaTransfer) (trips as? Load.Ok)?.value?.let {
                    Text("${it.size}개", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            if (viaTransfer) {
                Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) { TransferFooter(c, accent) }
            } else {
                val shown = (trips as? Load.Ok)?.let { Load.Ok(sort.apply(it.value)) } ?: trips
                TripList(
                    shown, accent, idleText, onRetry = c::search,
                    stops = stopsMap,
                    stopsKey = c::stopsKey,
                    onStops = if (c.supportsStops) c::toggleStops else null,
                    history = historyMap,
                    onHistory = if (c.supportsHistory) c::toggleHistory else null,
                    notes = notes,
                    footer = if (c.supportsTransfer) ({ TransferFooter(c, accent) }) else null,
                )
            }
        }
    }

    if (sorting) {
        SortSheet(sort, onPick = { sort = it; sorting = false }, onDismiss = { sorting = false })
    }
    if (calendar) {
        com.eunho.tagohub.ui.common.CalendarSheet(date, c::setDate, { calendar = false }, title = "가는날 선택")
    }
    optionTab?.let { tab ->
        OptionSheet(
            startTab = tab,
            noun = gradeAllLabel.removePrefix("전체 "),
            allLabel = gradeAllLabel,
            grades = (grades as? Load.Ok)?.value.orEmpty(),
            grade = grade,
            viaTransfer = viaTransfer,
            minWait = c.minWait.collectAsStateWithLifecycle().value,
            withTransfer = c.supportsTransfer,
            onApply = { g, via, wait ->
                if (g?.id != grade?.id) c.setGrade(g)
                if (wait != c.minWait.value) c.setMinWait(wait)
                viaTransfer = via
                optionTab = null
            },
            onDismiss = { optionTab = null },
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
            loader = { q -> c.placesFor(isDep, q) },
            browse = (c.browseLoader != null || c.geo != null) && !(c.restrictsPicker && (if (isDep) c.arr.value else c.dep.value) != null),
            geo = c.geo,
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
        emptyText = "해당 날짜에 운행편이 없어요.",
        idle = { MessageBox(idleText) },
    ) { trips ->
        val now = remember(trips) { LocalDateTime.now() }
        val next = remember(trips) { trips.indexOfFirst { t -> t.dep?.isBefore(now) == false } }
        val listState = rememberLazyListState()
        LaunchedEffect(trips) { if (next > 0) listState.scrollToItem(next) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
            if (trips.isEmpty()) item { MessageBox("해당 날짜에 직통 운행편이 없어요.") }
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

/** 목록 정렬 방식 */
enum class TripSort(val label: String) {
    Dep("출발 빠른 순"), Arr("도착 빠른 순"), Short("최단 시간 순");

    fun apply(list: List<Trip>): List<Trip> = when (this) {
        Dep -> list
        Arr -> list.sortedBy { it.arr ?: LocalDateTime.MAX }
        Short -> list.sortedBy { if (it.dep != null && it.arr != null) ChronoUnit.MINUTES.between(it.dep, it.arr) else Long.MAX_VALUE }
    }
}

/** 정렬 기준 고르는 아래 시트: 고른 줄은 파랑 바탕에 체크 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun SortSheet(current: TripSort, onPick: (TripSort) -> Unit, onDismiss: () -> Unit) {
    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = MaterialTheme.colorScheme.surface) {
        Column(Modifier.padding(horizontal = 16.dp).padding(bottom = 24.dp)) {
            Text("정렬 기준", Modifier.padding(horizontal = 4.dp, vertical = 8.dp), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            TripSort.entries.forEach { s ->
                val on = s == current
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp).height(56.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(if (on) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
                        .clickable { onPick(s) }
                        .padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        s.label, Modifier.weight(1f),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = if (on) FontWeight.ExtraBold else FontWeight.SemiBold,
                        color = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
                    )
                    if (on) Icon(Icons.Filled.Check, contentDescription = "선택됨", tint = MaterialTheme.colorScheme.primary)
                }
            }
        }
    }
}

/**
 * 운행편 카드: 위 줄에 차종 배지·편명, 가운데에 출발 ──(소요시간)── 도착 시각을 선으로 이은 줄,
 * 그 아래 역 이름, 맨 아래 요금 칸. 역·공항 이름은 줄바꿈해서 끝까지 보여준다.
 */
@Composable
fun TripRow(t: Trip, accent: Color, past: Boolean, next: Boolean) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val kc = kindColor(t.kind, accent)
    Surface(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 4.dp)
            .alpha(if (past) 0.4f else 1f),
        shape = RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surface,
        border = BorderStroke(if (next) 2.dp else 1.dp, if (next) accent else MaterialTheme.colorScheme.outlineVariant),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (t.kind.isNotBlank()) Pill(t.kind, kc)
                t.number?.let { Text(it, style = MaterialTheme.typography.bodyMedium.merge(Tnum), fontWeight = FontWeight.ExtraBold) }
                Spacer(Modifier.weight(1f))
                if (next) Text("다음 출발", style = MaterialTheme.typography.labelMedium, color = accent, fontWeight = FontWeight.ExtraBold)
            }
            Spacer(Modifier.height(8.dp))
            val dayShift = if (t.dep != null && t.arr != null) ChronoUnit.DAYS.between(t.dep.toLocalDate(), t.arr.toLocalDate()) else 0
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    t.dep?.let(TagoTime::hm) ?: "--:--",
                    style = MaterialTheme.typography.headlineSmall.merge(Tnum),
                    fontWeight = FontWeight.ExtraBold,
                )
                Column(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Fmt.duration(t.dep, t.arr)?.let { Text(it, style = MaterialTheme.typography.labelMedium, color = variant, fontWeight = FontWeight.Bold) }
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).clip(CircleShape).border(2.dp, kc, CircleShape))
                        Box(Modifier.weight(1f).height(3.dp).background(kc.copy(alpha = 0.5f)))
                        Box(Modifier.size(8.dp).clip(CircleShape).background(kc))
                    }
                }
                Text(
                    (t.arr?.let(TagoTime::hm) ?: "--:--") + if (dayShift > 0) " +${dayShift}일" else "",
                    style = MaterialTheme.typography.headlineSmall.merge(Tnum),
                    fontWeight = FontWeight.ExtraBold,
                )
            }
            Row(Modifier.padding(top = 2.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(t.depName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = variant)
                Text(t.arrName, Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = variant, textAlign = androidx.compose.ui.text.style.TextAlign.End)
            }
            Spacer(Modifier.height(10.dp))
            if (t.prices.isEmpty()) {
                Text("요금 정보 없음", style = MaterialTheme.typography.bodySmall, color = variant)
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    t.prices.forEach { (label, won) ->
                        Row(
                            Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
                                .padding(horizontal = 10.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            if (t.prices.size > 1) Text(label, Modifier.weight(1f), style = MaterialTheme.typography.labelMedium, color = variant)
                            else Spacer(Modifier.weight(1f))
                            Text(Fmt.won(won), style = MaterialTheme.typography.titleSmall.merge(Tnum), fontWeight = FontWeight.ExtraBold)
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
                MessageBox("환승 1회로 갈 수 있는 열차를 찾지 못했어요. 2회 이상 환승이 필요하거나 그날 운행이 없을 수 있어요.")
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

/** 최근 조회한 구간 (기기에 3개까지 저장) */
private object RecentRoutes {
    private const val PREFS = "recent_routes"

    fun load(context: android.content.Context, key: String): List<Pair<Place, Place>> {
        val json = context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).getString(key, null) ?: return emptyList()
        return runCatching {
            val arr = org.json.JSONArray(json)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Place(o.getString("di"), o.getString("dn")) to Place(o.getString("ai"), o.getString("an"))
            }
        }.getOrDefault(emptyList())
    }

    fun add(context: android.content.Context, key: String, dep: Place, arr: Place): List<Pair<Place, Place>> {
        val next = (listOf(dep to arr) + load(context, key).filterNot { it.first.id == dep.id && it.second.id == arr.id }).take(3)
        val json = org.json.JSONArray().also { a ->
            next.forEach { (d, r) -> a.put(org.json.JSONObject().put("di", d.id).put("dn", d.name).put("ai", r.id).put("an", r.name)) }
        }
        context.getSharedPreferences(PREFS, android.content.Context.MODE_PRIVATE).edit().putString(key, json.toString()).apply()
        return next
    }
}

/** 차종별 배지·선 색: 수단 색은 배지와 선에만 쓴다 */
@Composable
private fun kindColor(kind: String, accent: Color): Color {
    val dark = LocalDarkTheme.current
    return when {
        "KTX" in kind || "SRT" in kind || "이음" in kind -> if (dark) Color(0xFF5B80E0) else Color(0xFF1B3E8A)
        "ITX" in kind -> if (dark) Color(0xFF3FA66B) else Color(0xFF1F7A4A)
        "무궁화" in kind -> if (dark) Color(0xFFE05A93) else Color(0xFFC2185B)
        "새마을" in kind -> if (dark) Color(0xFFE59A3C) else Color(0xFFA85F0E)
        else -> accent
    }
}

/**
 * 조회옵션 시트: 차종 / 환승 여부 / 갈아타는 시간 탭(환승이 없는 시간표는 차종만).
 * 아래에 고른 옵션 요약과 초기화·선택 완료.
 */
@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun OptionSheet(
    startTab: Int,
    noun: String,
    allLabel: String,
    grades: List<Grade>,
    grade: Grade?,
    viaTransfer: Boolean,
    minWait: Int,
    withTransfer: Boolean,
    onApply: (Grade?, Boolean, Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val cs = MaterialTheme.colorScheme
    var tab by remember { mutableIntStateOf(if (withTransfer) startTab else 0) }
    var g by remember { mutableStateOf(grade) }
    var via by remember { mutableStateOf(viaTransfer) }
    var wait by remember { mutableIntStateOf(minWait) }
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = cs.surface,
    ) {
        Column(Modifier.fillMaxHeight(0.85f).padding(horizontal = 20.dp)) {
            Text("조회옵션", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold)
            if (withTransfer) {
                Row(Modifier.fillMaxWidth().padding(vertical = 10.dp).clip(CircleShape).background(cs.surfaceVariant).padding(4.dp)) {
                    listOf(noun, "환승 여부", "갈아타는 시간").forEachIndexed { i, label ->
                        Box(
                            Modifier.weight(1f).height(38.dp).clip(CircleShape)
                                .background(if (tab == i) cs.surface else Color.Transparent)
                                .clickable { tab = i },
                            contentAlignment = Alignment.Center,
                        ) {
                            com.eunho.tagohub.ui.common.FitText(
                                label, maxSizeSp = 14f, minSizeSp = 10f,
                                fontWeight = if (tab == i) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (tab == i) cs.onSurface else cs.onSurfaceVariant,
                            )
                        }
                    }
                }
            } else {
                Spacer(Modifier.height(10.dp))
            }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                when (tab) {
                    0 -> {
                        OptionRow(allLabel, g == null, { g = null })
                        grades.forEach { OptionRow(it.name, g?.id == it.id, { g = it }) }
                    }
                    1 -> {
                        OptionRow("직통", !via, { via = false })
                        OptionRow("환승", via, { via = true })
                    }
                    else -> listOf(5, 10, 15, 20).forEach { m -> OptionRow("${m}분 이상", wait == m, { wait = m }) }
                }
            }
            HorizontalDivider(Modifier.padding(top = 8.dp), color = cs.outlineVariant)
            Text("선택된 옵션", Modifier.padding(top = 10.dp), style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant)
            Text(
                "✓ ${g?.name ?: allLabel}" + (if (withTransfer) "  ✓ ${if (via) "환승" else "직통"}  ✓ ${wait}분 이상" else ""),
                Modifier.padding(top = 2.dp), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold,
            )
            Row(Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 24.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Surface(
                    modifier = Modifier.width(110.dp).height(50.dp).clip(CircleShape).clickable {
                        g = null; via = false; wait = com.eunho.tagohub.data.TrainTransfers.MIN_WAIT_MIN
                    },
                    shape = CircleShape, color = cs.surfaceVariant,
                ) { Box(contentAlignment = Alignment.Center) { Text("초기화", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = cs.onSurfaceVariant) } }
                Button(
                    onClick = { onApply(g, via, wait) },
                    shape = CircleShape,
                    modifier = Modifier.weight(1f).height(50.dp),
                ) { Text("선택 완료", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.ExtraBold) }
            }
        }
    }
}
