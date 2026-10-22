package com.eunho.tagohub.ui.timetable

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
        OdSelector(
            dep, arr, accent, "출발", "도착",
            onDep = { picking = true },
            onArr = { picking = false },
            onSwap = c::swap,
        )
        Spacer(Modifier.height(8.dp))
        DateStrip(date, c::setDate)
        (grades as? Load.Ok)?.value?.takeIf { it.isNotEmpty() }?.let { list ->
            GradeStrip(list, grade, accent, gradeAllLabel, c::setGrade)
        }
        Spacer(Modifier.height(4.dp))
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
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            itemsIndexed(trips) { i, t ->
                TripRow(t, accent, past = t.dep?.isBefore(now) == true, next = i == next)
            }
        }
    }
}

/**
 * 운행편 카드(노선도 방식): 출발 시각 ─●━━━●─ 도착 시각, 아래에 종류 칩·편명과 요금.
 * 지난 편은 흐리게, 다음 편은 모드색 테두리.
 */
@Composable
fun TripRow(t: Trip, accent: Color, past: Boolean, next: Boolean) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    val lineColor = accent
    Surface(
        shape = androidx.compose.foundation.shape.RoundedCornerShape(14.dp),
        color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        border = androidx.compose.foundation.BorderStroke(if (next) 2.dp else 1.dp, if (next) accent else MaterialTheme.colorScheme.outlineVariant),
        modifier = Modifier.fillMaxWidth().alpha(if (past) 0.45f else 1f),
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(t.dep?.let(TagoTime::hm) ?: "--:--", fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp)
                Column(Modifier.weight(1f).padding(horizontal = 10.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(Fmt.duration(t.dep, t.arr) ?: "", fontSize = 11.sp, color = variant)
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(8.dp).border(2.dp, lineColor, androidx.compose.foundation.shape.CircleShape))
                        Box(Modifier.weight(1f).height(3.dp).background(lineColor))
                        Box(Modifier.size(8.dp).background(lineColor, androidx.compose.foundation.shape.CircleShape).border(2.dp, lineColor, androidx.compose.foundation.shape.CircleShape))
                    }
                }
                val dayShift = if (t.dep != null && t.arr != null) ChronoUnit.DAYS.between(t.dep.toLocalDate(), t.arr.toLocalDate()) else 0
                Text((t.arr?.let(TagoTime::hm) ?: "--:--") + if (dayShift > 0) " +$dayShift" else "", fontSize = 22.sp, fontWeight = FontWeight.Black, letterSpacing = (-0.4).sp)
            }
            Row(Modifier.fillMaxWidth().padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (t.kind.isNotBlank()) {
                        Surface(shape = androidx.compose.foundation.shape.RoundedCornerShape(11.dp), color = accent) {
                            Text(t.kind, Modifier.padding(horizontal = 8.dp, vertical = 2.dp), fontSize = 11.sp, fontWeight = FontWeight.Black, color = Color.White)
                        }
                    }
                    t.number?.let { Text(it, fontSize = 12.sp, color = variant) }
                    if (next) Text("다음 출발", fontSize = 11.sp, color = MaterialTheme.colorScheme.secondary, fontWeight = FontWeight.Black)
                }
                if (t.prices.isEmpty()) {
                    Text("요금 정보 없음", fontSize = 12.sp, color = variant)
                } else {
                    Text(
                        t.prices.joinToString("  ") { (label, won) -> if (t.prices.size > 1) "$label ${Fmt.won(won)}" else Fmt.won(won) },
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Black,
                    )
                }
            }
        }
    }
}
