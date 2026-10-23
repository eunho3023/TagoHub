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
import kotlinx.coroutines.launch
import androidx.lifecycle.viewmodel.compose.viewModel
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.ui.common.ScreenTitle
import com.eunho.tagohub.ui.theme.ModeColor

class TrainViewModel : ViewModel() {
    private val repo = Graph.repo

    /** 내 위치에서 가까운 역 두 곳 (위치를 알 때) */
    val nearby = kotlinx.coroutines.flow.MutableStateFlow<List<QuickPick>>(emptyList())

    fun loadNearby(lat: Double, lng: Double) {
        viewModelScope.launch {
            val stations = (repo.trainStationsAll() as? com.eunho.tagohub.core.TagoResult.Success)?.data ?: return@launch
            nearby.value = com.eunho.tagohub.data.TrainStationGeo.nearest(stations, lat, lng, 2)
                .map { (p, m) -> QuickPick(p, if (m >= 1000) "%.1fkm".format(m / 1000.0) else "${m.toInt()}m") }
        }
    }
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
    private val repo = Graph.repo
    val table = TimetableController(
        scope = viewModelScope,
        placeLoader = { q -> repo.airportPlaces(q) },
        gradeLoader = { repo.airlines() },
        tripLoader = { d, a, date, g -> domesticTrips(d, a, date, g) },
        geo = com.eunho.tagohub.data.AirportGeo,
    )

    /**
     * 국내선: 오늘·내일은 TAGO 실시간 운항 정보(운임 포함), 그 밖의 날짜는 한국공항공사 정기 운항 스케줄.
     * (TAGO 는 오늘·내일만 제대로 주고 다른 날은 한두 편만 섞여 나옴.) 한쪽이 비면 다른 쪽으로 채움.
     */
    private suspend fun domesticTrips(
        d: com.eunho.tagohub.data.Place,
        a: com.eunho.tagohub.data.Place,
        date: java.time.LocalDate,
        grade: com.eunho.tagohub.data.Grade?,
    ): com.eunho.tagohub.core.TagoResult<List<com.eunho.tagohub.data.Trip>> {
        val today = java.time.LocalDate.now()
        val live = date == today || date == today.plusDays(1)
        var tago: com.eunho.tagohub.core.TagoResult<List<com.eunho.tagohub.data.Trip>>? = null
        if (live) {
            tago = repo.flights(d.id, a.id, date, grade?.id)
            if (!(tago as? com.eunho.tagohub.core.TagoResult.Success)?.data.isNullOrEmpty()) return tago
        }
        val dep = com.eunho.tagohub.data.KacDom.codeOf(d.name)
        val arr = com.eunho.tagohub.data.KacDom.codeOf(a.name)
        if (dep != null && arr != null) {
            val sched = com.eunho.tagohub.data.KacIntlClient.domesticTrips(dep, arr, date)
            val rows = (sched as? com.eunho.tagohub.core.TagoResult.Success)?.data
                ?.filter { grade == null || it.kind.contains(grade.name) || grade.name.contains(it.kind) }
            if (!rows.isNullOrEmpty()) return com.eunho.tagohub.core.TagoResult.Success(rows)
            if (sched is com.eunho.tagohub.core.TagoResult.Error && tago == null) return sched
        }
        return tago ?: com.eunho.tagohub.core.TagoResult.Success(emptyList())
    }

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
    // 내 위치 가까운 역: 위치 권한이 이미 있을 때만 (여기서 따로 묻지 않음)
    val context = androidx.compose.ui.platform.LocalContext.current
    androidx.compose.runtime.LaunchedEffect(Unit) {
        if (!com.eunho.tagohub.ui.bus.hasLocationPermission(context)) return@LaunchedEffect
        val loc = com.eunho.tagohub.ui.bus.lastKnownLocation(context) ?: com.eunho.tagohub.ui.bus.currentLocation(context)
        loc?.let { vm.loadNearby(it.latitude, it.longitude) }
    }
    val nearby by vm.nearby.collectAsStateWithLifecycle()
    Column(Modifier.fillMaxSize()) {
        TimetablePane(
            vm.table, ModeColor.Train,
            quickPicks = nearby,
            pickerNoun = "역",
            gradeAllLabel = "전체 차종",
            idleText = "출발역과 도착역을 고르면 시간표가 표시됩니다. 직통이 없으면 환승 경로를 찾고, 열차마다 정차역을 볼 수 있습니다.\n처음 한 번은 전국 역 목록을 받느라 몇 초 걸립니다.",
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
