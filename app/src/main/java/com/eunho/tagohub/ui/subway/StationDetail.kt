package com.eunho.tagohub.ui.subway

import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.toLoad
import com.eunho.tagohub.data.ExitInfo
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.data.KricRouteStation
import com.eunho.tagohub.data.KricSubwayCodes
import com.eunho.tagohub.data.KricSubwayRepository
import com.eunho.tagohub.data.KricSubwayRegion
import com.eunho.tagohub.data.KricTrainStop
import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.SeoulSubwayRepository
import com.eunho.tagohub.data.StationGroup
import com.eunho.tagohub.data.StationFacility
import com.eunho.tagohub.data.StationFacilityIndex
import com.eunho.tagohub.data.StationName
import com.eunho.tagohub.data.SubwayArrival
import com.eunho.tagohub.data.SubwayDeparture
import com.eunho.tagohub.data.SubwayStation
import com.eunho.tagohub.data.TagoRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime

internal fun matchTagoStations(
    group: StationGroup,
    candidates: List<SubwayStation>,
    expectedCodes: (MetroStation) -> KricSubwayCodes?,
): List<SubwayStation> {
    val sameName = candidates.filter { StationName.norm(it.name) == StationName.norm(group.name) }
    return sameName.filter { candidate ->
        group.stations.any { metro ->
            if (!StationName.sameLine(candidate.routeName, metro.line) &&
                !StationName.sameLine(candidate.routeName, metro.displayLine)
            ) {
                false
            } else {
                val officialCodes = expectedCodes(metro)
                officialCodes == null || officialCodes in KricSubwayCodes.candidates(candidate.id)
            }
        }
    }.distinctBy { it.id }
}

enum class DayType(val code: String, val kricCode: String, val label: String) {
    Weekday("01", "8", "평일"), Saturday("02", "7", "토요일"), Sunday("03", "9", "일요일·공휴일");

    companion object {
        /** 공휴일은 판별하지 않는다 (요일만) */
        fun today(): DayType = when (LocalDate.now().dayOfWeek) {
            DayOfWeek.SATURDAY -> Saturday
            DayOfWeek.SUNDAY -> Sunday
            else -> Weekday
        }
    }
}

enum class StationTab(val label: String) {
    Live("역 정보·도착"), Timetable("시간표"), Route("노선"), Exits("출구정보"), Facilities("편의정보"), Congestion("혼잡도")
}

enum class SubwayTimetableSource { Tago, Kric }

/** 지도에서 역을 눌렀을 때의 상세 상태 (역 하나당 인스턴스 하나) */
class StationDetail(val group: StationGroup) {
    val realtime: Boolean = group.seoulLineIds.isNotEmpty()

    val tab = MutableStateFlow(StationTab.Live)

    // 실시간 도착 (서울 API)
    val arrivals = MutableStateFlow<Load<List<SubwayArrival>>>(Load.Loading)
    val arrivalsUpdated = MutableStateFlow<LocalTime?>(null)

    // TAGO 역별 시간표·출구, KRIC 열차별 시간표·노선정보: 노선마다 역 ID 가 달라 노선 선택이 필요
    val tagoLines = MutableStateFlow<Load<List<SubwayStation>>>(Load.Loading)
    val line = MutableStateFlow<SubwayStation?>(null)
    val dayType = MutableStateFlow(DayType.today())
    val upDown = MutableStateFlow("U")
    val schedule = MutableStateFlow<Load<List<SubwayDeparture>>>(Load.Idle)
    val timetableSource = MutableStateFlow(SubwayTimetableSource.Tago)
    val kricTimetable = MutableStateFlow<Load<List<KricTrainStop>>>(Load.Idle)
    val kricRoute = MutableStateFlow<Load<List<KricRouteStation>>>(Load.Idle)
    val kricCodes = MutableStateFlow<KricSubwayCodes?>(null)
    val exits = MutableStateFlow<Load<List<ExitInfo>>>(Load.Idle)
    val facilities = MutableStateFlow<List<StationFacility>>(emptyList())

    internal var scheduleJob: Job? = null
    internal var kricTimetableJob: Job? = null
    internal var kricRouteJob: Job? = null
    internal var exitsJob: Job? = null
}

/**
 * 역 상세 동작. 지도 탭과 지하철 탭이 같이 쓴다.
 * 역별 시간표·출구는 TAGO, 열차별 시각·노선정보는 KRIC, 실시간 도착은 서울 API.
 */
class StationDetailController(
    private val scope: CoroutineScope,
    private val tago: TagoRepository,
    private val seoul: SeoulSubwayRepository,
    private val kric: KricSubwayRepository = Graph.kricSubway,
) {
    @Volatile
    private var facilityIndex = StationFacilityIndex.parseTsv("")

    fun setFacilityIndex(index: StationFacilityIndex) {
        facilityIndex = index
    }

    fun open(group: StationGroup): StationDetail {
        val d = StationDetail(group)
        d.facilities.value = facilityIndex.forStation(group)
        scope.launch { loadTagoLines(d) }
        return d
    }

    /** 화면에 떠 있는 동안 20초마다 (LaunchedEffect 에서 호출) */
    suspend fun runArrivals(d: StationDetail) {
        if (!d.realtime) {
            d.arrivals.value = Load.Ok(emptyList())
            return
        }
        while (true) {
            com.eunho.tagohub.core.AppState.awaitMapVisible()
            val fetched = seoul.arrivals(d.group)
            // 새로고침해도 남은 시간이 거꾸로 늘지 않게
            val r = if (fetched is TagoResult.Success) TagoResult.Success(com.eunho.tagohub.data.ArrivalSmoothing.subway((d.arrivals.value as? Load.Ok)?.value, fetched.data, java.time.LocalDateTime.now())) else fetched
            if (r is TagoResult.Success || d.arrivals.value !is Load.Ok) d.arrivals.value = r.toLoad()
            if (r is TagoResult.Success) d.arrivalsUpdated.value = LocalTime.now()
            delay(20_000)
        }
    }

    /**
     * 표준데이터 역 ↔ TAGO 지하철역 연결. 동명이역은 공식 KRIC 역 코드로 구분한다.
     */
    private suspend fun loadTagoLines(d: StationDetail) {
        val r = tago.subwayStations(d.group.name)
        if (r is TagoResult.Error) {
            d.tagoLines.value = Load.Fail(r.message)
            return
        }
        val list = matchTagoStations(d.group, (r as TagoResult.Success).data) { metro ->
            val regionCode = KricSubwayRegion.fromOperator(metro.operator) ?: return@matchTagoStations null
            kric.stationCodes(
                stationName = metro.name,
                lineNames = listOf(metro.line, metro.displayLine),
                operatorNames = listOf(metro.operator),
                regionCode = regionCode,
            )
        }
        d.tagoLines.value = Load.Ok(list)
        list.firstOrNull()?.let { selectLine(d, it) }
    }

    fun setTab(d: StationDetail, t: StationTab) {
        d.tab.value = t
        if (t == StationTab.Exits && d.exits.value !is Load.Ok) loadExits(d)
        if (t == StationTab.Route && d.kricRoute.value !is Load.Ok) loadKricRoute(d)
        if (t == StationTab.Timetable && d.timetableSource.value == SubwayTimetableSource.Kric &&
            d.kricTimetable.value !is Load.Ok
        ) loadKricTimetable(d)
    }

    fun selectLine(d: StationDetail, s: SubwayStation) {
        d.line.value = s
        d.exits.value = Load.Idle
        d.kricTimetableJob?.cancel()
        d.kricRouteJob?.cancel()
        d.kricTimetable.value = Load.Idle
        d.kricRoute.value = Load.Idle
        d.kricCodes.value = null
        loadSchedule(d)
        if (d.timetableSource.value == SubwayTimetableSource.Kric) loadKricTimetable(d)
        if (d.tab.value == StationTab.Route) loadKricRoute(d)
        if (d.tab.value == StationTab.Exits) loadExits(d)
    }

    fun setDay(d: StationDetail, t: DayType) {
        d.dayType.value = t
        if (d.timetableSource.value == SubwayTimetableSource.Kric) loadKricTimetable(d) else loadSchedule(d)
    }
    fun setUpDown(d: StationDetail, v: String) { d.upDown.value = v; loadSchedule(d) }

    fun setTimetableSource(d: StationDetail, source: SubwayTimetableSource) {
        d.timetableSource.value = source
        if (source == SubwayTimetableSource.Kric) loadKricTimetable(d) else loadSchedule(d)
    }

    fun loadSchedule(d: StationDetail) {
        val s = d.line.value ?: return
        d.scheduleJob?.cancel()
        d.scheduleJob = scope.launch {
            d.schedule.value = Load.Loading
            d.schedule.value = tago.subwaySchedule(s.id, d.dayType.value.code, d.upDown.value).toLoad()
        }
    }

    fun loadKricTimetable(d: StationDetail) {
        d.kricTimetableJob?.cancel()
        val s = d.line.value ?: return
        val regionCode = kricRegionCode(d, s)
        if (regionCode == null) {
            d.kricTimetable.value = Load.Fail("이 운영기관의 KRIC 광역권 코드를 찾지 못했습니다.")
            return
        }
        d.kricTimetableJob = scope.launch {
            d.kricTimetable.value = Load.Loading
            val route = kric.routeForStation(s, regionCode, kricStationCodes(d, s, regionCode))
            d.kricTimetable.value = when (route) {
                is TagoResult.Error -> route.toLoad()
                is TagoResult.Success -> {
                    d.kricCodes.value = route.data.codes
                    kric.timetable(route.data.codes, d.dayType.value.kricCode).toLoad()
                }
            }
        }
    }

    fun loadKricRoute(d: StationDetail) {
        if (d.kricRoute.value is Load.Loading) return
        d.kricRouteJob?.cancel()
        val s = d.line.value ?: return
        val regionCode = kricRegionCode(d, s)
        if (regionCode == null) {
            d.kricRoute.value = Load.Fail("이 운영기관의 KRIC 광역권 코드를 찾지 못했습니다.")
            return
        }
        d.kricRoute.value = Load.Loading
        d.kricRouteJob = scope.launch {
            d.kricRoute.value = when (val result =
                kric.routeForStation(s, regionCode, kricStationCodes(d, s, regionCode))
            ) {
                is TagoResult.Error -> result.toLoad()
                is TagoResult.Success -> {
                    d.kricCodes.value = result.data.codes
                    Load.Ok(result.data.stations)
                }
            }
        }
    }

    private fun kricRegionCode(d: StationDetail, station: SubwayStation): String? {
        val metro = d.group.stations.firstOrNull {
            StationName.sameLine(station.routeName, it.displayLine) ||
                StationName.sameLine(station.routeName, it.line)
        } ?: d.group.stations.firstOrNull()
        return metro?.let { KricSubwayRegion.fromOperator(it.operator) }
    }

    private fun kricStationCodes(d: StationDetail, station: SubwayStation, regionCode: String): KricSubwayCodes? {
        val matchingStations = d.group.stations.filter {
            StationName.sameLine(station.routeName, it.displayLine) ||
                StationName.sameLine(station.routeName, it.line)
        }.ifEmpty { d.group.stations }
        val lineNames = buildSet {
            add(station.routeName)
            matchingStations.forEach {
                add(it.line)
                add(it.displayLine)
            }
        }
        return kric.stationCodes(
            stationName = station.name,
            lineNames = lineNames,
            operatorNames = matchingStations.map { it.operator }.distinct(),
            regionCode = regionCode,
        )
    }

    fun loadExits(d: StationDetail) {
        val s = d.line.value ?: return
        d.exitsJob?.cancel()
        d.exitsJob = scope.launch {
            d.exits.value = Load.Loading
            d.exits.value = tago.subwayExits(s.id).toLoad()
        }
    }
}
