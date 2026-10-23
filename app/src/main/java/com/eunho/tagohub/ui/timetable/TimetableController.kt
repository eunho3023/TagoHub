package com.eunho.tagohub.ui.timetable

import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.toLoad
import com.eunho.tagohub.data.Grade
import com.eunho.tagohub.data.Place
import com.eunho.tagohub.data.RunSummary
import com.eunho.tagohub.data.StopTime
import com.eunho.tagohub.data.TrainTransfer
import com.eunho.tagohub.data.Trip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalDate

/**
 * 기차·항공·고속·시외 시간표는 모두 "출발지 → 도착지 + 날짜 + 등급필터" 구조라
 * 상태와 동작을 하나로 묶었다. ViewModel 이 viewModelScope 로 인스턴스를 만든다.
 */
class TimetableController(
    private val scope: CoroutineScope,
    val placeLoader: suspend (String) -> TagoResult<List<Place>>,
    private val gradeLoader: (suspend () -> TagoResult<List<Grade>>)?,
    private val tripLoader: suspend (dep: Place, arr: Place, date: LocalDate, grade: Grade?) -> TagoResult<List<Trip>>,
    /** 기차만: 모든 역 목록 (가나다순·지역별로 고르기) */
    val browseLoader: (suspend () -> TagoResult<List<Place>>)? = null,
    /** 기차만: 환승 1회 경로 */
    private val transferLoader: (suspend (dep: Place, arr: Place, date: LocalDate) -> TagoResult<List<TrainTransfer>>)? = null,
    /** 기차만: 열차별 정차역 */
    private val stopsLoader: (suspend (trip: Trip, date: LocalDate) -> TagoResult<List<StopTime>>)? = null,
    /** 기차만: 지난 7일 실제 운행(지연) */
    private val historyLoader: (suspend (trip: Trip) -> TagoResult<RunSummary>)? = null,
) {
    val supportsTransfer: Boolean get() = transferLoader != null
    val supportsStops: Boolean get() = stopsLoader != null
    val supportsHistory: Boolean get() = historyLoader != null
    val dep = MutableStateFlow<Place?>(null)
    val arr = MutableStateFlow<Place?>(null)
    val date = MutableStateFlow(LocalDate.now())
    val grades = MutableStateFlow<Load<List<Grade>>>(Load.Idle)
    val grade = MutableStateFlow<Grade?>(null)
    val trips = MutableStateFlow<Load<List<Trip>>>(Load.Idle)

    /** 환승 경로 (직통이 없으면 자동으로, 있으면 버튼을 눌렀을 때) */
    val transfers = MutableStateFlow<Load<List<TrainTransfer>>>(Load.Idle)

    /** 정차역을 펼친 열차: 키 → 정차역 (키는 [stopsKey]) */
    val stops = MutableStateFlow<Map<String, Load<List<StopTime>>>>(emptyMap())

    /** 지난 7일 운행을 펼친 열차: 키 → 요약 (키는 [stopsKey]) */
    val history = MutableStateFlow<Map<String, Load<RunSummary>>>(emptyMap())

    fun toggleHistory(t: Trip) {
        val loader = historyLoader ?: return
        val key = stopsKey(t)
        if (history.value.containsKey(key)) {
            history.value = history.value - key
            return
        }
        history.value = history.value + (key to Load.Loading)
        scope.launch {
            val r = loader(t).toLoad()
            if (history.value.containsKey(key)) history.value = history.value + (key to r)
        }
    }

    private var job: Job? = null
    private var transferJob: Job? = null

    fun stopsKey(t: Trip): String = "${t.depId}|${t.arrId}|${t.number}|${t.dep}"

    fun toggleStops(t: Trip) {
        val loader = stopsLoader ?: return
        val key = stopsKey(t)
        if (stops.value.containsKey(key)) {
            stops.value = stops.value - key
            return
        }
        stops.value = stops.value + (key to Load.Loading)
        scope.launch {
            val r = loader(t, date.value).toLoad()
            if (stops.value.containsKey(key)) stops.value = stops.value + (key to r)
        }
    }

    fun loadTransfers() {
        val loader = transferLoader ?: return
        val d = dep.value ?: return
        val a = arr.value ?: return
        transferJob?.cancel()
        transferJob = scope.launch {
            transfers.value = Load.Loading
            transfers.value = loader(d, a, date.value).toLoad()
        }
    }

    fun ensureGrades() {
        val loader = gradeLoader ?: return
        if (grades.value is Load.Ok || grades.value is Load.Loading) return
        scope.launch {
            grades.value = Load.Loading
            grades.value = loader().toLoad()
        }
    }

    /** 지도에서 누른 역·터미널·공항 이름으로 출발지(asOrigin) 또는 도착지를 채움. 못 찾으면 false */
    suspend fun jumpTo(name: String, asOrigin: Boolean): Boolean {
        val key = name.replace(Regex("\\(.*?\\)"), "")
            .replace(Regex("(국제공항|공항|종합버스터미널|고속버스터미널|시외버스터미널|버스터미널|터미널|역)"), " ").trim()
            .ifEmpty { name }
        val places = (placeLoader(key) as? com.eunho.tagohub.core.TagoResult.Success)?.data.orEmpty()
        // 이름이 비슷한 것만 (전에는 못 찾으면 목록 첫 번째를 넣어서 엉뚱한 공항·역이 출발지가 됐음)
        val p = places.firstOrNull { com.eunho.tagohub.data.PoiMatch.similar(it.name, name) || com.eunho.tagohub.data.PoiMatch.similar(it.name, key) } ?: return false
        if (asOrigin) setDep(p) else setArr(p)
        return true
    }

    fun setDep(p: Place) { dep.value = p; search() }
    fun setArr(p: Place) { arr.value = p; search() }
    fun setDate(d: LocalDate) { date.value = d; search() }
    fun setGrade(g: Grade?) { grade.value = g; search() }

    fun swap() {
        val d = dep.value
        dep.value = arr.value
        arr.value = d
        search()
    }

    fun search() {
        val d = dep.value ?: return
        val a = arr.value ?: return
        job?.cancel()
        transferJob?.cancel()
        transfers.value = Load.Idle
        stops.value = emptyMap()
        history.value = emptyMap()
        if (d.id == a.id) {
            trips.value = Load.Fail("출발지와 도착지가 같습니다.")
            return
        }
        job = scope.launch {
            trips.value = Load.Loading
            val r = tripLoader(d, a, date.value, grade.value).toLoad()
            trips.value = r
            // 직통이 하나도 없으면 환승 경로를 바로 찾아 줌
            if (r is Load.Ok && r.value.isEmpty() && grade.value == null) loadTransfers()
        }
    }
}
