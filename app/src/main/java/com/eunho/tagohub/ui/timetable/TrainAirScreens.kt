package com.eunho.tagohub.ui.timetable

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
}

@Composable
fun TrainScreen(vm: TrainViewModel = viewModel()) {
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

@Composable
fun AirScreen(vm: AirViewModel = viewModel()) {
    Column(Modifier.fillMaxSize()) {
        ScreenTitle("국내선 항공", ModeColor.Air, subtitle = "공항 간 운항 스케줄과 운임")
        TimetablePane(
            vm.table, ModeColor.Air,
            pickerNoun = "공항",
            gradeAllLabel = "전체 항공사",
            idleText = "출발 공항과 도착 공항을 고르면 운항편이 표시됩니다.",
        )
    }
}
