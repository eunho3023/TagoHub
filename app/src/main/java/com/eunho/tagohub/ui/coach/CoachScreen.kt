@file:OptIn(ExperimentalMaterial3Api::class)

package com.eunho.tagohub.ui.coach

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eunho.tagohub.core.Fmt
import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.core.toLoad
import com.eunho.tagohub.data.ExpArrival
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.data.Place
import com.eunho.tagohub.ui.common.LoadView
import com.eunho.tagohub.ui.common.MessageBox
import com.eunho.tagohub.ui.common.OdSelector
import com.eunho.tagohub.ui.common.Pill
import com.eunho.tagohub.ui.common.PlacePickerSheet
import com.eunho.tagohub.ui.common.ScreenTitle
import com.eunho.tagohub.ui.common.Tnum
import com.eunho.tagohub.ui.theme.ModeColor
import com.eunho.tagohub.ui.timetable.TimetableController
import com.eunho.tagohub.ui.timetable.TimetablePane
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import java.time.LocalTime

enum class CoachMode(val label: String) { Express("고속버스"), Suburb("시외버스"), Live("고속 실시간") }

class CoachViewModel : ViewModel() {
    private val repo = Graph.repo
    val mode = MutableStateFlow(CoachMode.Express)

    val express = TimetableController(
        scope = viewModelScope,
        placeLoader = { q -> repo.expTerminalPlaces(q) },
        gradeLoader = { repo.expGrades() },
        tripLoader = { d, a, date, g -> repo.expTrips(d.id, a.id, date, g?.id) },
        geo = com.eunho.tagohub.data.CoachTerminalGeo,
        // 출발 터미널을 고르면 도착은 거기서 가는 터미널만 (도착을 먼저 고르면 전체)
        connectedNames = { fixed, fixedIsDep -> if (fixedIsDep) repo.expDestinationNames(fixed.name) else null },
    )

    val suburb = TimetableController(
        scope = viewModelScope,
        placeLoader = { q -> repo.suburbTerminalPlaces(q) },
        gradeLoader = { repo.suburbGrades() },
        tripLoader = { d, a, date, g -> repo.suburbTrips(d.id, a.id, date, g?.id) },
        geo = com.eunho.tagohub.data.CoachTerminalGeo,
    )

    // ── 고속버스 실시간 도착 (ExpBusArrInfo: 별도 터미널 코드 체계) ──
    val liveDep = MutableStateFlow<Place?>(null)
    val liveArr = MutableStateFlow<Place?>(null)
    val live = MutableStateFlow<Load<List<ExpArrival>>>(Load.Idle)
    val liveUpdated = MutableStateFlow<LocalTime?>(null)

    fun setLiveDep(p: Place) {
        liveDep.value = p
        liveArr.value = null
        live.value = Load.Idle
    }

    fun setLiveArr(p: Place) {
        liveArr.value = p
        live.value = Load.Loading
    }

    suspend fun depPlaces(q: String) = repo.expArrDepPlaces(q)

    suspend fun arrPlaces(q: String): TagoResult<List<Place>> {
        val d = liveDep.value ?: return TagoResult.Error("출발 터미널을 먼저 선택하세요.")
        return repo.expArrDestPlaces(d.id, q)
    }

    suspend fun refreshLive() {
        val d = liveDep.value ?: return
        val a = liveArr.value ?: return
        val r = repo.expArrivals(d.id, a.id)
        // 갱신 실패 시 직전 목록은 유지
        if (r is TagoResult.Success || live.value !is Load.Ok) live.value = r.toLoad()
        if (r is TagoResult.Success) liveUpdated.value = LocalTime.now()
    }
}

@Composable
fun CoachScreen(vm: CoachViewModel = viewModel()) {
    // 지도에서 고른 터미널: 고속버스에서 먼저 찾고, 없으면 시외버스
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.eunho.tagohub.ui.ModeJump.request.collect { j ->
            if (j?.kind == com.eunho.tagohub.ui.ModeJump.Kind.Coach) {
                com.eunho.tagohub.ui.ModeJump.request.value = null
                if (vm.express.jumpTo(j.name, j.asOrigin)) vm.mode.value = CoachMode.Express
                else if (vm.suburb.jumpTo(j.name, j.asOrigin)) vm.mode.value = CoachMode.Suburb
            }
        }
    }
    val mode by vm.mode.collectAsStateWithLifecycle()
    val accent = if (mode == CoachMode.Suburb) ModeColor.Suburb else ModeColor.Coach

    Column(Modifier.fillMaxSize()) {
        SingleChoiceSegmentedButtonRow(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 12.dp)
        ) {
            CoachMode.entries.forEachIndexed { i, m ->
                SegmentedButton(
                    selected = mode == m,
                    onClick = { vm.mode.value = m },
                    shape = SegmentedButtonDefaults.itemShape(i, CoachMode.entries.size),
                ) { Text(m.label, maxLines = 1) }
            }
        }
        Spacer(Modifier.height(10.dp))
        when (mode) {
            CoachMode.Express -> TimetablePane(
                vm.express, ModeColor.Coach, "터미널", "전체 등급",
                "출발·도착 터미널을 고르면 배차 시간표가 표시됩니다.",
            )
            CoachMode.Suburb -> TimetablePane(
                vm.suburb, ModeColor.Suburb, "터미널", "전체 등급",
                "출발·도착 터미널을 고르면 배차 시간표가 표시됩니다.",
            )
            CoachMode.Live -> LivePane(vm)
        }
    }
}

@Composable
private fun LivePane(vm: CoachViewModel) {
    val dep by vm.liveDep.collectAsStateWithLifecycle()
    val arr by vm.liveArr.collectAsStateWithLifecycle()
    val state by vm.live.collectAsStateWithLifecycle()
    val updated by vm.liveUpdated.collectAsStateWithLifecycle()
    var picking by remember { mutableStateOf<Boolean?>(null) }
    var tick by remember { mutableIntStateOf(0) }

    LaunchedEffect(dep, arr, tick) {
        if (dep != null && arr != null) {
            while (true) {
                com.eunho.tagohub.core.AppState.awaitForeground()
                vm.refreshLive()
                delay(60_000)
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        OdSelector(
            dep, arr, ModeColor.Coach, "출발 터미널", "도착 터미널",
            onDep = { picking = true },
            onArr = { if (dep != null) picking = false },
            onSwap = null, // 도착 목록이 출발 터미널에 종속돼서 교환 불가
        )
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                updated?.let { "${TagoTime.clockText(it.hour * 60 + it.minute)} 갱신, 1분마다 자동 갱신" } ?: "",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            IconButton(onClick = { tick++ }, enabled = dep != null && arr != null) {
                Icon(Icons.Filled.Refresh, contentDescription = "새로고침")
            }
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        LoadView(
            state,
            onRetry = { tick++ },
            isEmpty = { it.isEmpty() },
            emptyText = "지금 이 구간을 운행 중인 버스가 없습니다.",
            idle = { MessageBox("출발·도착 터미널을 고르면 이미 출발한 버스의 현재 위치와 도착 예정 시각이 표시됩니다.") },
        ) { list ->
            LazyColumn(Modifier.fillMaxSize()) {
                items(list) {
                    ExpArrivalRow(it)
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        }
    }

    picking?.let { isDep ->
        PlacePickerSheet(
            title = if (isDep) "출발 터미널" else "도착 터미널",
            onDismiss = { picking = null },
            onPick = { p ->
                if (isDep) vm.setLiveDep(p) else vm.setLiveArr(p)
                picking = null
            },
            loader = { q -> if (isDep) vm.depPlaces(q) else vm.arrPlaces(q) },
        )
    }
}

@Composable
private fun ExpArrivalRow(a: ExpArrival) {
    val variant = MaterialTheme.colorScheme.onSurfaceVariant
    Row(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .alpha(if (a.arrived) 0.45f else 1f),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.width(72.dp)) {
            Text(
                a.depMinute?.let { TagoTime.clockText(it) } ?: "--:--",
                style = MaterialTheme.typography.titleLarge.merge(Tnum),
                fontWeight = FontWeight.Bold,
            )
            Text("출발", style = MaterialTheme.typography.labelSmall, color = variant)
        }
        Column(Modifier.weight(1f)) {
            Row(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) {
                a.grade?.let { Pill(it, ModeColor.Coach) }
                a.corp?.let { Text(it, style = MaterialTheme.typography.bodyMedium) }
            }
            Text(
                if (a.arrived) "도착 완료" else "현재 ${a.location ?: "위치 미상"}",
                style = MaterialTheme.typography.bodySmall,
                color = variant,
            )
        }
        Column(horizontalAlignment = Alignment.End) {
            if (!a.arrived) {
                Text(
                    a.remainMinutes?.let { Fmt.minutes(it) + " 후" } ?: a.remainText,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = ModeColor.Coach,
                )
            }
            a.arrPredict?.let {
                Text(
                    TagoTime.hm(it) + if (a.arrived) " 도착" else " 도착 예정",
                    style = MaterialTheme.typography.bodySmall.merge(Tnum),
                    color = variant,
                )
            }
        }
    }
}
