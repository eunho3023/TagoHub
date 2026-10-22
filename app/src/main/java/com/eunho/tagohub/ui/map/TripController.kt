package com.eunho.tagohub.ui.map

import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.data.Jeonju
import com.eunho.tagohub.data.JeonjuMappers
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.PlaceSearch
import com.eunho.tagohub.data.Shapes
import com.eunho.tagohub.data.StopDb
import com.eunho.tagohub.data.TagoRepository
import com.eunho.tagohub.data.TripLeg
import com.eunho.tagohub.data.TripPlan
import com.eunho.tagohub.data.TripPlanner
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 길찾기 출발지·도착지 (장소 또는 정류장) */
data class PlanPoint(val name: String, val sub: String?, val lat: Double, val lng: Double, val stopId: String? = null)

enum class PlanField { Origin, Dest, Via }
enum class PlanMode(val label: String) { Transit("대중교통"), Car("자동차"), Walk("도보"), Bicycle("자전거") }

/** 고른 경로의 실시간 정보 */
data class TripLive(val first: List<BusArrival>, val transfer: List<BusArrival>)

data class RidePlan(
    val mode: PlanMode,
    val distanceMeters: Int,
    val durationSeconds: Int,
    /** 자동차 경로만: 통행요금(원), 회전 안내, 도로 구간별 소통 상태 */
    val tollWon: Int? = null,
    val guides: List<com.eunho.tagohub.data.DriveGuide> = emptyList(),
    val roads: List<com.eunho.tagohub.data.DriveRoad> = emptyList(),
    /** 안내 시작에 쓰는 경로 원본과 목적지 */
    val drive: com.eunho.tagohub.data.KakaoDriveRoute? = null,
    val destLat: Double = 0.0,
    val destLng: Double = 0.0,
    val destName: String = "",
    /** 택시로 가면 나오는 요금(원) */
    val taxiWon: Int? = null,
    /** 이 경로를 찾을 때 쓴 옵션 (안내 중 재탐색도 같은 옵션으로) */
    val option: com.eunho.tagohub.data.DriveOption = com.eunho.tagohub.data.DriveOption.Recommend,
    /** 이 경로가 거치는 경유지 (안내 시작·재탐색에 그대로 넘김) */
    val vias: List<LatLng> = emptyList(),
)

/** 소통 상태 색: 1 정체(빨강) 2 지체(주황) 3 서행(노랑) 4 원활(초록), 사고(6)는 진한 빨강, 모르면 기본 파랑 */
internal fun trafficColor(state: Int): Int = when (state) {
    1 -> 0xFFE53935.toInt()
    2 -> 0xFFF57C00.toInt()
    3 -> 0xFFFBC02D.toInt()
    4 -> 0xFF2E9B57.toInt()
    6 -> 0xFF8E0000.toInt()
    else -> 0xFF3D5BAB.toInt()
}

/** 이어지는 도로 중 소통 색이 같은 것끼리 한 줄로 묶어 지도에 그릴 선을 만든다 */
internal fun trafficSegments(roads: List<com.eunho.tagohub.data.DriveRoad>): List<TripSegment> {
    val out = ArrayList<TripSegment>()
    var color = 0
    var pts = ArrayList<com.eunho.tagohub.data.LatLng>()
    for (r in roads) {
        if (r.points.size < 2) continue
        val c = trafficColor(r.trafficState)
        if (pts.isNotEmpty() && c != color) {
            out += TripSegment(pts, color, false)
            // 색이 바뀌는 곳이 끊겨 보이지 않게 이전 선의 마지막 점에서 이어 그림
            pts = arrayListOf(pts.last())
        }
        color = c
        pts.addAll(r.points)
    }
    if (pts.size >= 2) out += TripSegment(pts, color, false)
    return out
}

/** "정체 3.2km · 서행 12.1km" (없으면 null) */
internal fun trafficSummary(roads: List<com.eunho.tagohub.data.DriveRoad>): String? {
    if (roads.isEmpty()) return null
    val heavy = roads.filter { it.trafficState == 1 || it.trafficState == 2 || it.trafficState == 6 }.sumOf { it.distance }
    val slow = roads.filter { it.trafficState == 3 }.sumOf { it.distance }
    fun km(m: Int) = "%.1fkm".format(m / 1000.0)
    return when {
        heavy == 0 && slow == 0 -> "정체 없음"
        else -> listOfNotNull(if (heavy > 0) "정체 ${km(heavy)}" else null, if (slow > 0) "서행 ${km(slow)}" else null).joinToString(" · ")
    }
}

/**
 * 버스 길찾기 (전주시 시내버스).
 * 직통: 출발 정류장을 지나는 노선의 정류장 목록에서 도착 정류장이 뒤에 나오면 직통.
 * 환승: 전주시 환승 경로검색 API.
 * 정렬: 걷기 + 정류장 수 + 환승 + 첫 버스 대기(실시간) 로 계산한 예상 시간.
 */
class TripController(
    private val scope: CoroutineScope,
    private val repo: TagoRepository,
    private val canvas: () -> MapCanvas?,
    /** 내 위치 (없으면 null) */
    private val me: () -> LatLng?,
    /** 지금 지도 중심 */
    private val center: () -> LatLng?,
    private val busy: MutableStateFlow<Int>,
    /** 집·회사 (휴대폰에 저장) */
    val homeWork: com.eunho.tagohub.data.HomeWorkStore,
) {
    val origin = MutableStateFlow<PlanPoint?>(null)
    val dest = MutableStateFlow<PlanPoint?>(null)
    val editing = MutableStateFlow<PlanField?>(PlanField.Dest)
    val results = MutableStateFlow<Load<List<PlanPoint>>>(Load.Idle)
    /** 길찾기 결과 정렬 (최단시간·최소도보·최소환승) */
    val sort = MutableStateFlow(PlanSort.Fast)
    /** 경유지 (카카오 길찾기로 출발→경유, 경유→도착을 이어 붙임) */
    val via = MutableStateFlow<PlanPoint?>(null)

    /** 자동차 길찾기 경유지 (카카오 길찾기 waypoints, 최대 5개). 순서대로 거침 */
    val carVias = MutableStateFlow<List<PlanPoint>>(emptyList())

    fun addCarVia(p: PlanPoint) {
        if (carVias.value.size >= com.eunho.tagohub.data.KakaoDrive.MAX_WAYPOINTS) {
            note.value = "경유지는 ${com.eunho.tagohub.data.KakaoDrive.MAX_WAYPOINTS}개까지 넣을 수 있습니다."
            return
        }
        carVias.value = carVias.value + p
        if (dest.value != null) { mode.value = PlanMode.Car; drive() }
    }

    fun removeCarVia(index: Int) {
        carVias.value = carVias.value.filterIndexed { i, _ -> i != index }
        if (dest.value != null && mode.value == PlanMode.Car) drive()
    }
    val plans = MutableStateFlow<Load<List<TripPlan>>>(Load.Idle)
    val selected = MutableStateFlow<TripPlan?>(null)
    val live = MutableStateFlow<TripLive?>(null)
    val mode = MutableStateFlow(PlanMode.Transit)
    val ridePlan = MutableStateFlow<Load<RidePlan>>(Load.Idle)

    /** 자동차 길찾기 옵션 (추천·최단거리·무료도로·고속도로 제외) */
    val driveOption = MutableStateFlow(com.eunho.tagohub.data.DriveOption.Recommend)

    fun selectDriveOption(option: com.eunho.tagohub.data.DriveOption) {
        if (driveOption.value == option) return
        driveOption.value = option
        if (mode.value == PlanMode.Car) drive()
    }
    val note = MutableStateFlow<String?>(null)

    /** 값이 있으면 지금 검색해서 고르는 장소를 길찾기 대신 집/회사로 저장한다 ("집 설정" 칩) */
    val saving = MutableStateFlow<com.eunho.tagohub.data.SavedKind?>(null)

    /** 집/회사로 한 번에: 출발은 지금 위치(또는 정해 둔 출발지), 도착은 저장한 곳으로 대중교통 길찾기 */
    fun goTo(place: com.eunho.tagohub.data.SavedPlace) {
        saving.value = null
        editing.value = null
        results.value = Load.Idle
        dest.value = PlanPoint(place.name, null, place.lat, place.lng)
        plan()
    }

    /** 집/회사 장소 검색 시작 (고른 곳은 길찾기 대신 저장됨) */
    fun startSaving(kind: com.eunho.tagohub.data.SavedKind) {
        editing.value = PlanField.Dest
        results.value = Load.Idle
        saving.value = kind
    }

    private var searchJob: Job? = null
    private var planJob: Job? = null
    private var liveJob: Job? = null
    private var rideJob: Job? = null

    private suspend fun <T> tracked(block: suspend () -> T): T {
        busy.value++
        try {
            return block()
        } finally {
            busy.value--
        }
    }

    fun reset() {
        saving.value = null
        searchJob?.cancel(); planJob?.cancel(); liveJob?.cancel(); rideJob?.cancel()
        origin.value = null
        dest.value = null
        editing.value = PlanField.Dest
        results.value = Load.Idle
        plans.value = Load.Idle
        via.value = null
        carVias.value = emptyList()
        selected.value = null
        live.value = null
        mode.value = PlanMode.Transit
        ridePlan.value = Load.Idle
        note.value = null
        canvas()?.setTrip(emptyList(), emptyList())
    }

    fun selectMode(value: PlanMode) {
        mode.value = value
        when (value) {
            PlanMode.Transit -> plan()
            PlanMode.Car -> drive()
            PlanMode.Walk -> ride(bicycle = false)
            PlanMode.Bicycle -> ride(bicycle = true)
        }
    }

    /** 자동차 경로를 소통 색으로 지도에 그림. 안내 중 재탐색으로 경로가 바뀔 때도 씀([fit]=false 면 화면을 옮기지 않음) */
    fun showDriveRoute(origin: LatLng, dest: LatLng, route: com.eunho.tagohub.data.KakaoDriveRoute, fit: Boolean, vias: List<LatLng> = emptyList()) {
        canvas()?.setTrip(
            trafficSegments(route.roads).ifEmpty { listOf(TripSegment(route.points, 0xFF3D5BAB.toInt(), false)) },
            listOf(TripMark(origin.lat, origin.lng, "출", MARK_START)) + vias.map { TripMark(it.lat, it.lng, "경", MARK_TRANSFER) } + TripMark(dest.lat, dest.lng, "도", MARK_END),
        )
        if (fit) canvas()?.fitTo(route.points)
    }

    /** 안내 중: 지나온 경로는 지우고 [progress] 미터 지점부터 남은 경로만 소통 색으로 그림 (화면은 옮기지 않음) */
    fun showDriveRemaining(nav: com.eunho.tagohub.data.NavRoute, progress: Double, dest: LatLng, vias: List<LatLng> = emptyList()) {
        val roads = nav.remainingRoads(progress)
        val segments = trafficSegments(roads).ifEmpty { listOf(TripSegment(nav.remainingPoints(progress), 0xFF3D5BAB.toInt(), false)) }
        canvas()?.setTrip(segments, vias.map { TripMark(it.lat, it.lng, "경", MARK_TRANSFER) } + TripMark(dest.lat, dest.lng, "도", MARK_END))
    }

    private fun drive() {
        val d = dest.value ?: return
        val o = origin.value?.let { LatLng(it.lat, it.lng) } ?: me() ?: center()
        rideJob?.cancel()
        note.value = "카카오 자동차 길찾기"
        rideJob = scope.launch {
            ridePlan.value = Load.Loading
            if (o == null) {
                ridePlan.value = Load.Fail("출발 위치를 확인할 수 없습니다. 출발지를 선택해주세요.")
                return@launch
            }
            if (!com.eunho.tagohub.data.KakaoExtra.available) {
                ridePlan.value = Load.Fail("카카오 REST API 키가 설정되지 않았습니다.")
                return@launch
            }
            val viaLatLngs = carVias.value.map { LatLng(it.lat, it.lng) }
            val (status, route) = tracked { com.eunho.tagohub.data.KakaoExtra.drive(o, LatLng(d.lat, d.lng), driveOption.value, viaLatLngs) }
            if (route == null) {
                ridePlan.value = Load.Fail(
                    when {
                        status == "HTTP 401" || status == "HTTP 403" -> "카카오 자동차 길찾기 권한 또는 REST API 키를 확인해주세요."
                        status.startsWith("HTTP ") -> "카카오 자동차 경로 요청에 실패했습니다. ($status)"
                        else -> "자동차 경로를 찾지 못했습니다. ($status)"
                    },
                )
                return@launch
            }
            showDriveRoute(o, LatLng(d.lat, d.lng), route, fit = true, vias = viaLatLngs)
            ridePlan.value = Load.Ok(
                RidePlan(PlanMode.Car, route.distanceMeters, route.durationSeconds, route.tollWon, route.guides, route.roads, route, d.lat, d.lng, d.name, route.taxiWon, driveOption.value, viaLatLngs),
            )
        }
    }

    /** 카카오 자전거(bicycle)·도보 경로 */
    private fun ride(bicycle: Boolean) {
        val d = dest.value ?: return
        val o = origin.value?.let { LatLng(it.lat, it.lng) } ?: me() ?: center()
        rideJob?.cancel()
        note.value = if (bicycle) "카카오 자전거 경로" else "카카오 도보 경로"
        rideJob = scope.launch {
            ridePlan.value = Load.Loading
            if (o == null) {
                ridePlan.value = Load.Fail("출발 위치를 확인할 수 없습니다. 출발지를 선택해주세요.")
                return@launch
            }
            if (!com.eunho.tagohub.data.KakaoExtra.available) {
                ridePlan.value = Load.Fail("카카오 REST API 키가 설정되지 않았습니다.")
                return@launch
            }
            val r = tracked { com.eunho.tagohub.data.KakaoExtra.ride(o, LatLng(d.lat, d.lng), bicycle) }
            if (r == null) {
                ridePlan.value = Load.Fail("${if (bicycle) "자전거" else "도보"} 경로를 카카오에서 찾지 못했습니다.")
                return@launch
            }
            val (path, m, sec) = r
            canvas()?.setTrip(listOf(TripSegment(path, if (bicycle) 0xFF2E7D32.toInt() else WALK, !bicycle)),
                listOf(TripMark(o.lat, o.lng, "출", MARK_START), TripMark(d.lat, d.lng, "도", MARK_END)))
            canvas()?.fitTo(path)
            ridePlan.value = Load.Ok(RidePlan(if (bicycle) PlanMode.Bicycle else PlanMode.Walk, m, sec))
        }
    }

    /** 경유: 앞 경로 + 뒤 경로 (경유지에서 내려 다시 타는 걷기와 3분 여유 포함) */
    private fun joinVia(a: TripPlan, b: TripPlan): TripPlan = a.copy(
        dest = b.dest,
        walkEndM = b.walkEndM,
        legs = a.legs + b.legs,
        transferWalkM = a.transferWalkM + b.transferWalkM + a.walkEndM + b.walkStartM,
        fixedMinutes = (a.fixedMinutes ?: a.minutes) + (b.fixedMinutes ?: b.minutes) + 3,
        fare = listOfNotNull(a.fare, b.fare).takeIf { it.isNotEmpty() }?.sum(),
        walkPaths = a.walkPaths.orEmpty() + b.walkPaths.orEmpty(),
        source = "카카오 길찾기 · 경유 ${via.value?.name.orEmpty()}",
    )

    fun edit(field: PlanField?) {
        if (field == null || saving.value != null) saving.value = null
        editing.value = field
        results.value = Load.Idle
    }

/** 출발지·도착지 검색은 카카오 로컬 장소 검색을 사용한다. */
    fun search(q: String) {
        searchJob?.cancel()
        val k = q.trim()
        if (k.isEmpty()) {
            results.value = Load.Idle
            return
        }
        searchJob = scope.launch {
            delay(250)
            results.value = Load.Loading
            if (!PlaceSearch.available) {
                results.value = Load.Fail("카카오 REST API 키가 설정되지 않았습니다.")
                return@launch
            }
            val near = me() ?: center()
            val response = tracked { PlaceSearch.searchResult(k, near) }
            results.value = response.fold(
                onSuccess = { places -> Load.Ok(places.map { PlanPoint(it.name, it.address, it.lat, it.lng) }) },
                onFailure = { Load.Fail("카카오 장소 검색 실패: ${it.message ?: "네트워크 오류"}") },
            )
        }
    }

    fun pick(p: PlanPoint?) {
        val kind = saving.value
        if (kind != null && p != null) {
            // 집/회사 저장 중: 길찾기는 하지 않고 저장만
            homeWork.set(kind, com.eunho.tagohub.data.SavedPlace(p.name, p.lat, p.lng))
            saving.value = null
            editing.value = null
            results.value = Load.Idle
            return
        }
        if (editing.value == PlanField.Via && p != null) {
            editing.value = null
            results.value = Load.Idle
            addCarVia(p)
            return
        }
        rideJob?.cancel()
        mode.value = PlanMode.Transit
        ridePlan.value = Load.Idle
        when (editing.value) {
            PlanField.Origin -> origin.value = p
            PlanField.Dest -> dest.value = p
            PlanField.Via, null -> Unit
        }
        editing.value = null
        results.value = Load.Idle
        if (dest.value != null) plan()
    }

    fun swap() {
        rideJob?.cancel()
        mode.value = PlanMode.Transit
        ridePlan.value = Load.Idle
        val o = origin.value
        origin.value = dest.value
        dest.value = o
        if (dest.value != null) plan()
    }

    fun plan() {
        val d = dest.value ?: return
        planJob?.cancel()
        selected.value = null
        live.value = null
        rideJob?.cancel()
        mode.value = PlanMode.Transit
        ridePlan.value = Load.Idle
        canvas()?.setTrip(emptyList(), emptyList())
        planJob = scope.launch {
            plans.value = Load.Loading
            val o = origin.value ?: me()?.let { PlanPoint("내 위치", null, it.lat, it.lng) }
                ?: center()?.let { PlanPoint("지도 중심", null, it.lat, it.lng) }
            if (o == null) {
                plans.value = Load.Fail("출발지를 정해주세요.")
                return@launch
            }
            note.value = if (origin.value == null && me() == null) "현재 위치를 몰라 지도 중심에서 출발합니다. · 카카오" else "카카오 대중교통 길찾기"
            if (!com.eunho.tagohub.data.KakaoRoutes.available) {
                plans.value = Load.Fail("카카오 REST API 키가 설정되지 않았습니다.")
                return@launch
            }

            val v = via.value
            if (v != null) {
                val (statusA, a) = tracked { com.eunho.tagohub.data.KakaoRoutes.transit(o.lat, o.lng, v.lat, v.lng) }
                val (statusB, b) = tracked { com.eunho.tagohub.data.KakaoRoutes.transit(v.lat, v.lng, d.lat, d.lng) }
                val joined = a.sortedBy { it.minutes }.take(3).flatMap { x -> b.sortedBy { it.minutes }.take(3).map { y -> joinVia(x, y) } }
                if (joined.isNotEmpty()) {
                    plans.value = Load.Ok(joined.sortedBy { it.minutes })
                    canvas()?.fitTo(listOf(LatLng(o.lat, o.lng), LatLng(v.lat, v.lng), LatLng(d.lat, d.lng)))
                    return@launch
                }
                plans.value = Load.Fail("카카오에서 경유 경로를 찾지 못했습니다. ${routeFailure(statusA, statusB)}")
                return@launch
            }
            val (status, kakao) = tracked {
                com.eunho.tagohub.data.KakaoRoutes.transit(o.lat, o.lng, d.lat, d.lng)
            }
            if (kakao.isEmpty()) {
                plans.value = Load.Fail(routeFailure(status))
                return@launch
            }
            plans.value = Load.Ok(kakao.sortedBy { it.minutes }.take(10))
            canvas()?.fitTo(listOf(LatLng(o.lat, o.lng), LatLng(d.lat, d.lng)))
        }
    }

    private fun routeFailure(vararg statuses: String): String {
        val status = statuses.firstOrNull { it != "OK" && it != "NO_RESULTS" }
            ?: statuses.firstOrNull { it != "OK" }
            ?: "NO_RESULTS"
        return when {
            status == "HTTP 401" || status == "HTTP 403" ->
                "카카오 길찾기 권한을 확인해주세요. REST API 키와 카카오맵 사용 설정을 확인하세요."
            status.startsWith("HTTP ") ->
                "카카오 길찾기 요청에 실패했습니다. ($status)"
            status == "OK" ->
                "카카오에서 대중교통 경로를 찾지 못했습니다."
            else -> com.eunho.tagohub.data.KakaoTransit.message(status)
                ?: "카카오 대중교통 경로를 찾지 못했습니다. ($status)"
        }
    }

    /** 노선 ID → 도시코드 (TAGO 노선의 정류장 목록·도착을 받을 때) */
    private val routeCity = HashMap<String, String>()

    fun select(p: TripPlan?) {
        selected.value = p
        liveJob?.cancel()
        live.value = null
        if (p == null) {
            canvas()?.setTrip(emptyList(), emptyList())
            return
        }
        scope.launch { draw(p) }
        // 첫 버스·환승 버스 실시간 도착 (패널이 열려 있는 동안 20초마다)
        liveJob = scope.launch {
            while (true) {
                val first = liveFor(p.legs[0])
                val second = p.legs.getOrNull(1)?.let { liveFor(it) }.orEmpty()
                live.value = TripLive(first, second)
                delay(20_000)
            }
        }
    }

    /** 한 구간의 타는 정류장 실시간 도착 (그 노선만) */
    private suspend fun liveFor(leg: TripLeg): List<com.eunho.tagohub.data.BusArrival> {
        if (leg.subway) return emptyList()
        val db = StopDb.peek() ?: return emptyList()
        val kakao = com.eunho.tagohub.data.KakaoTransit.isKakao(leg.routeId)
        val stop = withContext(Dispatchers.IO) {
            if (kakao) boardStopNear(db, leg) else db.byId(leg.boardStopId)
        } ?: return emptyList()
        val all = (com.eunho.tagohub.ui.bus.arrivalsFor(repo, stop) as? TagoResult.Success)?.data.orEmpty()
        return if (kakao) {
            val nos = listOf(leg.routeNo) + leg.alternatives
            all.filter { a -> nos.any { com.eunho.tagohub.data.KakaoTransit.sameRouteNo(it, a.routeNo) } }
        } else all.filter { it.routeId == leg.routeId }
    }

    /** 카카오 구간의 타는 곳: 경로 시작점 80m 안 정류장 중 이름이 같은 것, 없으면 가장 가까운 것 */
    private fun boardStopNear(db: StopDb, leg: TripLeg): BusStop? {
        val lat = leg.boardLat ?: return null
        val lng = leg.boardLng ?: return null
        val near = db.near(lat, lng, 80.0, 10)
        fun n(s: String) = s.replace(Regex("[\\s.·()]"), "")
        return near.firstOrNull { n(it.first.name) == n(leg.boardName) }?.first ?: near.firstOrNull()?.first
    }

    private suspend fun draw(p: TripPlan) {
        val c = canvas() ?: return
        val db = StopDb.peek()
        val o = origin.value ?: me()?.let { PlanPoint("내 위치", null, it.lat, it.lng) } ?: center()?.let { PlanPoint("", null, it.lat, it.lng) }
        val d = dest.value
        val segs = ArrayList<TripSegment>()
        val marks = ArrayList<TripMark>()
        val all = ArrayList<LatLng>()
        val os = LatLng(p.origin.lat ?: 0.0, p.origin.lng ?: 0.0)
        val ds = LatLng(p.dest.lat ?: 0.0, p.dest.lng ?: 0.0)
        p.walkPaths?.let { walks ->
            // 카카오 길찾기: 걷는 길도 실제 경로, 구간 선도 그대로
            walks.forEach { w -> segs += TripSegment(w, WALK, true); all += w }
            p.legs.forEachIndexed { i, leg ->
                val path = legPath(leg, db)
                segs += TripSegment(path, LEG_COLORS[i % LEG_COLORS.size], false)
                all += path
                if (i < p.legs.lastIndex) {
                    val a = leg.alightLat
                    val b = leg.alightLng
                    if (a != null && b != null) marks += TripMark(a, b, "환", MARK_TRANSFER)
                }
            }
            o?.let { marks += TripMark(it.lat, it.lng, "출", MARK_START); all += LatLng(it.lat, it.lng) }
            d?.let { marks += TripMark(it.lat, it.lng, "도", MARK_END); all += LatLng(it.lat, it.lng) }
            c.setTrip(segs, marks)
            c.fitTo(all)
            return
        }
        o?.let { segs += TripSegment(walkLine(LatLng(it.lat, it.lng), os), WALK, true); all += LatLng(it.lat, it.lng) }
        p.legs.forEachIndexed { i, leg ->
            if (i > 0) {
                // 다른 정류장으로 걸어서 갈아타는 구간
                val prev = p.legs[i - 1]
                if (prev.alightStopId != leg.boardStopId) {
                    val a = db?.byId(prev.alightStopId)
                    val b = db?.byId(leg.boardStopId)
                    if (a?.lat != null && b?.lat != null) {
                        segs += TripSegment(walkLine(LatLng(a.lat, a.lng ?: 0.0), LatLng(b.lat, b.lng ?: 0.0)), WALK, true)
                    }
                }
            }
            val path = legPath(leg, db)
            segs += TripSegment(path, LEG_COLORS[i % LEG_COLORS.size], false)
            all += path
        }
        d?.let { segs += TripSegment(walkLine(ds, LatLng(it.lat, it.lng)), WALK, true); all += LatLng(it.lat, it.lng) }
        o?.let { marks += TripMark(it.lat, it.lng, "출", MARK_START) }
        p.legs.getOrNull(1)?.let { _ ->
            db?.byId(p.legs[0].alightStopId)?.let { s -> if (s.lat != null && s.lng != null) marks += TripMark(s.lat, s.lng, "환", MARK_TRANSFER) }
        }
        d?.let { marks += TripMark(it.lat, it.lng, "도", MARK_END) }
        c.setTrip(segs, marks)
        c.fitTo(all)
    }

    /** 걷는 길: 카카오 도보 경로 (키가 없거나 30m 안이면 직선) */
    private suspend fun walkLine(a: LatLng, b: LatLng): List<LatLng> {
        if (!com.eunho.tagohub.data.KakaoExtra.available || com.eunho.tagohub.core.Geo.meters(a.lat, a.lng, b.lat, b.lng) < 30) return listOf(a, b)
        return com.eunho.tagohub.data.KakaoExtra.walk(a, b) ?: listOf(a, b)
    }

    /** 버스 구간 선: 전주시 노선 모양을 탄 정류장~내린 정류장 사이만 잘라서. 실패하면 정류장끼리 직선 */
    private suspend fun legPath(leg: TripLeg, db: StopDb?): List<LatLng> {
        leg.path?.takeIf { it.size >= 2 }?.let { return it } // 카카오 길찾기: 실제 경로
        val straight = listOfNotNull(
            leg.boardLat?.let { la -> leg.boardLng?.let { LatLng(la, it) } },
            leg.alightLat?.let { la -> leg.alightLng?.let { LatLng(la, it) } },
        )
        if (leg.subway) return straight // 지하철 구간 (서울시 경로검색): 타는 역 → 내리는 역
        val city = routeCity[leg.routeId] ?: if (com.eunho.tagohub.data.SeoulBus.isSeoul(leg.routeId)) "11" else "35010"
        val stops = com.eunho.tagohub.ui.bus.routeStopsFor(repo, leg.routeId, city).filter { it.lat != null && it.lng != null }
        val i = stops.indexOfFirst { it.ord == leg.fromSeq }.takeIf { it >= 0 } ?: stops.indexOfFirst { it.nodeId == leg.boardStopId }
        val j = stops.indexOfFirst { it.ord == leg.toSeq }.takeIf { it > i } ?: stops.indexOfLast { it.nodeId == leg.alightStopId }
        if ((i < 0 || j <= i) && straight.size == 2) return straight
        if (i < 0 || j <= i) {
            val a = db?.byId(leg.boardStopId)
            val b = db?.byId(leg.alightStopId)
            return listOfNotNull(
                a?.let { s -> s.lat?.let { LatLng(it, s.lng ?: 0.0) } },
                b?.let { s -> s.lat?.let { LatLng(it, s.lng ?: 0.0) } },
            )
        }
        val pts = stops.map { LatLng(it.lat!!, it.lng!!) }
        // 노선 모양: 휴대폰에 저장된 것, 없으면 전주 노선은 전주시 노선 모양
        val shape = withContext(Dispatchers.IO) { com.eunho.tagohub.data.StaticCache.shape(leg.routeId) }
            ?: if (Jeonju.isJeonju(leg.routeId)) (Graph.jeonju.drawing(leg.routeId) as? TagoResult.Success)?.data else null
        if (shape != null && shape.size >= 2) {
            val idx = Shapes.stopIndices(shape, pts)
            if (idx[j] > idx[i]) return listOf(pts[i]) + shape.subList(idx[i], idx[j] + 1) + pts[j]
        }
        return pts.subList(i, j + 1)
    }

    companion object {
        val LEG_COLORS = listOf(0xFF2E8B57.toInt(), 0xFF3D5BAB.toInt())
        val WALK = 0xFF7A8494.toInt()
        val MARK_START = 0xFF1A73E8.toInt()
        val MARK_TRANSFER = 0xFFE08A00.toInt()
        val MARK_END = 0xFFD7262E.toInt()
    }
}

/** 길찾기 결과 정렬 (최단시간·최소도보·최소환승) — 화면에서도 쓰므로 파일 최상위 */
enum class PlanSort(val label: String) { Fast("최단시간"), LessWalk("최소도보"), LessTransfer("최소환승") }
