package com.eunho.tagohub.ui.timetable

import androidx.compose.material3.Text
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
    )
}

class AirViewModel : ViewModel() {
    private val repo = Graph.repo
    val table = TimetableController(
        scope = viewModelScope,
        placeLoader = { q -> repo.airportPlaces(q) },
        gradeLoader = { repo.airlines() },
        tripLoader = { d, a, date, g -> repo.flights(d.id, a.id, date, g?.id) },
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
        ScreenTitle("기차", ModeColor.Train, subtitle = "KTX·SRT·ITX·무궁화 시간표와 운임")
        TimetablePane(
            vm.table, ModeColor.Train,
            pickerNoun = "역",
            gradeAllLabel = "전체 차종",
            idleText = "출발역과 도착역을 고르면 시간표가 표시됩니다.\n처음 한 번은 전국 역 목록을 받느라 몇 초 걸립니다.",
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
        ScreenTitle("항공", ModeColor.Air, subtitle = if (intl) "한국공항공사 국제선 운항 스케줄 (인천공항 제외)" else "공항 간 운항 스케줄과 운임")
        androidx.compose.foundation.layout.Row(Modifier.padding(horizontal = 16.dp), horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
            androidx.compose.material3.FilterChip(selected = !intl, onClick = { vm.intlMode.value = false }, label = { Text("국내선") })
            androidx.compose.material3.FilterChip(selected = intl, onClick = { vm.intlMode.value = true }, label = { Text("국제선") })
        }
        if (intl) {
            TimetablePane(
                vm.intl, ModeColor.Air,
                pickerNoun = "공항",
                gradeAllLabel = "전체 항공사",
                idleText = "출발 공항과 도착 공항을 고르면 그날 운항편이 표시됩니다. 한쪽은 김포·김해·제주·청주·대구·무안·양양이어야 합니다.",
            )
        } else {
            TimetablePane(
                vm.table, ModeColor.Air,
                pickerNoun = "공항",
                gradeAllLabel = "전체 항공사",
                idleText = "출발 공항과 도착 공항을 고르면 운항편이 표시됩니다.",
            )
        }
    }
}
