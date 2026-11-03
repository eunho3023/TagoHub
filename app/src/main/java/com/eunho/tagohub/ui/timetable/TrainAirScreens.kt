package com.eunho.tagohub.ui.timetable

import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.setValue
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.ui.common.ScreenTitle
import com.eunho.tagohub.ui.theme.ModeColor

class TrainViewModel : ViewModel() {
    private val repo = Graph.repo

    val table = TimetableController(
        scope = viewModelScope,
        placeLoader = { q -> repo.trainStationPlaces(q) },
        gradeLoader = { repo.trainKinds() },
        tripLoader = { d, a, date, g -> repo.trainTrips(d.id, a.id, date, g?.id) },
        browseLoader = { repo.trainStationsAll() },
        transferLoader = { d, a, date, wait -> repo.trainTransfers(d.id, a.id, date, wait) },
        stopsLoader = { t, date -> repo.trainStops(t, date) },
        historyLoader = { t -> repo.trainHistory(t) },
        cachedTrips = { d, a, date, g -> repo.cachedTrainTrips(d.id, a.id, date, g?.id) },
        delayLoader = { trips -> repo.trainDelayNotes(trips) },
        geo = com.eunho.tagohub.data.TrainPlaceGeo,
    )
}

class AirViewModel : ViewModel() {
    /**
     * 국내선: 앱에 든 한국공항공사 스케줄 파일(CSV)을 우선 쓴다 — 공항 목록·항공사·운항편 모두 파일에서.
     * 파일 기간(2026-10-24)이 지난 날짜만 실시간 운항 API 로 받고, 안 되면 마지막 한 주 요일 패턴의 "예상"을 보인다.
     */
    val table = TimetableController(
        scope = viewModelScope,
        placeLoader = { q -> com.eunho.tagohub.core.TagoResult.Success(com.eunho.tagohub.data.PlaceFilter.filter(com.eunho.tagohub.data.AirSchedule.domesticAirports(), q)) },
        gradeLoader = { com.eunho.tagohub.core.TagoResult.Success(com.eunho.tagohub.data.AirSchedule.domesticAirlines()) },
        tripLoader = { d, a, date, g ->
            val all = com.eunho.tagohub.data.KacIntlClient.withStatus(d.id, a.id, date,
                com.eunho.tagohub.data.KacIntlClient.fileOrLive(com.eunho.tagohub.data.AirSchedule.trips(d.id, a.id, date)) {
                    com.eunho.tagohub.data.KacIntlClient.domesticTrips(d.id, a.id, date)
                })
            when (all) {
                is com.eunho.tagohub.core.TagoResult.Success -> com.eunho.tagohub.core.TagoResult.Success(all.data.filter { g == null || it.kind.startsWith(g.name) })
                else -> all
            }
        },
        geo = com.eunho.tagohub.data.AirportGeo,
    )

    /** 국내선 / 국제선 */
    val intlMode = kotlinx.coroutines.flow.MutableStateFlow(false)

    /** 국제선: 한국공항공사 운항 스케줄 (김포·김해·제주·청주·대구·무안·양양 ↔ 해외) */
    val intl = TimetableController(
        scope = viewModelScope,
        placeLoader = { q -> com.eunho.tagohub.core.TagoResult.Success(com.eunho.tagohub.data.KacIntl.places(q)) },
        gradeLoader = null,
        tripLoader = { d, a, date, _ -> com.eunho.tagohub.data.KacIntlClient.trips(d.id, a.id, date) },
    )
}

@Composable
fun TrainScreen(vm: TrainViewModel = viewModel()) {
    // 지도에서 고른 역·공항으로 출발지·도착지 채우기
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.eunho.tagohub.ui.ModeJump.request.collect { j ->
            if (j?.kind == com.eunho.tagohub.ui.ModeJump.Kind.Train) {
                com.eunho.tagohub.ui.ModeJump.request.value = null
                vm.table.jumpTo(j.name, j.asOrigin)
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        TimetablePane(
            vm.table, ModeColor.Train,
            pickerNoun = "역",
            gradeAllLabel = "전체 차종",
            idleText = "출발역과 도착역을 고르면 시간표가 표시돼요. 직통이 없으면 환승 경로를 찾고, 열차마다 정차역을 볼 수 있어요.\n처음 한 번은 전국 역 목록을 받느라 몇 초 걸립니다.",
        )
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
fun AirScreen(vm: AirViewModel = viewModel()) {
    // 지도에서 고른 역·공항으로 출발지·도착지 채우기
    androidx.compose.runtime.LaunchedEffect(Unit) {
        com.eunho.tagohub.ui.ModeJump.request.collect { j ->
            if (j?.kind == com.eunho.tagohub.ui.ModeJump.Kind.Air) {
                com.eunho.tagohub.ui.ModeJump.request.value = null
                // 국내선 공항에서 먼저 찾고, 없으면 국제선
                if (vm.table.jumpTo(j.name, j.asOrigin)) vm.intlMode.value = false
                else if (vm.intl.jumpTo(j.name, j.asOrigin)) vm.intlMode.value = true
            }
        }
    }
    Column(Modifier.fillMaxSize()) {
        val intl by vm.intlMode.collectAsStateWithLifecycle()
        var showInfo by androidx.compose.runtime.remember { androidx.compose.runtime.mutableStateOf(false) }
        androidx.compose.foundation.layout.Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 16.dp, top = 12.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(selected = !intl, onClick = { vm.intlMode.value = false }, label = { Text("국내선") })
            androidx.compose.material3.FilterChip(selected = intl, onClick = { vm.intlMode.value = true }, label = { Text("국제선") })
            androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
            androidx.compose.material3.TextButton(onClick = { showInfo = true }) { Text("공항 정보") }
        }
        if (showInfo) {
            // 출발 공항(없으면 도착 공항)이 한국공항공사 공항이면 그 공항부터
            val table = if (intl) vm.intl else vm.table
            val picked = table.dep.value?.name ?: table.arr.value?.name
            AirportInfoSheet(initial = picked, onDismiss = { showInfo = false })
        }
        if (intl) {
            TimetablePane(
                vm.intl, ModeColor.Air,
                recentKey = "air-intl",
                pickerNoun = "공항",
                gradeAllLabel = "전체 항공사",
                idleText = "출발 공항과 도착 공항을 고르면 그날 운항편이 표시돼요. 한쪽은 김포·김해·제주·청주·대구·무안·양양이어야 해요.",
            )
        } else {
            TimetablePane(
                vm.table, ModeColor.Air,
                pickerNoun = "공항",
                gradeAllLabel = "전체 항공사",
                idleText = "출발 공항과 도착 공항을 고르면 운항편이 표시돼요.",
            )
        }
    }
}
