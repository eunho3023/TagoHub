@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.eunho.tagohub.ui.subway

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import kotlinx.coroutines.delay
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.core.Fmt
import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.data.ExitInfo
import com.eunho.tagohub.data.SeoulLines
import com.eunho.tagohub.data.SubwayArrival
import com.eunho.tagohub.data.SubwayDeparture
import com.eunho.tagohub.data.SubwayStation
import com.eunho.tagohub.ui.common.LoadView
import com.eunho.tagohub.ui.common.MessageBox
import com.eunho.tagohub.ui.common.Pill
import com.eunho.tagohub.ui.common.Tnum
import com.eunho.tagohub.ui.theme.ModeColor
import com.eunho.tagohub.ui.theme.subwayLineColor
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

private fun LocalTime.hhmm() = TagoTime.clockText(hour * 60 + minute)

/** 역 상세: 실시간 도착 / 시간표 / 출구정보. 부모가 높이를 정해준다. */
@Composable
fun StationDetailPanel(
    d: StationDetail,
    c: StationDetailController,
    onClose: () -> Unit,
    refreshKey: Int = 0,
    onArrivalClick: ((SubwayArrival) -> Unit)? = null,
) {
    val tab by d.tab.collectAsStateWithLifecycle()
    val lines = d.group.displayLines
    val color = subwayLineColor(lines.first())

    LaunchedEffect(d, refreshKey) { c.runArrivals(d) }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.padding(start = 16.dp, end = 4.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .width(5.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(color)
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(d.group.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    lines.forEach { Pill(it, subwayLineColor(it)) }
                }
            }
            IconButton(onClick = onClose) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
        }
        TabRow(selectedTabIndex = tab.ordinal, contentColor = color) {
            StationTab.entries.forEach { t ->
                Tab(selected = tab == t, onClick = { c.setTab(d, t) }, text = { Text(t.label, maxLines = 1) })
            }
        }
        when (tab) {
            StationTab.Live -> LiveTab(d, onArrivalClick)
            StationTab.Timetable -> TimetableTab(d, c)
            StationTab.Exits -> ExitsTab(d, c)
        }
    }
}

// ───────────────────────── 실시간 도착 ─────────────────────────

@Composable
private fun LiveTab(d: StationDetail, onArrivalClick: ((SubwayArrival) -> Unit)?) {
    if (!d.realtime) {
        MessageBox("실시간 도착정보는 수도권 노선만 제공됩니다.\n시간표 탭에서 운행 시각을 확인하세요.")
        return
    }
    val state by d.arrivals.collectAsStateWithLifecycle()
    val updated by d.arrivalsUpdated.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        updated?.let {
            Text(
                "${it.hhmm()} 갱신, 20초마다 자동 갱신",
                Modifier.padding(start = 16.dp, top = 6.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        LoadView(state, isEmpty = { it.isEmpty() }, emptyText = "지금 도착 예정인 열차가 없습니다.") { list ->
            // 매초 다시 계산해서 "n분 후"가 실제로 줄어들게
            val now by produceState(LocalDateTime.now()) {
                while (true) {
                    delay(1000)
                    value = LocalDateTime.now()
                }
            }
            LazyColumn(Modifier.fillMaxSize()) {
                list.groupBy { it.lineId }.forEach { (lineId, rows) ->
                    val name = SeoulLines.byId[lineId]?.apiName ?: lineId
                    item(key = "h$lineId") {
                        Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp)) { Pill(name, subwayLineColor(name)) }
                    }
                    items(rows) { a ->
                        ArrivalRow(a, now, onClick = onArrivalClick?.let { cb -> { cb(a) } })
                        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    }
                }
            }
        }
    }
}

/** barvlDt 는 수신 시각 기준이므로 경과 시간을 뺀다. 0 이면 안내 문구 사용 */
private fun arrivalMain(a: SubwayArrival, now: LocalDateTime): String {
    val sec = a.seconds?.takeIf { it > 0 } ?: return com.eunho.tagohub.data.SubwayText.main(a)
    val elapsed = a.received?.let { Duration.between(it, now).seconds.coerceIn(0, 600) } ?: 0
    val left = (sec - elapsed).coerceAtLeast(0)
    return if (left < 60) "곧 도착" else "${(left + 59) / 60}분 후"
}

@Composable
private fun ArrivalRow(a: SubwayArrival, now: LocalDateTime, onClick: (() -> Unit)? = null) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                a.lineText.ifBlank { a.dest?.let { "${it}행" } ?: "" } + if (onClick != null) "  ›" else "",
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                a.upDown.ifBlank { null },
                a.message.takeIf { (a.seconds ?: 0) > 0 && it.isNotBlank() },
                a.current?.let { "현재 $it" },
            ).joinToString("   ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = variant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            Text(arrivalMain(a, now), style = MaterialTheme.typography.titleMedium.merge(Tnum), fontWeight = FontWeight.Bold)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                if (a.express) Pill("급행", Color(0xFFD7262E), filled = false)
                if (a.last) Pill("막차", ModeColor.Subway, filled = false)
            }
        }
    }
}

// ───────────────────────── 노선 선택 (시간표·출구 공용) ─────────────────────────

/** 환승역이면 TAGO 노선별 역 ID 가 달라 노선을 골라야 한다. 노선이 하나면 칩을 숨긴다. */
@Composable
private fun LinePicker(d: StationDetail, c: StationDetailController, content: @Composable (SubwayStation) -> Unit) {
    val lines by d.tagoLines.collectAsStateWithLifecycle()
    val selected by d.line.collectAsStateWithLifecycle()
    LoadView(
        lines,
        isEmpty = { it.isEmpty() },
        emptyText = "TAGO 지하철 정보에서 이 역을 찾지 못했습니다.",
    ) { list ->
        Column(Modifier.fillMaxSize()) {
            if (list.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(list) { s ->
                        val lc = subwayLineColor(s.routeName)
                        FilterChip(
                            selected = selected?.id == s.id,
                            onClick = { c.selectLine(d, s) },
                            label = { Text(s.routeName) },
                            colors = FilterChipDefaults.filterChipColors(selectedContainerColor = lc, selectedLabelColor = Color.White),
                        )
                    }
                }
            }
            selected?.let { content(it) }
        }
    }
}

// ───────────────────────── 시간표 ─────────────────────────

@Composable
private fun TimetableTab(d: StationDetail, c: StationDetailController) {
    LinePicker(d, c) { line ->
        val day by d.dayType.collectAsStateWithLifecycle()
        val upDown by d.upDown.collectAsStateWithLifecycle()
        val schedule by d.schedule.collectAsStateWithLifecycle()
        val lineColor = subwayLineColor(line.routeName)
        Column(Modifier.fillMaxSize()) {
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(DayType.entries) { t ->
                    FilterChip(selected = t == day, onClick = { c.setDay(d, t) }, label = { Text(t.label) })
                }
            }
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                listOf("U" to "상행", "D" to "하행").forEachIndexed { i, (code, label) ->
                    SegmentedButton(
                        selected = upDown == code,
                        onClick = { c.setUpDown(d, code) },
                        shape = SegmentedButtonDefaults.itemShape(i, 2),
                    ) { Text(label) }
                }
            }
            LoadView(
                schedule,
                onRetry = { c.loadSchedule(d) },
                isEmpty = { it.isEmpty() },
                emptyText = "이 조건의 시간표가 없습니다. 종착역이면 반대 방향을 확인하세요.",
            ) { list -> TimetableBoard(list, lineColor, live = day == DayType.today()) }
        }
    }
}

/** 역 시간표 게시판: 시(時) 열 + 분 목록. 행선지마다 색이 다르다. */
@Composable
private fun TimetableBoard(list: List<SubwayDeparture>, lineColor: Color, live: Boolean) {
    val destinations = remember(list) {
        list.groupingBy { it.endStationName }.eachCount().entries.sortedByDescending { it.value }.map { it.key }
    }
    val palette = listOf(lineColor, Color(0xFF5A6474), Color(0xFFB4462E), Color(0xFF2F7D5B), Color(0xFF7A4FB5), Color(0xFFB08A1E))
    val destColor = remember(destinations, lineColor) {
        destinations.withIndex().associate { (i, n) -> n to palette[i % palette.size] }
    }
    val nowService = remember(list) { LocalTime.now().let { TagoTime.serviceMinute(it.hour * 60 + it.minute) } }
    val next = remember(list, live) { if (live) list.firstOrNull { it.serviceMinute >= nowService } else null }
    val hours = remember(list) { list.groupBy { it.serviceMinute / 60 }.toSortedMap().toList() }
    val listState = rememberLazyListState()

    LaunchedEffect(list, live) {
        val n = next ?: return@LaunchedEffect
        val idx = hours.indexOfFirst { it.first == n.serviceMinute / 60 }
        if (idx > 0) listState.scrollToItem(idx)
    }

    LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
        item {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                if (next != null) {
                    val wait = next.serviceMinute - nowService
                    Text(
                        "다음 열차 ${TagoTime.clockText(next.serviceMinute % 1440)} ${next.endStationName}행, " +
                            if (wait <= 0) "곧 출발" else "${Fmt.minutes(wait)} 후",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = lineColor,
                    )
                    Spacer(Modifier.height(6.dp))
                }
                FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    destinations.forEach { dest ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier
                                    .size(10.dp)
                                    .clip(CircleShape)
                                    .background(destColor[dest] ?: lineColor)
                            )
                            Spacer(Modifier.width(4.dp))
                            Text("${dest}행", style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        items(hours) { (hour, deps) ->
            Row(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    String.format(Locale.ROOT, "%02d", hour % 24),
                    modifier = Modifier.width(44.dp),
                    style = MaterialTheme.typography.headlineSmall.merge(Tnum),
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                FlowRow(
                    Modifier.weight(1f),
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    deps.forEach { dep ->
                        val isNext = dep == next
                        val col = destColor[dep.endStationName] ?: lineColor
                        Text(
                            String.format(Locale.ROOT, "%02d", dep.serviceMinute % 60) + if (dep.arrivalOnly) "착" else "",
                            modifier = Modifier
                                .clip(RoundedCornerShape(6.dp))
                                .background(if (isNext) col else Color.Transparent)
                                .padding(horizontal = 6.dp, vertical = 3.dp),
                            style = MaterialTheme.typography.titleMedium.merge(Tnum),
                            fontWeight = if (isNext) FontWeight.Bold else FontWeight.Medium,
                            color = if (isNext) Color.White else col,
                        )
                    }
                }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

// ───────────────────────── 출구정보 ─────────────────────────

@Composable
private fun ExitsTab(d: StationDetail, c: StationDetailController) {
    LinePicker(d, c) { line ->
        val exits by d.exits.collectAsStateWithLifecycle()
        LaunchedEffect(line.id) { if (d.exits.value is Load.Idle) c.loadExits(d) }
        val color = subwayLineColor(line.routeName)
        LoadView(exits, onRetry = { c.loadExits(d) }, isEmpty = { it.isEmpty() }, emptyText = "출구 정보가 없습니다.") { list ->
            LazyColumn(Modifier.fillMaxSize()) {
                items(list) { e ->
                    ExitRow(e, color)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun ExitRow(e: ExitInfo, color: Color) {
    Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
        Box(
            Modifier
                .size(36.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(color),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                e.exitNo,
                color = Color.White,
                style = MaterialTheme.typography.titleMedium.merge(Tnum),
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center,
                maxLines = 1,
            )
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            if (e.facilities.isNotEmpty()) Text(e.facilities.joinToString(", "), style = MaterialTheme.typography.bodyMedium)
            if (e.buses.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    e.buses.forEach { Pill(it, ModeColor.Bus, filled = false) }
                }
            }
        }
    }
}
