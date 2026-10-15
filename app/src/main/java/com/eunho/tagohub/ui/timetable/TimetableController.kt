package com.eunho.tagohub.ui.timetable

import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.toLoad
import com.eunho.tagohub.data.Grade
import com.eunho.tagohub.data.Place
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
) {
    val dep = MutableStateFlow<Place?>(null)
    val arr = MutableStateFlow<Place?>(null)
    val date = MutableStateFlow(LocalDate.now())
    val grades = MutableStateFlow<Load<List<Grade>>>(Load.Idle)
    val grade = MutableStateFlow<Grade?>(null)
    val trips = MutableStateFlow<Load<List<Trip>>>(Load.Idle)

    private var job: Job? = null

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
        if (d.id == a.id) {
            trips.value = Load.Fail("출발지와 도착지가 같습니다.")
            return
        }
        job = scope.launch {
            trips.value = Load.Loading
            trips.value = tripLoader(d, a, date.value, grade.value).toLoad()
        }
    }
}
