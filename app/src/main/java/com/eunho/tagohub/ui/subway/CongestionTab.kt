package com.eunho.tagohub.ui.subway

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.eunho.tagohub.data.StationName
import com.eunho.tagohub.data.SubwayCongestion
import com.eunho.tagohub.ui.common.MessageBox
import com.eunho.tagohub.ui.theme.subwayLineColor
import java.time.LocalDate
import java.time.LocalTime

/** 혼잡도 색: 정원 대비 % 를 초록→노랑→주황→빨강으로 */
internal fun congestionColor(percent: Int): Color = when {
    percent < 0 -> Color(0xFFB0BEC5)
    percent < 60 -> Color(0xFF2E9E4F)
    percent < 100 -> Color(0xFFF9A825)
    percent < 130 -> Color(0xFFEF6C00)
    else -> Color(0xFFD32F2F)
}

/**
 * 역 혼잡도: 요일·노선을 고르면 방향별로 "지금 시간대"의 혼잡도와 하루 흐름(30분 막대)을 보여 준다.
 * 서울교통공사 1~8호선 자료(과거 평균)라서 다른 노선·지역 역은 "자료 없음".
 */
@Composable
internal fun CongestionTab(d: StationDetail) {
    val selected by d.line.collectAsStateWithLifecycle()
    val stationName = d.group.name
    val candidates = d.group.displayLines
    val available = remember(stationName, candidates) { SubwayCongestion.linesOf(stationName, candidates) }
    if (available.isEmpty()) {
        MessageBox("이 역의 혼잡도 자료가 없습니다. (서울교통공사 1~8호선 역만 제공됩니다)")
        return
    }
    var day by remember { mutableStateOf(SubwayCongestion.Day.of(LocalDate.now().dayOfWeek)) }
    val selectedName = selected?.routeName
    var lineChoice by remember(available) {
        mutableStateOf(available.firstOrNull { l -> selectedName != null && StationName.sameLine(selectedName, l) } ?: available.first())
    }
    val series = remember(day, lineChoice, stationName) { SubwayCongestion.forStation(day, lineChoice, stationName) }
    val nowSlot = remember { SubwayCongestion.slotOf(LocalTime.now()) }
    val isToday = day == SubwayCongestion.Day.of(LocalDate.now().dayOfWeek)

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 12.dp)) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            SubwayCongestion.Day.entries.forEach { dd ->
                FilterChip(selected = day == dd, onClick = { day = dd }, label = { Text(dd.label) })
            }
        }
        if (available.size > 1) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 2.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                available.forEach { l ->
                    FilterChip(
                        selected = lineChoice == l, onClick = { lineChoice = l }, label = { Text(l) },
                        colors = FilterChipDefaults.filterChipColors(selectedContainerColor = subwayLineColor(l), selectedLabelColor = Color.White),
                    )
                }
            }
        }
        series.forEach { s ->
            val now = s.values.getOrNull(nowSlot) ?: -1
            Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(s.direction, fontWeight = FontWeight.ExtraBold, fontSize = 16.sp, modifier = Modifier.padding(end = 10.dp))
                    if (isToday) {
                        Text(
                            "${SubwayCongestion.slotLabel(nowSlot)} 무렵 ${if (now >= 0) "$now%" else "-"} · ${SubwayCongestion.describe(now)}",
                            color = congestionColor(now), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                        )
                    } else {
                        val peak = s.values.withIndex().maxByOrNull { it.value }
                        if (peak != null && peak.value >= 0) {
                            Text(
                                "가장 붐비는 때 ${SubwayCongestion.slotLabel(peak.index)} ${peak.value}%",
                                color = congestionColor(peak.value), fontWeight = FontWeight.Bold, fontSize = 14.sp,
                            )
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                CongestionBars(s.values, if (isToday) nowSlot else -1)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    listOf("06", "09", "12", "15", "18", "21", "24").forEach {
                        Text(it, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
        Text(
            "정원 대비 승객 비율(%)의 평균이에요. 100%면 정원만큼 탄 상태이고, 실시간 정보는 아닙니다. 서울교통공사 제공(2026-06-30 기준).",
            Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun CongestionBars(values: IntArray, nowSlot: Int) {
    val outline = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(72.dp)) {
        val n = values.size
        val gap = 2.dp.toPx()
        val w = (size.width - gap * (n - 1)) / n
        val cap = 160f   // 이 값을 막대 맨 위로
        for (i in 0 until n) {
            val v = values[i]
            val h = if (v < 0) 2.dp.toPx() else (v.coerceAtMost(cap.toInt()) / cap * size.height).coerceAtLeast(2.dp.toPx())
            val x = i * (w + gap)
            drawRoundRect(congestionColor(v), Offset(x, size.height - h), Size(w, h), CornerRadius(2.dp.toPx()))
            if (i == nowSlot) drawRect(outline, Offset(x - 1.dp.toPx(), 0f), Size(w + 2.dp.toPx(), 1.5.dp.toPx()))
        }
    }
}
