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
import androidx.compose.material3.Icon
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
) {
    val dep by c.dep.collectAsStateWithLifecycle()
    val arr by c.arr.collectAsStateWithLifecycle()
    val date by c.date.collectAsStateWithLifecycle()
    val grades by c.grades.collectAsStateWithLifecycle()
    val grade by c.grade.collectAsStateWithLifecycle()
    val trips by c.trips.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Boolean?>(null) } // true 출발, false 도착

    LaunchedEffect(c) { c.ensureGrades() }

    Column(Modifier.fillMaxSize()) {
        // 화면 제목 머리띠 바로 아래로 이어지는 같은 색 띠 안에 출발·도착 (철도 예매 앱처럼)
        Surface(color = accent, contentColor = Color.White) {
            Column(Modifier.padding(bottom = 12.dp)) {
                OdSelector(
                    dep, arr, accent, "출발", "도착",
                    onDep = { picking = true },
                    onArr = { picking = false },
                    onSwap = c::swap,
                )
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
        TripList(trips, accent, idleText, onRetry = c::search)
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
        )
    }
}

/** 출발 안내판 형태의 운행편 목록. 이미 떠난 편은 흐리게, 다음 편으로 자동 스크롤. */
@Composable
fun TripList(state: Load<List<Trip>>, accent: Color, idleText: String, onRetry: () -> Unit) {
    LoadView(
        state,
        onRetry = onRetry,
        isEmpty = { it.isEmpty() },
        emptyText = "해당 날짜에 운행편이 없습니다.",
        idle = { MessageBox(idleText) },
    ) { trips ->
        val now = remember(trips) { LocalDateTime.now() }
        val next = remember(trips) { trips.indexOfFirst { t -> t.dep?.isBefore(now) == false } }
        val listState = rememberLazyListState()
        LaunchedEffect(trips) { if (next > 0) listState.scrollToItem(next) }
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.surfaceVariant)) {
            itemsIndexed(trips) { i, t ->
                TripRow(t, accent, past = t.dep?.isBefore(now) == true, next = i == next)
            }
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
