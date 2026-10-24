@file:OptIn(ExperimentalLayoutApi::class, ExperimentalMaterial3Api::class)

package com.eunho.tagohub.ui.subway

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.wrapContentHeight
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.material3.VerticalDivider
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
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.isSpecified
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
import com.eunho.tagohub.data.KricRouteStation
import com.eunho.tagohub.data.KricTrainStop
import com.eunho.tagohub.data.SeoulLines
import com.eunho.tagohub.data.SubwayArrival
import com.eunho.tagohub.data.SubwayDeparture
import com.eunho.tagohub.data.SubwayStation
import com.eunho.tagohub.ui.common.LineBadge
import com.eunho.tagohub.ui.common.LoadView
import com.eunho.tagohub.ui.common.MessageBox
import com.eunho.tagohub.ui.common.lineBadgeLabel
import com.eunho.tagohub.ui.common.Pill
import com.eunho.tagohub.ui.common.Tnum
import com.eunho.tagohub.ui.theme.BoardColor
import com.eunho.tagohub.ui.theme.LocalDarkTheme
import com.eunho.tagohub.ui.theme.ModeColor
import com.eunho.tagohub.ui.theme.subwayLineColor
import java.time.Duration
import java.time.LocalDateTime
import java.time.LocalTime
import java.util.Locale

private fun LocalTime.hhmm() = TagoTime.clockText(hour * 60 + minute)

/** 역 상세: 실시간 도착 / 시간표 / 출구정보. 부모가 높이를 정해준다. 글자는 앱 기본보다 조금(8%) 작게 해서 긴 역·방면 이름이 다 보이게 한다. */
@Composable
fun StationDetailPanel(
    d: StationDetail,
    c: StationDetailController,
    onClose: () -> Unit,
    refreshKey: Int = 0,
    onArrivalClick: ((SubwayArrival) -> Unit)? = null,
    onUseStation: ((Boolean) -> Unit)? = null,
    /** 같은 자리에 기차역이 있으면 그 역 이름 (기차 시간표로 바로 가는 버튼을 보임) */
    railStationName: String? = null,
    onRailJump: ((Boolean) -> Unit)? = null,
    /** 노선 띠의 이전·다음 역 이름을 누르면 그 역으로 */
    onStationName: ((String) -> Unit)? = null,
) {
    val base = MaterialTheme.typography
    val smaller = remember(base) { base.scaled(0.92f) }
    MaterialTheme(colorScheme = MaterialTheme.colorScheme, shapes = MaterialTheme.shapes, typography = smaller) {
        StationDetailBody(d, c, onClose, refreshKey, onArrivalClick, onUseStation, railStationName, onRailJump, onStationName)
    }
}

private fun TextStyle.scaled(f: Float): TextStyle = copy(
    fontSize = if (fontSize.isSpecified) fontSize * f else fontSize,
    lineHeight = if (lineHeight.isSpecified) lineHeight * f else lineHeight,
)

private fun Typography.scaled(f: Float): Typography = copy(
    headlineMedium = headlineMedium.scaled(f),
    headlineSmall = headlineSmall.scaled(f),
    titleLarge = titleLarge.scaled(f),
    titleMedium = titleMedium.scaled(f),
    titleSmall = titleSmall.scaled(f),
    bodyLarge = bodyLarge.scaled(f),
    bodyMedium = bodyMedium.scaled(f),
    bodySmall = bodySmall.scaled(f),
    labelLarge = labelLarge.scaled(f),
    labelMedium = labelMedium.scaled(f),
    labelSmall = labelSmall.scaled(f),
)

@Composable
private fun StationDetailBody(
    d: StationDetail,
    c: StationDetailController,
    onClose: () -> Unit,
    refreshKey: Int = 0,
    onArrivalClick: ((SubwayArrival) -> Unit)? = null,
    onUseStation: ((Boolean) -> Unit)? = null,
    /** 같은 자리에 기차역이 있으면 그 역 이름 (기차 시간표로 바로 가는 버튼을 보임) */
    railStationName: String? = null,
    onRailJump: ((Boolean) -> Unit)? = null,
    onStationName: ((String) -> Unit)? = null,
) {
    val tab by d.tab.collectAsStateWithLifecycle()
    val lines = d.group.displayLines
    val color = subwayLineColor(lines.first())
    val stationName = d.group.name.let { if (it.endsWith("역")) it else "${it}역" }
    val operatorLabel = stationOperatorLabel(d.group.stations.firstOrNull()?.operator.orEmpty())

    LaunchedEffect(d, refreshKey) { c.runArrivals(d) }

    val selectedLine by d.line.collectAsStateWithLifecycle()
    val route by d.kricRoute.collectAsStateWithLifecycle()
    val codes by d.kricCodes.collectAsStateWithLifecycle()
    // 이전·다음 역(노선 띠에 표시)을 알려면 노선 역 목록이 필요함 — 노선이 정해지면 한 번 받아 둠
    LaunchedEffect(selectedLine?.id) {
        if (selectedLine != null && d.kricRoute.value is Load.Idle) c.loadKricRoute(d)
    }
    val neighbors = remember(route, codes) {
        (route as? Load.Ok)?.value?.sortedBy { it.stationOrder }?.let { all ->
            val i = all.indexOfFirst { it.stationCode == codes?.stationCode }
            if (i >= 0) Triple(all.getOrNull(i - 1)?.stationName, all[i].stationName, all.getOrNull(i + 1)?.stationName) else null
        }
    }
    val stripColor = subwayLineColor(selectedLine?.routeName ?: lines.first())

    Column(Modifier.fillMaxSize()) {
        // 머리: 큰 역 이름 + 호선 알약(환승역은 여러 개), 오른쪽에 회색 원 안의 닫기
        Row(Modifier.padding(start = 20.dp, end = 12.dp, top = 4.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stationName, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                if (operatorLabel.isNotBlank()) {
                    Text(operatorLabel, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            FlowRow(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.End), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                lines.forEach {
                    Text(
                        it,
                        Modifier.background(subwayLineColor(it), CircleShape).padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White,
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.ExtraBold,
                    )
                }
            }
            Spacer(Modifier.width(10.dp))
            Surface(onClick = onClose, shape = CircleShape, color = MaterialTheme.colorScheme.surfaceVariant, modifier = Modifier.size(48.dp)) {
                Box(contentAlignment = Alignment.Center) { Icon(Icons.Filled.Close, contentDescription = "닫기") }
            }
        }
        // 노선 띠: 이전 역 ‹  [ 이 역 ]  › 다음 역
        neighbors?.let { (prev, cur, next) ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
                    .heightIn(min = 48.dp)
                    .background(stripColor, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(prev?.let { "‹ " + it } ?: "", Modifier.weight(1f).heightIn(min = 48.dp).then(if (prev != null && onStationName != null) Modifier.clickable { onStationName(prev) } else Modifier).wrapContentHeight(Alignment.CenterVertically), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Spacer(Modifier.weight(1.1f))
                    Text(next?.let { it + " ›" } ?: "", Modifier.weight(1f).heightIn(min = 48.dp).then(if (next != null && onStationName != null) Modifier.clickable { onStationName(next) } else Modifier).wrapContentHeight(Alignment.CenterVertically), color = Color.White, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis, textAlign = TextAlign.End)
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    border = androidx.compose.foundation.BorderStroke(3.dp, stripColor),
                ) {
                    Row(Modifier.heightIn(min = 44.dp).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically) {
                        LineBadge(lineBadgeLabel(selectedLine?.routeName ?: lines.first()), stripColor, size = 26.dp)
                        Spacer(Modifier.width(8.dp))
                        Text(cur, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.ExtraBold, maxLines = 2)
                    }
                }
            }
        }
        // 실시간 · 시간표 · 노선도 · 출구 · 편의정보: 한 줄 (좁으면 옆으로 밀어서)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp).horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Row(
                Modifier.background(MaterialTheme.colorScheme.surfaceVariant, CircleShape).padding(4.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
            ) {
                listOf(
                    StationTab.Live to "실시간", StationTab.Timetable to "시간표", StationTab.Route to "노선도",
                    StationTab.Exits to "출구", StationTab.Facilities to "편의정보",
                ).forEach { (t, label) ->
                    val on = tab == t
                    Surface(
                        onClick = { c.setTab(d, t) },
                        shape = CircleShape,
                        color = if (on) MaterialTheme.colorScheme.surface else Color.Transparent,
                        border = if (on) androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outline) else null,
                    ) {
                        Box(Modifier.heightIn(min = 40.dp).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                            Text(
                                label,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = if (on) FontWeight.ExtraBold else FontWeight.Medium,
                                color = if (on) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 1,
                            )
                        }
                    }
                }
            }
        }
        Box(Modifier.weight(1f)) {
            when (tab) {
                StationTab.Live -> LiveTab(d, c, onArrivalClick)
                StationTab.Timetable -> TimetableTab(d, c)
                StationTab.Route -> RouteTab(d, c)
                StationTab.Exits -> ExitsTab(d, c)
                StationTab.Facilities -> FacilitiesTab(d)
            }
        }
        // 아래: 파란 [출발][도착] 알약 (이 역을 길찾기 출발지·도착지로)
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // 같은 자리에 기차역이 있으면 기차 시간표로 바로 가기 (왼쪽, 연한 알약)
            if (railStationName != null && onRailJump != null) {
                Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(true to "기차 출발", false to "기차 도착").forEach { (asOrigin, label) ->
                        Surface(
                            onClick = { onRailJump(asOrigin) },
                            shape = CircleShape,
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.primary,
                        ) {
                            Box(Modifier.heightIn(min = 48.dp).padding(horizontal = 14.dp), contentAlignment = Alignment.Center) {
                                Text(label, fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                            }
                        }
                    }
                }
            }
            Button(onClick = { onUseStation?.invoke(true) }, enabled = onUseStation != null, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("출발", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
            }
            Button(onClick = { onUseStation?.invoke(false) }, enabled = onUseStation != null, shape = CircleShape, modifier = Modifier.heightIn(min = 48.dp)) {
                Text("도착", fontWeight = FontWeight.ExtraBold, style = MaterialTheme.typography.titleSmall)
            }
        }
    }
}

private fun stationOperatorLabel(operator: String): String = when {
    operator.contains("대전") -> "대전 지하철"
    operator.contains("부산") -> "부산 지하철"
    operator.contains("대구") -> "대구 지하철"
    operator.contains("광주") -> "광주 지하철"
    operator.contains("인천") -> "인천 지하철"
    operator.contains("서울") -> "서울 지하철"
    operator.contains("코레일") || operator.contains("한국철도") -> "수도권 전철"
    else -> operator
}

@Composable
private fun FacilitiesTab(d: StationDetail) {
    val facilities by d.facilities.collectAsStateWithLifecycle()
    if (facilities.isEmpty()) {
        MessageBox("등록된 역 편의정보가 없습니다.")
        return
    }
    LazyColumn(Modifier.fillMaxSize()) {
        facilities.groupBy { it.category }.forEach { (category, records) ->
            item(key = "facility-$category") {
                Text(
                    category,
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = subwayLineColor(d.group.displayLines.first()),
                )
            }
            items(records) { record ->
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("${record.station} · ${record.line}", style = MaterialTheme.typography.labelMedium)
                    Text(record.detail, style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

// ───────────────────────── 실시간 도착 ─────────────────────────

@Composable
private fun LiveTab(d: StationDetail, c: StationDetailController, onArrivalClick: ((SubwayArrival) -> Unit)?) {
    val state by d.arrivals.collectAsStateWithLifecycle()
    val updated by d.arrivalsUpdated.collectAsStateWithLifecycle()
    val day by d.dayType.collectAsStateWithLifecycle()
    val upDown by d.upDown.collectAsStateWithLifecycle()
    val schedule by d.schedule.collectAsStateWithLifecycle()
    val selectedLine by d.line.collectAsStateWithLifecycle()
    val lines by d.tagoLines.collectAsStateWithLifecycle()
    val availableLines = (lines as? Load.Ok)?.value.orEmpty()
    val color = subwayLineColor(selectedLine?.routeName ?: d.group.displayLines.first())

    Column(Modifier.fillMaxSize()) {
        StationInfoCard(d)
        if (!d.realtime) {
            Text(
                "실시간 도착 대신 오늘 운행 시간표를 표시합니다.",
                Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (availableLines.size > 1) {
                LazyRow(
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(availableLines) { option ->
                        FilterChip(
                            selected = selectedLine?.id == option.id,
                            onClick = { c.selectLine(d, option) },
                            label = { Text(option.routeName) },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = subwayLineColor(option.routeName),
                                selectedLabelColor = Color.White,
                            ),
                        )
                    }
                }
            }
            SingleChoiceSegmentedButtonRow(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 4.dp)
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
                emptyText = "이 방향의 오늘 시간표가 없습니다.",
            ) { departures ->
                TimetableBoard(departures, color, live = day == DayType.today())
            }
        } else {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    "도착·출발 정보",
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                )
                updated?.let {
                    Text(
                        "${it.hhmm()} 갱신",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            LoadView(state, isEmpty = { it.isEmpty() }, emptyText = "현재 도착·출발 예정인 열차가 없습니다.") { list ->
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
                            Row(Modifier.padding(start = 16.dp, top = 10.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                                LineBadge(lineBadgeLabel(name), subwayLineColor(name), size = 26.dp)
                                Spacer(Modifier.width(8.dp))
                                Text(name, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Black)
                            }
                        }
                        rows.groupBy { it.upDown.ifBlank { "방향 정보 없음" } }.forEach { (direction, directionRows) ->
                            item(key = "d$lineId$direction") {
                                Text(
                                    direction,
                                    Modifier.padding(start = 16.dp, top = 6.dp, bottom = 2.dp),
                                    style = MaterialTheme.typography.labelLarge,
                                    color = subwayLineColor(name),
                                )
                            }
                            items(directionRows) { a ->
                                ArrivalRow(a, now, onClick = onArrivalClick?.let { cb -> { cb(a) } })
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StationInfoCard(d: StationDetail) {
    val facilities by d.facilities.collectAsStateWithLifecycle()
    val address = facilities.firstOrNull { it.category == "주소" }?.detail
    val structure = facilities.firstOrNull { it.category == "역 구조" }?.detail
    Surface(
        Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            address?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            structure?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (address == null && structure == null) {
                Text(
                    d.group.displayLines.joinToString(" · "),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
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
        // 호선 원형 배지 (노선도처럼)
        val lineName = SeoulLines.byId[a.lineId]?.apiName
        if (lineName != null) {
            LineBadge(lineBadgeLabel(lineName), subwayLineColor(lineName), size = 36.dp)
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                a.lineText.ifBlank { a.dest?.let { "${it}행" } ?: "" } + if (onClick != null) "  ›" else "",
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val sub = listOfNotNull(
                a.upDown.ifBlank { null },
                a.message.takeIf { (a.seconds ?: 0) > 0 && it.isNotBlank() },
                a.current?.let { "현재 $it" },
            ).joinToString("   ")
            if (sub.isNotEmpty()) {
                Text(sub, style = MaterialTheme.typography.bodySmall, color = variant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.End) {
            val main = arrivalMain(a, now)
            Text(
                main,
                style = MaterialTheme.typography.titleLarge.merge(Tnum),
                fontWeight = FontWeight.Black,
                // 도착 시간은 빨간 글씨 (정류소·지하철 도착 목록 공통)
                color = if (LocalDarkTheme.current) Color(0xFFFF8A80) else Color(0xFFD7262E),
            )
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
        val source by d.timetableSource.collectAsStateWithLifecycle()
        val schedule by d.schedule.collectAsStateWithLifecycle()
        val kricSchedule by d.kricTimetable.collectAsStateWithLifecycle()
        val lineColor = subwayLineColor(line.routeName)
        Column(Modifier.fillMaxSize()) {
            SingleChoiceSegmentedButtonRow(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp)
            ) {
                listOf(SubwayTimetableSource.Tago to "역별 시간표", SubwayTimetableSource.Kric to "열차별 시각표")
                    .forEachIndexed { i, (option, label) ->
                        SegmentedButton(
                            selected = source == option,
                            onClick = { c.setTimetableSource(d, option) },
                            shape = SegmentedButtonDefaults.itemShape(i, 2),
                        ) { Text(label) }
                    }
            }
            LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(DayType.entries) { t ->
                    FilterChip(selected = t == day, onClick = { c.setDay(d, t) }, label = { Text(t.label) })
                }
            }
            if (source == SubwayTimetableSource.Tago) {
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
            }
            if (source == SubwayTimetableSource.Tago) {
                LoadView(
                    schedule,
                    onRetry = { c.loadSchedule(d) },
                    isEmpty = { it.isEmpty() },
                    emptyText = "이 조건의 시간표가 없습니다. 종착역이면 반대 방향을 확인하세요.",
                ) { list -> TimetableBoard(list, lineColor, live = day == DayType.today()) }
            } else {
                LoadView(
                    kricSchedule,
                    onRetry = { c.loadKricTimetable(d) },
                    isEmpty = { it.isEmpty() },
                    emptyText = "이 역의 열차별 운행시각 정보가 없습니다.",
                ) { list -> KricTimetableBoard(list, lineColor) }
            }
        }
    }
}

@Composable
private fun KricTimetableBoard(list: List<KricTrainStop>, lineColor: Color) {
    LazyColumn(Modifier.fillMaxSize()) {
        item {
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("열차번호", Modifier.weight(1f), style = MaterialTheme.typography.labelLarge, color = lineColor)
                Text("도착", Modifier.width(64.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
                Text("출발", Modifier.width(64.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelLarge)
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        items(list, key = { "${it.trainNo}_${it.arrivalTime}_${it.departureTime}" }) { stop ->
            Row(
                Modifier.padding(horizontal = 16.dp, vertical = 9.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    stop.trainNo.ifBlank { "열차" },
                    Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Medium,
                )
                Text(
                    stop.arrivalTime?.let(TagoTime::clockText).orEmpty().ifBlank { "—" },
                    Modifier.width(64.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyLarge.merge(Tnum),
                )
                Text(
                    stop.departureTime?.let(TagoTime::clockText).orEmpty().ifBlank { "—" },
                    Modifier.width(64.dp),
                    textAlign = TextAlign.End,
                    style = MaterialTheme.typography.bodyLarge.merge(Tnum),
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
    }
}

@Composable
private fun RouteTab(d: StationDetail, c: StationDetailController) {
    LinePicker(d, c) { line ->
        val route by d.kricRoute.collectAsStateWithLifecycle()
        val codes by d.kricCodes.collectAsStateWithLifecycle()
        val selectedStationCode = remember(line, codes) { codes?.stationCode }
        LaunchedEffect(line.id) {
            if (d.kricRoute.value !is Load.Ok) c.loadKricRoute(d)
        }
        LoadView(
            route,
            onRetry = { c.loadKricRoute(d) },
            isEmpty = { it.isEmpty() },
            emptyText = "이 노선의 역 정보가 없습니다.",
        ) { stations ->
            LazyColumn(Modifier.fillMaxSize()) {
                items(stations, key = { "${it.stationOrder}_${it.stationCode}" }) { station ->
                    KricRouteRow(station, station.stationCode == selectedStationCode)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }
}

@Composable
private fun KricRouteRow(station: KricRouteStation, selected: Boolean) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (selected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            station.stationOrder.toString(),
            Modifier.width(40.dp),
            style = MaterialTheme.typography.bodyMedium.merge(Tnum),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            station.stationName.ifBlank { "역명 정보 없음" },
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        )
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
