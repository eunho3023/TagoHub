package com.eunho.tagohub.ui.timetable

import androidx.compose.foundation.background
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
        LazyColumn(state = listState, modifier = Modifier.fillMaxSize()) {
            itemsIndexed(trips) { i, t ->
                TripRow(t, accent, past = t.dep?.isBefore(now) == true, next = i == next)
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
            }
        }
    }
}

@Composable
fun TripRow(t: Trip, accent: Color, past: Boolean, next: Boolean) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .background(if (next) accent.copy(alpha = 0.08f) else Color.Transparent)
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (past) 0.4f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.widthIn(min = 64.dp)) {
            Text(
                t.dep?.let(TagoTime::hm) ?: "--:--",
                style = MaterialTheme.typography.titleLarge.merge(Tnum),
                fontWeight = FontWeight.Bold,
            )
            val dayShift = if (t.dep != null && t.arr != null) ChronoUnit.DAYS.between(t.dep.toLocalDate(), t.arr.toLocalDate()) else 0
            Text(
                "→ " + (t.arr?.let(TagoTime::hm) ?: "--:--") + if (dayShift > 0) " +$dayShift" else "",
                style = MaterialTheme.typography.bodyMedium.merge(Tnum),
                color = variant,
            )
        }
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                if (t.kind.isNotBlank()) Pill(t.kind, accent)
                t.number?.let { Text(it, style = MaterialTheme.typography.bodyMedium.merge(Tnum), fontWeight = FontWeight.Medium) }
            }
            Fmt.duration(t.dep, t.arr)?.let {
                Text("소요 $it", style = MaterialTheme.typography.bodySmall, color = variant)
            }
            if (next) Text("다음 출발", style = MaterialTheme.typography.labelSmall, color = accent, fontWeight = FontWeight.Bold)
        }
        Column(horizontalAlignment = Alignment.End) {
            if (t.prices.isEmpty()) {
                Text("요금 정보 없음", style = MaterialTheme.typography.bodySmall, color = variant)
            } else {
                t.prices.forEach { (label, won) ->
                    Text(
                        if (t.prices.size > 1) "$label ${Fmt.won(won)}" else Fmt.won(won),
                        style = MaterialTheme.typography.bodyMedium.merge(Tnum),
                        fontWeight = FontWeight.Medium,
                    )
                }
            }
        }
    }
}
