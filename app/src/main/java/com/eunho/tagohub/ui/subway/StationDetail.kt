package com.eunho.tagohub.ui.subway

import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.toLoad
import com.eunho.tagohub.data.ExitInfo
import com.eunho.tagohub.data.SeoulSubwayRepository
import com.eunho.tagohub.data.StationGroup
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

enum class DayType(val code: String, val label: String) {
    Weekday("01", "평일"), Saturday("02", "토요일"), Sunday("03", "일요일·공휴일");

    companion object {
        /** 공휴일은 판별하지 않는다 (요일만) */
        fun today(): DayType = when (LocalDate.now().dayOfWeek) {
            DayOfWeek.SATURDAY -> Saturday
            DayOfWeek.SUNDAY -> Sunday
            else -> Weekday
        }
    }
}

enum class StationTab(val label: String) { Live("실시간 도착"), Timetable("시간표"), Exits("출구정보") }

/** 지도에서 역을 눌렀을 때의 상세 상태 (역 하나당 인스턴스 하나) */
class StationDetail(val group: StationGroup) {
    val realtime: Boolean = group.seoulLineIds.isNotEmpty()

    val tab = MutableStateFlow(if (realtime) StationTab.Live else StationTab.Timetable)

    // 실시간 도착 (서울 API)
    val arrivals = MutableStateFlow<Load<List<SubwayArrival>>>(Load.Loading)
    val arrivalsUpdated = MutableStateFlow<LocalTime?>(null)

    // 시간표·출구 (TAGO SubwayInfo): 노선마다 역 ID 가 따로라 노선 선택이 필요
    val tagoLines = MutableStateFlow<Load<List<SubwayStation>>>(Load.Loading)
    val line = MutableStateFlow<SubwayStation?>(null)
    val dayType = MutableStateFlow(DayType.today())
    val upDown = MutableStateFlow("U")
    val schedule = MutableStateFlow<Load<List<SubwayDeparture>>>(Load.Idle)
    val exits = MutableStateFlow<Load<List<ExitInfo>>>(Load.Idle)

    internal var scheduleJob: Job? = null
    internal var exitsJob: Job? = null
}

/**
 * 역 상세 동작. 지도 탭과 지하철 탭이 같이 쓴다.
 * 시간표·출구정보는 TAGO, 실시간 도착은 서울 API.
 */
class StationDetailController(
    private val scope: CoroutineScope,
    private val tago: TagoRepository,
    private val seoul: SeoulSubwayRepository,
) {
    fun open(group: StationGroup): StationDetail {
        val d = StationDetail(group)
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
            val r = seoul.arrivals(d.group)
            if (r is TagoResult.Success || d.arrivals.value !is Load.Ok) d.arrivals.value = r.toLoad()
            if (r is TagoResult.Success) d.arrivalsUpdated.value = LocalTime.now()
            delay(20_000)
        }
    }

    /**
     * 표준데이터 역 ↔ TAGO 지하철역 연결.
     * 이름(정규화)이 같은 TAGO 역 중 이 역의 노선과 맞는 것만 남긴다.
     * 노선이 하나도 안 맞으면(지방 노선명 표기 차이 등) 이름이 같은 역을 모두 보여준다.
     */
    private suspend fun loadTagoLines(d: StationDetail) {
        val r = tago.subwayStations(d.group.name)
        if (r is TagoResult.Error) {
            d.tagoLines.value = Load.Fail(r.message)
            return
        }
        val sameName = (r as TagoResult.Success).data.filter { StationName.norm(it.name) == d.group.name }
        val lines = d.group.displayLines + d.group.stations.map { it.line }
        val matched = sameName.filter { t -> lines.any { StationName.sameLine(t.routeName, it) } }
        val list = (matched.ifEmpty { sameName }).distinctBy { it.id }
        d.tagoLines.value = Load.Ok(list)
        list.firstOrNull()?.let { selectLine(d, it) }
    }

    fun setTab(d: StationDetail, t: StationTab) {
        d.tab.value = t
        if (t == StationTab.Exits && d.exits.value !is Load.Ok) loadExits(d)
    }

    fun selectLine(d: StationDetail, s: SubwayStation) {
        d.line.value = s
        d.exits.value = Load.Idle
        loadSchedule(d)
        if (d.tab.value == StationTab.Exits) loadExits(d)
    }

    fun setDay(d: StationDetail, t: DayType) { d.dayType.value = t; loadSchedule(d) }
    fun setUpDown(d: StationDetail, v: String) { d.upDown.value = v; loadSchedule(d) }

    fun loadSchedule(d: StationDetail) {
        val s = d.line.value ?: return
        d.scheduleJob?.cancel()
        d.scheduleJob = scope.launch {
            d.schedule.value = Load.Loading
            d.schedule.value = tago.subwaySchedule(s.id, d.dayType.value.code, d.upDown.value).toLoad()
        }
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
