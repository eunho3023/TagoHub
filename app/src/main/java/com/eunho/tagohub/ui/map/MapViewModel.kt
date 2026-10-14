package com.eunho.tagohub.ui.map

import android.app.Application
import android.content.Context
import androidx.compose.ui.graphics.toArgb
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.toLoad
import com.eunho.tagohub.core.valueOrNull
import com.eunho.tagohub.data.BusRoute
import com.eunho.tagohub.data.BusSearch
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.City
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.data.GpsTracker
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.RoadShapeClient
import com.eunho.tagohub.data.Shapes
import com.eunho.tagohub.data.Place
import com.eunho.tagohub.data.SeoulLines
import com.eunho.tagohub.data.SeoulMappers
import com.eunho.tagohub.data.StationGroup
import com.eunho.tagohub.data.StationIndex
import com.eunho.tagohub.data.StopDb
import com.eunho.tagohub.data.RouteDirections
import com.eunho.tagohub.data.RouteStop
import com.eunho.tagohub.data.MergedStop
import com.eunho.tagohub.data.StopMerge
import com.eunho.tagohub.data.DayKind
import com.eunho.tagohub.data.Jeonju
import com.eunho.tagohub.data.JeonjuMappers
import com.eunho.tagohub.data.JeonjuTimetable
import com.eunho.tagohub.data.RouteTimes
import com.eunho.tagohub.data.TripPlanner
import com.eunho.tagohub.data.SubwayArrival
import com.eunho.tagohub.data.SubwayTrain
import com.eunho.tagohub.ui.bus.BusDest
import com.eunho.tagohub.ui.bus.StopRouteRow
import com.eunho.tagohub.ui.bus.loadRouteData
import com.eunho.tagohub.ui.bus.loadStopBoard
import com.eunho.tagohub.ui.bus.loadDirection
import com.eunho.tagohub.ui.bus.refreshBusData
import com.eunho.tagohub.ui.bus.routeStopsFor
import com.eunho.tagohub.ui.bus.routeInfoFor
import com.eunho.tagohub.ui.subway.StationDetail
import com.eunho.tagohub.ui.subway.StationDetailController
import com.eunho.tagohub.ui.theme.busRouteColor
import com.eunho.tagohub.ui.theme.subwayLineColor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.LocalDateTime

enum class MapPanel { None, Stop, Route, Station, Train, Plan }

/** 열차 한 대의 행선지까지 경로 (열차를 누르거나, 역 도착정보의 "~행"을 누르면) */
data class TrainView(
    val lineId: String,
    val lineName: String,
    val dest: String?,
    /** 지도에서 강조할 열차 id (실시간 위치와 못 맞추면 null) */
    val trainId: String?,
    val trainNo: String?,
    /** "종각 출발" 같은 현재 상태 */
    val status: String?,
    val path: List<MetroStation>,
    /** 패널·지도에서 강조할 역 (누른 역 또는 열차가 있는 역) */
    val focusNorm: String?,
    /** 열차가 지금 있는 역 */
    val trainAtNorm: String?,
)

/** 버스 탭 / 지하철 탭 — 같은 지도 화면을 레이어만 다르게 */
/** Transit = 버스·지하철을 한 지도에 (정류장·역·버스·열차 모두) */
enum class MapMode { Bus, Subway, Transit }

class MapViewModel(app: Application) : AndroidViewModel(app) {
    private val repo = Graph.repo
    private val seoul = Graph.seoul

    /** "kakao" | null(준비 전·실패) */
    val mode = MutableStateFlow<String?>(null)
    val banner = MutableStateFlow<String?>(null)
    val needZoom = MutableStateFlow(true)
    /** 진행 중인 요청 수 (> 0 이면 상단에 진행 막대) */
    val busy = MutableStateFlow(0)
    val panel = MutableStateFlow(MapPanel.None)
    val selected = MutableStateFlow<BusDest.Stop?>(null)
    val route = MutableStateFlow<BusDest.Route?>(null)
    val wantsLocate = MutableStateFlow(false)

    /** 새로고침 버튼: 값이 바뀌면 화면의 갱신 루프들이 즉시 다시 돈다 */
    val refreshTick = MutableStateFlow(0)

    var mapMode = MapMode.Bus
        private set
    private val busLayer get() = mapMode == MapMode.Bus || mapMode == MapMode.Transit
    private val subwayLayer get() = mapMode == MapMode.Subway || mapMode == MapMode.Transit

    // ── 버스 검색 ──
    private val prefs = app.getSharedPreferences("bus", Context.MODE_PRIVATE)

    /** 즐겨찾기 (정류장·노선) — 휴대폰 안에 저장, 로그인 없이 */
    val favorites = MutableStateFlow(com.eunho.tagohub.data.FavoriteCodec.decode(prefs.getString("favorites", null)))

    fun toggleFavorite(f: com.eunho.tagohub.data.Favorite) {
        favorites.value = com.eunho.tagohub.data.FavoriteCodec.toggle(favorites.value, f)
        prefs.edit().putString("favorites", com.eunho.tagohub.data.FavoriteCodec.encode(favorites.value)).apply()
    }

    // ───────── 카카오 지도: 겹쳐 보기, POI 누르기, 꾹 누르기 → 출발·도착 ─────────

    /** 지도 겹쳐 보기 (교통정보·자전거도로·지형도) — 저장해 두고 다음에도 */
    val overlays = MutableStateFlow(prefs.getString("overlays", null).orEmpty().split(',').filter { it.isNotBlank() }.toSet())

    fun toggleOverlay(id: String) {
        overlays.value = if (id in overlays.value) overlays.value - id else overlays.value + id
        prefs.edit().putString("overlays", overlays.value.joinToString(",")).apply()
        canvas?.setOverlays(overlays.value)
    }

    /** 지도에서 고른 곳 (꾹 누른 곳, 정류장·역이 아닌 POI) — 출발·도착으로 쓰기 */
    data class PickedPlace(val name: String, val sub: String?, val lat: Double, val lng: Double)
    val picked = MutableStateFlow<PickedPlace?>(null)

    /** 카카오 지도의 POI 를 누름: 정류장이면 정류장, 지하철역이면 역, 아니면 출발·도착 고르기 */
    fun onMapPoi(name: String, lat: Double, lng: Double) {
        viewModelScope.launch {
            // 1) 지하철역: 300m 안에서 이름이 비슷한 역
            val station = index?.groups
                ?.filter { com.eunho.tagohub.core.Geo.meters(lat, lng, it.lat, it.lng) <= 300.0 }
                ?.let { gs -> com.eunho.tagohub.data.PoiMatch.pick(name, lat, lng, gs, { it.name }, { it.lat to it.lng }, 0.0) }
            if (station != null) {
                closePick()
                focusStation(station)
                return@launch
            }
            // 2) 버스 정류장: 60m 안에서 이름이 비슷한 것, 없으면 25m 안의 가장 가까운 것
            val near = stopDb?.let { db -> withContext(Dispatchers.IO) { db.near(lat, lng, 60.0, 15) } }.orEmpty().map { it.first }
            val stop = com.eunho.tagohub.data.PoiMatch.pick(name, lat, lng, near, { it.name }, { s -> s.lat?.let { la -> s.lng?.let { la to it } } }, 25.0)
            if (stop != null) {
                closePick()
                pickStop(stop)
                return@launch
            }
            // 3) 그 밖의 장소: 출발·도착으로 쓸지
            picked.value = PickedPlace(name.ifBlank { "선택한 장소" }, null, lat, lng)
            canvas?.setPin(lat, lng)
        }
    }

    /** 지도 바닥을 꾹 누름: 그 자리를 출발·도착으로 (이름은 카카오 좌표→주소로) */
    fun onMapLongPress(lat: Double, lng: Double) {
        val p = PickedPlace("선택한 위치", "주소 확인 중…", lat, lng)
        picked.value = p
        canvas?.setPin(lat, lng)
        viewModelScope.launch {
            val name = com.eunho.tagohub.data.KakaoExtra.placeName(lat, lng)
            if (picked.value === p) picked.value = p.copy(name = name ?: "선택한 위치", sub = if (name == null) "%.5f, %.5f".format(lat, lng) else null)
        }
    }

    /** 고른 곳을 출발(asOrigin) 또는 도착으로 → 길찾기 (도착이 정해지면 바로 검색) */
    fun usePicked(asOrigin: Boolean) {
        val p = picked.value ?: return
        val point = PlanPoint(p.name, p.sub, p.lat, p.lng)
        openPlanner()
        if (asOrigin) trip.origin.value = point else trip.dest.value = point
        closePick()
        if (trip.dest.value != null) trip.plan()
    }

    fun closePick() {
        picked.value = null
        canvas?.setPin(null, null)
    }

    /** 정류장 패널의 출발지·도착지 버튼 → 길찾기 */
    fun useStopAs(stop: BusStop, asOrigin: Boolean) {
        val lat = stop.lat ?: return
        val lng = stop.lng ?: return
        picked.value = PickedPlace(stop.name, stop.nodeNo?.let { "정류장 $it" }, lat, lng)
        usePicked(asOrigin)
    }

    /** 정류장 검색 결과에 '○○ 방면' (앱 안 노선 정류장 순서에서 다음 정류장, 한 번 구하면 기억) */
    private val directionCache = java.util.concurrent.ConcurrentHashMap<String, String>()
    suspend fun stopDirection(stopId: String): String? {
        directionCache[stopId]?.let { return it.ifEmpty { null } }
        val d = stopDb?.let { db -> withContext(Dispatchers.IO) { db.nextStopName(stopId) } }
        directionCache[stopId] = d.orEmpty()
        return d
    }

    /** 노선 패널의 즐겨찾기: 기점·종점은 노선 정보에서 */
    fun routeFavorite(d: BusDest.Route): com.eunho.tagohub.data.Favorite {
        val r = d.info.value.valueOrNull()
        return com.eunho.tagohub.data.Favorite.of(d.routeId, d.routeNo, d.routeType ?: r?.routeType, d.cityCode, r?.startName, r?.endName)
    }

    fun openFavorite(f: com.eunho.tagohub.data.Favorite) = when (f.kind) {
        com.eunho.tagohub.data.Favorite.Kind.Stop -> pickStop(f.toStop())
        com.eunho.tagohub.data.Favorite.Kind.Route -> pickRoute(f.toRoute())
    }
    val busCity = MutableStateFlow(
        prefs.getString("cityCode", null)?.let { City(it, prefs.getString("cityName", null).orEmpty()) }
    )

    /** 지도 위치로 자동 판별한 지역 (검색에 우선 사용) */
    val autoCity = MutableStateFlow<City?>(null)
    private var cityAt: Pair<Double, Double>? = null
    private var cityJob: Job? = null
    /** 마지막 검색에 쓴 지역 (노선 열 때 사용) */
    private var searchCity: City? = null

    /** 사용자가 고른 지도 종류. null 이면 자동 (다크모드 → 야간지도, 아니면 기본지도) */
    val baseMap = MutableStateFlow(prefs.getString("baseMap", null)?.let { n -> BaseMap.entries.firstOrNull { it.name == n } })
    private var dark = false
    val busResult = MutableStateFlow<Load<BusSearch>>(Load.Idle)
    /** 전국 검색 진행 (끝낸 지역 수, 전체 지역 수). 끝나면 null */
    val busProgress = MutableStateFlow<Pair<Int, Int>?>(null)
    /** 도시코드 → 이름 (검색 결과에 지역 표시) */
    val cityNames = MutableStateFlow<Map<String, String>>(emptyMap())
    private var busSearchJob: Job? = null

    // ── 지하철 ──
    val trainsOn = MutableStateFlow(true)
    val trainCount = MutableStateFlow(0)
    val station = MutableStateFlow<StationDetail?>(null)
    val trainView = MutableStateFlow<TrainView?>(null)

    /** 정류장 예상 시간표 (null = 안 보는 중) */
    val stopTimetable = MutableStateFlow<Load<List<RouteTimes>>?>(null)
    val timetableDay = MutableStateFlow(DayKind.today())
    private var timetableJob: Job? = null

    /** 버스 길찾기 (전주시 시내버스: 직통 + 1회 환승) */
    val trip = TripController(
        scope = viewModelScope,
        repo = repo,
        canvas = { canvas },
        me = { me?.let { LatLng(it.first, it.second) } },
        center = { lastView?.let { LatLng(it.first, it.second) } },
        busy = busy,
    )
    val stations = StationDetailController(viewModelScope, repo, seoul)
    private var index: StationIndex? = null
    private val trainsByLine = HashMap<String, List<SubwayTrain>>()
    private var trainLines: Set<String> = emptySet()
    private var trainJob: Job? = null
    private var lastTrainError: String? = null

    // ── 지도 상태 ──
    /** 합쳐진 정류장: 대표 정류장 id → (대표 + 같은 자리의 다른 시군 정류장) */
    private val merged = HashMap<String, MergedStop>()

    /** 목록에 보일 정류장 번호 (합쳐진 정류장은 "30690 · 3110313") */
    fun stopNumbers(s: BusStop): String? = merged[s.nodeId]?.numbers?.ifBlank { null } ?: s.nodeNo

    /** 지도에 올린 버스 정류소 캐시. 지도를 옮겨도 이미 받은 정류소는 유지 (최대 MAX_STOPS) */
    private val stops = LinkedHashMap<String, BusStop>()
    private var me: Pair<Double, Double>? = null
    private var lastFetch: Pair<Double, Double>? = null
    /** 탭을 바꿨다 돌아와도 같은 위치·배율로 (lat, lng, zoom) */
    private var lastView: Triple<Double, Double, Double>? = null
    private var lastLevel = 0
    private var lastBox: DoubleArray? = null
    private var nearbyJob: Job? = null
    private var canvas: MapCanvas? = null

    /** 내장 전국 정류장 DB (열기 전·실패 시 null → TAGO 주변 정류소 API 로 대체) */
    private var stopDb: StopDb? = null

    init {
        viewModelScope.launch {
            index = withContext(Dispatchers.IO) {
                runCatching {
                    app.assets.open("subway_stations.tsv").bufferedReader().use { StationIndex.parseTsv(it.readText()) }
                }.getOrNull()
            }
            if (index == null) banner.value = "지하철역 데이터를 읽지 못했습니다."
            pushStations()
            // 지도가 역 데이터보다 먼저 준비돼 화면 범위를 이미 알려줬으면, 지금 실시간 열차를 시작
            updateTrainLines()
        }
        viewModelScope.launch {
            stopDb = StopDb.get(app)
            val db = stopDb
            if (db == null) {
                banner.value = "저장된 정류장 데이터를 열지 못해 주변 정류장을 서버에서 받아옵니다."
                return@launch
            }
            cityNames.value = withContext(Dispatchers.IO) { db.cityNames() }
            // DB 가 열리기 전에 지도가 이미 확대돼 있었다면 지금 채운다
            lastBox?.let { box -> if (lastLevel >= 2 && busLayer) loadStopsInView(box, immediate = true) }
        }
    }

    private suspend fun <T> tracked(block: suspend () -> T): T {
        busy.value++
        try {
            return block()
        } finally {
            busy.value--
        }
    }

    fun configure(m: MapMode) {
        mapMode = m
    }

    // ───────────────────────── 지도 연결 ─────────────────────────

    private val viewKey get() = "view_${mapMode.name}"

    /** 마지막으로 보던 지도 (탭별). 다음 실행 때 전국 지도 대신 바로 이 자리에서 시작 */
    private fun saveView(lat: Double, lng: Double, zoom: Double) {
        prefs.edit().putString(viewKey, "$lat,$lng,$zoom").apply()
    }

    private fun savedView(): Triple<Double, Double, Double>? {
        val p = prefs.getString(viewKey, null)?.split(',')?.mapNotNull { it.toDoubleOrNull() } ?: return null
        // 전국이 보일 만큼 축소해 둔 상태는 저장값으로 쓰지 않음 (그땐 내 위치로 시작)
        return if (p.size == 3 && p[2] >= 10.0) Triple(p[0], p[1], p[2]) else null
    }

    /** 이번에 저장된 자리(또는 탭 전환 전 자리)에서 시작했는지. 아니면 내 위치로 이동 */
    var startsAtSaved = false
        private set

    /** 위치 권한을 이번 실행에서 아직 안 물었으면 true (한 번만) */
    private var askedLocation = false
    fun askLocationOnce(): Boolean = if (askedLocation) false else { askedLocation = true; true }

    fun attach(c: MapCanvas, isDark: Boolean) {
        canvas = c
        dark = isDark
        // 지도를 다시 만들어도(인증 실패 후 새로고침으로 다시 시도) 실시간 열차가 이어지게
        updateTrainLines()
        c.setBase(effectiveBase())
        c.setOverlays(overlays.value)
        picked.value?.let { c.setPin(it.lat, it.lng) }
        pushStations()
        pushStops()
        pushRoute()
        pushBuses()
        pushTrains()
        pushSelectedStation()
        trainView.value?.let {
            c.selectTrain(it.trainId)
            pushTrainPath()
        }
        trip.selected.value?.let { trip.select(it) }
        me?.let { (lat, lng) -> c.setMe(lat, lng) }
        val v = lastView ?: savedView()
        // 앱을 새로 켰을 때는 늘 내 위치로. 탭을 바꿨다 돌아온 경우(lastView 있음)만 보던 자리 유지.
        // 저장된 자리는 위치를 받기 전까지 잠깐 보여주는 용도
        startsAtSaved = lastView != null
        if (v != null) {
            c.moveTo(v.first, v.second, v.third)
        }
        // 저장된 위치로 먼저 띄우고, 위치 권한이 있으면 내 위치 점도 바로 표시
        wantsLocate.value = true
    }

    fun detach(c: MapCanvas) {
        if (canvas === c) canvas = null
        mode.value = null
        // 지도가 안 보이면 실시간 호출 중단 (서울 API 일일 한도 보호)
        trainJob?.cancel()
        trainLines = emptySet()
        nearbyJob?.cancel()
    }

    fun onMapMode(m: String) { mode.value = m }
    fun onMapProblem(message: String) { banner.value = message }

    /** 카카오맵 인증 실패: 안내를 띄우고, REST 호출로 원인을 점검해 카카오가 보낸 이유까지 덧붙인다 */
    fun onKakaoFailed(reason: String, packageName: String, keyHash: String?) {
        banner.value = "$reason\n\n원인 점검 중…"
        viewModelScope.launch {
            val nativeAsRest = com.eunho.tagohub.data.KakaoProbe.probe(com.eunho.tagohub.BuildConfig.KAKAO_NATIVE_KEY)
            val restKey = com.eunho.tagohub.BuildConfig.KAKAO_REST_KEY.trim()
            val rest = if (restKey.isNotEmpty() && restKey != com.eunho.tagohub.BuildConfig.KAKAO_NATIVE_KEY.trim())
                com.eunho.tagohub.data.KakaoProbe.probe(restKey) else null
            val d = com.eunho.tagohub.data.KakaoDiag.diagnose(packageName, keyHash, nativeAsRest, rest)
            android.util.Log.w("TagoKakao", "점검: $d")
            banner.value = "$reason\n\n[원인 점검] $d"
        }
    }

    fun onMoveEnd(lat: Double, lng: Double, zoom: Double, level: Int, s: Double, w: Double, n: Double, e: Double) {
        lastView = Triple(lat, lng, zoom)
        saveView(lat, lng, zoom)
        lastLevel = level
        lastBox = doubleArrayOf(s, w, n, e)
        needZoom.value = if (subwayLayer) level < 1 else level < 2
        updateTrainLines()
        if (level >= 2 && busLayer) loadStopsInView(doubleArrayOf(s, w, n, e))
        if (level >= 1 && busLayer) detectCity(lat, lng)
    }

    /**
     * 화면 안 정류장. 내장 DB 에서 바로 읽으므로 API 호출이 없다 (약 1ms).
     * DB 를 못 열었을 때만 TAGO 주변 정류소 API 로 대체.
     */
    private fun loadStopsInView(box: DoubleArray, immediate: Boolean = false) {
        val db = stopDb
        if (db == null) {
            lastView?.let { (lat, lng, _) -> loadNearbyStops(lat, lng, immediate) }
            return
        }
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            if (!immediate) delay(120)
            // 화면보다 조금 넓게 읽어 살짝 움직여도 정류장이 비지 않게
            val dLat = (box[2] - box[0]) * 0.15
            val dLng = (box[3] - box[1]) * 0.15
            val list = withContext(Dispatchers.IO) { db.inBox(box[0] - dLat, box[1] - dLng, box[2] + dLat, box[3] + dLng) }
            // 시군마다 따로 등록된 같은 자리 정류장은 마커 하나로 (지금 지역 정류장을 대표로)
            val groups = withContext(Dispatchers.Default) { StopMerge.merge(list, autoCity.value?.code) }
            stops.clear()
            groups.forEach { g ->
                stops[g.main.nodeId] = g.main
                if (g.others.isNotEmpty()) merged[g.main.nodeId] = g
            }
            pushStops()
        }
    }

    private fun loadNearbyStops(lat: Double, lng: Double, immediate: Boolean = false) {
        nearbyJob?.cancel()
        nearbyJob = viewModelScope.launch {
            if (!immediate) delay(350) // 연속 이동 중 호출 억제
            lastFetch?.let { (la, ln) -> if (Geo.meters(la, ln, lat, lng) < 250) return@launch }
            when (val r = tracked { repo.busStopsNear(lat, lng) }) {
                is TagoResult.Success -> {
                    r.data.stops.filter { it.lat != null && it.lng != null }.forEach { stops[it.nodeId] = it }
                    trimStops(lat, lng)
                    lastFetch = lat to lng
                    pushStops()
                }
                is TagoResult.Error -> banner.value = r.message
            }
        }
    }

    /**
     * 지도 중심의 지역(TAGO 도시코드)을 자동 판별.
     * 이미 받은 주변 정류소가 있으면 그걸 쓰고, 없으면 중심 좌표로 주변 정류소 1회 조회.
     */
    private fun detectCity(lat: Double, lng: Double) {
        cityAt?.let { (la, ln) -> if (Geo.meters(la, ln, lat, lng) < 3000) return }
        cityJob?.cancel()
        cityJob = viewModelScope.launch {
            delay(300)
            val db = stopDb
            val code = if (db != null) {
                withContext(Dispatchers.IO) { db.nearestCity(lat, lng) }
            } else {
                (repo.busStopsNear(lat, lng) as? TagoResult.Success)?.data?.stops?.firstOrNull { it.cityCode.isNotBlank() }?.cityCode
            } ?: return@launch
            cityAt = lat to lng
            if (autoCity.value?.code != code) {
                autoCity.value = City(code, cityNames.value[code] ?: repo.busCityName(code) ?: "지역 $code")
            }
        }
    }

    fun onStopClick(id: String) {
        closeStopTimetable()
        if (subwayLayer) {
            index?.group(id)?.let {
                openStation(it)
                return
            }
            if (!busLayer) return
        }
        val stop = stops[id] ?: routeStopAsBusStop(id) ?: return
        selected.value = BusDest.Stop(stop, merged[stop.nodeId]?.others)
        panel.value = MapPanel.Stop
        pushStops()
        // 누른 정류장을 현위치 버튼과 똑같이 지도 정중앙에
        if (stop.lat != null && stop.lng != null) canvas?.focus(stop.lat, stop.lng)
    }

    fun onStationClick(id: String) {
        val g = index?.group(id) ?: return
        openStation(g)
        canvas?.focus(g.lat, g.lng)
    }

    /** 지도에서 열차를 누름 → 행선지까지 경로 */
    fun onTrainClick(id: String) {
        val idx = index ?: return
        val t = trainsByLine.values.flatten().firstOrNull { "t:${it.lineId}:${it.trainNo}" == id } ?: return
        val cur = idx.locate(t.lineId, t.stationName) ?: return
        val dest = t.destName?.let { idx.locate(t.lineId, it) }
        showTrain(
            TrainView(
                lineId = t.lineId,
                lineName = SeoulLines.byId[t.lineId]?.apiName ?: cur.displayLine,
                dest = t.destName,
                trainId = id,
                trainNo = t.trainNo,
                status = "${t.stationName} ${SeoulMappers.statusText(t.status)}".trim(),
                path = idx.pathToward(t.lineId, cur, dest),
                focusNorm = cur.norm,
                trainAtNorm = cur.norm,
            ),
        )
    }

    /** 역 상세의 실시간 도착 "~행"을 누름 → 그 열차가 지금 있는 역부터 행선지까지 */
    fun showArrivalPath(g: StationGroup, a: SubwayArrival) {
        val idx = index ?: return
        val here = g.stations.firstOrNull { it.seoulLine?.id == a.lineId } ?: return
        val at = a.current?.let { idx.locate(a.lineId, it) }
        val dest = a.dest?.let { idx.locate(a.lineId, it) }
        val from = at ?: here
        val train = a.trainNo?.trimStart('0')?.let { no ->
            trainsByLine[a.lineId]?.firstOrNull { it.trainNo.trimStart('0') == no }
        }
        showTrain(
            TrainView(
                lineId = a.lineId,
                lineName = SeoulLines.byId[a.lineId]?.apiName ?: here.displayLine,
                dest = a.dest,
                trainId = train?.let { "t:${it.lineId}:${it.trainNo}" },
                trainNo = a.trainNo,
                status = a.message.ifBlank { null },
                path = idx.pathToward(a.lineId, from, dest),
                focusNorm = here.norm,
                trainAtNorm = at?.norm,
            ),
        )
    }

    private fun showTrain(v: TrainView) {
        trainView.value = v
        panel.value = MapPanel.Train
        canvas?.selectTrain(v.trainId)
        pushTrainPath() // 열차 경로도 지도는 보던 그대로
    }

    /** 경로의 역을 누름 → 지도 이동 + 역 상세 */
    fun focusMetro(s: MetroStation) {
        val g = index?.groupIdOf(s)?.let { index?.group(it) } ?: return
        focusStation(g)
    }

    private fun openStation(g: StationGroup) {
        if (station.value?.group?.id != g.id) station.value = stations.open(g)
        panel.value = MapPanel.Station
        pushSelectedStation()
    }

    private fun routeStopAsBusStop(id: String): BusStop? {
        val d = route.value ?: return null
        val s = d.stops.value.valueOrNull()?.firstOrNull { it.nodeId == id } ?: return null
        return BusStop(d.cityCode, s.nodeId, s.name, s.nodeNo, s.lat, s.lng)
    }

    // ───────────────────────── 버스 검색 ─────────────────────────

    suspend fun cityPlaces(q: String) = repo.busCityPlaces(q)

    /** 직접 지역을 바꿀 때 (자동 판별 대신). 지도를 크게 옮기면 다시 자동으로 돌아간다 */
    fun setBusCity(p: Place) {
        val c = City(p.id, p.name)
        busCity.value = c
        autoCity.value = c
        cityAt = lastView?.let { it.first to it.second }
        prefs.edit().putString("cityCode", p.id).putString("cityName", p.name).apply()
        busResult.value = Load.Idle
    }

    /**
     * 정류소명·정류소번호·노선번호 전국 통합 검색.
     * - 정류장: 내장 DB(전국 227,059곳)에서 바로 → API 호출 없음
     * - 노선번호: TAGO 는 도시별로만 검색돼서, 검색어에 숫자가 있을 때만 전국 도시를 3곳씩 동시에 조회
     *   (지금 지도 지역 → 같은 도 → 나머지 순, 찾는 대로 추가)
     */
    fun searchBus(q: String) {
        val k = q.trim()
        if (k.isEmpty()) return
        busSearchJob?.cancel()
        busSearchJob = viewModelScope.launch {
            busResult.value = Load.Loading
            val here = (autoCity.value ?: busCity.value)?.code
            val db = stopDb
            val localStops = if (db != null) withContext(Dispatchers.IO) { db.search(k) } else emptyList()
            // 같은 자리의 시군별 정류장은 한 줄로 (번호 두 개)
            val localGroups = withContext(Dispatchers.Default) { StopMerge.merge(localStops, here) }
            localGroups.filter { it.others.isNotEmpty() }.forEach { merged[it.main.nodeId] = it }
            val stopsSorted = localGroups.map { it.main }
                .sortedWith(compareBy({ it.cityCode != here }, { here == null || it.cityCode.take(2) != here.take(2) }))
            val wantRoutes = k.any(Char::isDigit)
            busResult.value = Load.Ok(BusSearch(stopsSorted, emptyList()))
            if (!wantRoutes) {
                if (db == null) busResult.value = Load.Fail("저장된 정류장 데이터를 열지 못했습니다.")
                return@launch
            }

            val cities = when (val r = busCitiesCached()) {
                is TagoResult.Success -> r.data
                is TagoResult.Error -> {
                    busResult.value = Load.Ok(BusSearch(stopsSorted, emptyList(), warning = "노선 검색 실패: ${r.message}"))
                    return@launch
                }
            }
            if (cityNames.value.isEmpty()) cityNames.value = cities.associate { it.code to it.name }
            val kotsaCities = stopDb?.routeCities.orEmpty()
            val order = cities.filter {
                it.code != com.eunho.tagohub.data.Jeju.CITY && !com.eunho.tagohub.data.Gyeonggi.isGyeonggiCity(it.code) && it.code !in kotsaCities
            }.sortedWith(
                compareBy<City>({ it.code != here }, { here == null || it.code.take(2) != here.take(2) }, { it.name })
            )
            val rank = order.withIndex().associate { (i, c) -> c.code to i } +
                (com.eunho.tagohub.data.Jeju.CITY to if (here == com.eunho.tagohub.data.Jeju.CITY) -1 else order.size) +
                ("31" to if (here != null && com.eunho.tagohub.data.Gyeonggi.isGyeonggiCity(here)) -1 else order.size) +
                kotsaCities.associateWith { if (it == here) -1 else order.size }
            val foundRoutes = ArrayList<BusRoute>()
            // 교통안전공단 노선 (농어촌·시외 지역): 앱 안 목록에서 바로
            stopDb?.let { db -> foundRoutes += withContext(Dispatchers.IO) { db.searchRoutes(k) } }
            // 경기: GBIS 노선번호 검색 한 번으로 경기 전체 (TAGO 시군별 조회 대신)
            // 경기: 노선 번호부(처음 한 번 받아 계속 저장)에서, 없으면 경기 API
            val gBook = tracked { com.eunho.tagohub.data.GyeonggiRouteBook.get(Graph.gyeonggi) }
            if (gBook != null) foundRoutes += com.eunho.tagohub.data.GyeonggiRouteBook.search(gBook, k)
            else (tracked { Graph.gyeonggi.searchRoutes(k) } as? TagoResult.Success)?.data?.let { foundRoutes += it }
            // 제주: 제주 BIS 노선 목록에서 번호로 (TAGO 대신)
            (Graph.jeju.routes() as? TagoResult.Success)?.data
                ?.filter { it.routeNo.contains(k, ignoreCase = true) }
                ?.let { foundRoutes += it }
            var done = 0
            var failed = 0
            busProgress.value = 0 to order.size
            val gate = Semaphore(3)
            coroutineScope {
                order.forEach { c ->
                    launch {
                        val r = gate.withPermit { tracked { repo.routesByNo(c.code, k) } }
                        done++
                        when (r) {
                            is TagoResult.Success -> foundRoutes += r.data.filter { x -> foundRoutes.none { it.routeId == x.routeId } }
                            is TagoResult.Error -> failed++
                        }
                        busProgress.value = done to order.size
                        busResult.value = Load.Ok(
                            BusSearch(
                                stops = stopsSorted,
                                routes = foundRoutes.sortedWith(
                                    compareBy<BusRoute>({ rank[it.cityCode] ?: Int.MAX_VALUE }).thenBy(com.eunho.tagohub.core.naturalOrder) { it.routeNo }
                                ),
                                warning = if (failed > 0) "${failed}개 지역은 노선 응답이 없어 빠졌을 수 있습니다." else null,
                            )
                        )
                    }
                }
            }
            busProgress.value = null
        }
    }

    fun clearBusSearch() {
        busSearchJob?.cancel()
        busProgress.value = null
        busResult.value = Load.Idle
    }

    fun pickStop(s: BusStop) {
        clearBusSearch()
        closeStopTimetable()
        stops[s.nodeId] = s
        if (s.lat != null && s.lng != null) canvas?.focus(s.lat, s.lng)
        selected.value = BusDest.Stop(s, merged[s.nodeId]?.others)
        panel.value = MapPanel.Stop
        pushStops()
    }

    fun pickRoute(r: BusRoute) {
        val code = r.cityCode.ifBlank { (searchCity ?: autoCity.value ?: busCity.value)?.code.orEmpty() }
        if (code.isEmpty()) return
        clearBusSearch()
        route.value = BusDest.Route(code, r.routeId, r.routeNo, r.routeType, null, fitOnOpen = true)
        panel.value = MapPanel.Route
    }

    /** 노선 타임라인에서 정류소를 누름 */
    fun focusRouteStop(nodeId: String) {
        val s = routeStopAsBusStop(nodeId) ?: return
        if (s.lat != null && s.lng != null) canvas?.focus(s.lat, s.lng)
        onStopClick(nodeId)
    }

    // ───────────────────────── 지하철 검색 ─────────────────────────

    /** 역 이름 검색 (내장 데이터) */
    fun searchStations(q: String): List<StationGroup> {
        val idx = index ?: return emptyList()
        val k = q.replace(" ", "").removeSuffix("역")
        if (k.isEmpty()) return emptyList()
        return idx.groups
            .filter { it.name.contains(k) }
            .sortedWith(compareBy({ !it.name.startsWith(k) }, { it.name.length }, { it.name }))
            .take(8)
    }

    fun focusStation(g: StationGroup) {
        canvas?.focus(g.lat, g.lng)
        openStation(g)
    }

    // ───────────────────────── 사용자 동작 ─────────────────────────

    fun setMe(lat: Double, lng: Double, center: Boolean) {
        me = lat to lng
        canvas?.setMe(lat, lng)
        if (center) canvas?.focus(lat, lng)
    }

    fun showRoute(row: StopRouteRow) {
        val s = selected.value ?: return
        // 다른 시군 노선이면 그 시군 도시코드로 연다
        route.value = BusDest.Route(row.cityCode ?: s.stop.cityCode, row.routeId, row.routeNo, row.routeType, s.stop.nodeId)
        panel.value = MapPanel.Route
    }

    fun closePanel() {
        when (panel.value) {
            MapPanel.Route -> {
                route.value = null
                canvas?.setRoute(null, emptyList(), null)
                canvas?.setBuses(emptyList())
            }
            MapPanel.Stop -> {
                selected.value = null
                pushStops()
            }
            MapPanel.Station -> {
                station.value = null
                pushSelectedStation()
            }
            MapPanel.Train -> {
                trainView.value = null
                canvas?.selectTrain(null)
                canvas?.setRoute(null, emptyList(), null)
            }
            MapPanel.Plan -> {
                // 경로 상세에서 닫으면 목록으로, 목록에서 닫으면 길찾기 종료
                if (trip.selected.value != null) {
                    trip.select(null)
                    return
                }
                trip.reset()
            }
            MapPanel.None -> Unit
        }
        panel.value = when {
            route.value != null -> MapPanel.Route
            selected.value != null -> MapPanel.Stop
            station.value != null -> MapPanel.Station
            trainView.value != null -> MapPanel.Train
            else -> MapPanel.None
        }
    }

    fun openPanel(p: MapPanel) { panel.value = p }

    // ───────────────────────── 정류장 예상 시간표 ─────────────────────────

    fun openStopTimetable(day: DayKind = timetableDay.value) {
        val d = selected.value ?: return
        timetableDay.value = day
        timetableJob?.cancel()
        stopTimetable.value = Load.Loading
        timetableJob = viewModelScope.launch { stopTimetable.value = tracked { loadStopTimes(d, day) }
            // 노선을 고를 때마다 계산하지 않고 열 때 모든 노선을 한꺼번에 (고르면 바로 보이게)
            (stopTimetable.value as? Load.Ok)?.value?.filter { !it.loaded }?.forEach { ensureTimes(it.routeId) }
        }
    }

    fun closeStopTimetable() {
        timetableJob?.cancel()
        stopTimetable.value = null
    }

    /**
     * 정류장 시간표: 이 정류장에 서는 모든 노선 (다른 시군 노선 포함).
     * - 전주 노선: 회차별 실제 출발시각(전주시 API) + 이 정류장까지 소요시간
     * - 그 외 전국 노선: 첫차·막차·배차간격(TAGO·경기·제주 노선 정보)으로 만든 예상 시각. 고를 때 계산 (노선마다 요청 2건)
     */
    private suspend fun loadStopTimes(d: BusDest.Stop, day: DayKind): Load<List<RouteTimes>> {
        val rows = (d.rows.value as? Load.Ok)?.value.orEmpty()
        val jeonju = if (Jeonju.isJeonju(d.stop.nodeId)) jeonjuStopTimes(d, day) else emptyList()
        val done = jeonju.map { it.routeId }.toSet()
        val others = rows.filter { it.routeId !in done }.distinctBy { it.routeId + "|" + it.cityCode }.map {
            RouteTimes(it.routeId, it.routeNo, it.routeType, emptyList(), it.startName, it.endName,
                loaded = false, estimated = true, cityCode = it.cityCode ?: d.stop.cityCode)
        }
        val list = jeonju + others
        if (list.isEmpty()) return Load.Fail("이 정류장을 지나는 노선을 아직 불러오지 못했습니다. 잠시 후 다시 눌러주세요.")
        return Load.Ok(list)
    }

    /** 전주 노선: 회차별 실제 출발시각 */
    private suspend fun jeonjuStopTimes(d: BusDest.Stop, day: DayKind): List<RouteTimes> {
        val routes = LinkedHashMap<String, Pair<String, String?>>()
        val ends = HashMap<String, Pair<String?, String?>>()
        d.lastRoutes.orEmpty().forEach { routes[it.routeId] = it.routeNo to it.routeType; ends[it.routeId] = it.startName to it.endName }
        d.lastArrivals.orEmpty().forEach { routes.putIfAbsent(it.routeId, it.routeNo to it.routeType) }
        val ids = routes.keys.filter(Jeonju::isJeonju).take(20)
        val db = stopDb
        val list = coroutineScope {
            ids.map { id ->
                async {
                    val rows = (Graph.jeonju.routeStopRows(id) as? TagoResult.Success)?.data ?: return@async null
                    val courses = (Graph.jeonju.courses(id) as? TagoResult.Success)?.data.orEmpty()
                    val schedule = if (day != DayKind.Weekday) (Graph.jeonju.schedule(id) as? TagoResult.Success)?.data else null
                    val coords = if (db != null) withContext(Dispatchers.IO) {
                        db.coords(rows.mapNotNull { it.str("stopStandardid", "bnodeId")?.let(Jeonju::tago) })
                    } else emptyMap()
                    val stops = JeonjuMappers.routeStops(rows) { coords[it] }
                    val line = TripPlanner.Line(id, routes[id]?.first ?: id, stops)
                    val seqs = stops.filter { it.nodeId == d.stop.nodeId }.map { it.ord }
                    val starts = JeonjuTimetable.startsFor(day, courses, schedule)
                    val times = JeonjuTimetable.stopTimes(starts, seqs) { a, b -> TripPlanner.minutesBetween(line, a, b) }
                    RouteTimes(id, routes[id]?.first ?: id, routes[id]?.second, times, ends[id]?.first, ends[id]?.second, cityCode = "35010")
                }
            }.awaitAll().filterNotNull().filter { it.minutes.isNotEmpty() }
        }
        val now = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
        return list.sortedBy { r -> r.minutes.firstOrNull { it >= now } ?: (r.minutes.first() + 1440) }
    }

    private val timesComputing = HashSet<String>()

    /** 시간표에서 노선을 고르면 그 노선 예상 시각 계산 (전국 노선) */
    fun ensureTimes(routeId: String) {
        val d = selected.value ?: return
        val item = (stopTimetable.value as? Load.Ok)?.value?.firstOrNull { it.routeId == routeId } ?: return
        if (item.loaded || !timesComputing.add(routeId)) return
        val day = timetableDay.value
        viewModelScope.launch {
            val r = try {
                tracked { estimateTimes(d, item, day) }
            } finally {
                timesComputing.remove(routeId)
            }
            val cur = stopTimetable.value as? Load.Ok ?: return@launch
            stopTimetable.value = Load.Ok(cur.value.map { x -> if (x.routeId == routeId) r else x })
        }
    }

    /** 첫차·막차·배차간격 + 이 정류장까지 소요시간(노선 정류장 거리) */
    private suspend fun estimateTimes(d: BusDest.Stop, item: RouteTimes, day: DayKind): RouteTimes {
        val raw = item.cityCode ?: d.stop.cityCode
        val city = if (raw.startsWith("TS")) stopDb?.let { db -> withContext(Dispatchers.IO) { db.tagoCity(raw) } } ?: raw else raw
        val (info, stops) = coroutineScope {
            val i = async { routeInfoFor(repo, item.routeId, city) }
            val st = async { routeStopsFor(repo, item.routeId, city) }
            i.await() to st.await()
        }
        val first = JeonjuTimetable.parseClock(info?.firstTime)
        val last = JeonjuTimetable.parseClock(info?.lastTime)
        val ids = setOf(d.stop.nodeId) + d.others.value.map { it.nodeId }
        val seqs = stops.filter { it.nodeId in ids }.map { it.ord }
        if (seqs.isEmpty()) return item.copy(loaded = true)
        // 앱에 넣어둔 실제 운행 시간표(광주 평일, 창녕 농어촌)가 있으면 그 출발 시각 — 첫차·막차 정보가 없는 노선도
        val real = if (day == DayKind.Weekday) stopDb?.let { db -> withContext(Dispatchers.IO) { db.timetable(city, item.routeNo) } }.orEmpty() else emptyMap()
        val endName = info?.endName ?: stops.lastOrNull()?.name
        val realDir = real.entries.firstOrNull { (dir, _) -> val end = endName?.take(2); end != null && dir.contains(end) }?.value
            // 맞는 방향이 없으면 방향이 하나뿐일 때만 그걸 씀 (여러 방향·노선이 같은 번호를 쓰면 다른 노선 시각을 빌려오지 않음)
            ?: real.values.singleOrNull()
        if (realDir != null && realDir.isNotEmpty()) {
            val line = TripPlanner.Line(item.routeId, item.routeNo, stops)
            val starts = realDir.map { com.eunho.tagohub.data.CourseStart(it, stops.first().ord) }
            val times = JeonjuTimetable.stopTimes(starts, seqs) { a, b -> TripPlanner.minutesBetween(line, a, b) }
            return item.copy(minutes = times, loaded = true, estimated = false)
        }
        if (info == null || first == null || last == null) return item.copy(loaded = true)
        val interval = JeonjuTimetable.intervalFor(day, info.interval, info.intervalSat, info.intervalSun)
        val starts = JeonjuTimetable.fromInterval(first, last, interval, stops.first().ord)
        val line = TripPlanner.Line(item.routeId, item.routeNo, stops)
        val times = JeonjuTimetable.stopTimes(starts, seqs) { a, b -> TripPlanner.minutesBetween(line, a, b) }
        return item.copy(minutes = times, loaded = true)
    }

    /** 노선 패널: 정류장마다 첫차·막차 예상 시각 (전주는 회차별 실제 출발, 그 외 전국은 첫차·막차 + 소요시간) */
    private suspend fun loadRouteStopTimes(d: BusDest.Route) {
        val stops = d.stops.value.valueOrNull() ?: return
        val line = TripPlanner.Line(d.routeId, d.routeNo, stops)
        if (Jeonju.isJeonju(d.routeId)) {
            val day = DayKind.today()
            val courses = (Graph.jeonju.courses(d.routeId) as? TagoResult.Success)?.data.orEmpty()
            val schedule = if (day != DayKind.Weekday) (Graph.jeonju.schedule(d.routeId) as? TagoResult.Success)?.data else null
            val starts = JeonjuTimetable.startsFor(day, courses, schedule)
            if (starts.isEmpty()) return
            d.stopTimes.value = stops.associate { st ->
                val t = JeonjuTimetable.stopTimes(starts, listOf(st.ord)) { a, b -> TripPlanner.minutesBetween(line, a, b) }
                st.ord to ((t.firstOrNull() ?: -1) to (t.lastOrNull() ?: -1))
            }.filterValues { it.first >= 0 }
            return
        }
        val info = d.info.value.valueOrNull() ?: return
        val first = JeonjuTimetable.parseClock(info.firstTime) ?: return
        var last = JeonjuTimetable.parseClock(info.lastTime) ?: return
        if (last < first) last += 1440
        val startOrd = stops.firstOrNull()?.ord ?: return
        d.stopTimes.value = stops.associate { st ->
            val off = Math.round(TripPlanner.minutesBetween(line, startOrd, st.ord)).toInt()
            st.ord to ((first + off) to (last + off))
        }
    }

    /** TAGO 도시 목록 (거의 안 바뀜 → 휴대폰에 저장, 7일) */
    private suspend fun busCitiesCached(): TagoResult<List<City>> {
        withContext(Dispatchers.IO) { com.eunho.tagohub.data.StaticCache.pairs("tago_cities") }?.let { saved ->
            return TagoResult.Success(saved.map { City(it.first, it.second) })
        }
        val r = repo.allBusCities()
        if (r is TagoResult.Success && r.data.isNotEmpty()) {
            withContext(Dispatchers.IO) { com.eunho.tagohub.data.StaticCache.putPairs("tago_cities", r.data.map { it.code to it.name }) }
        }
        return r
    }

    fun openPlanner() {
        clearBusSearch()
        if (panel.value != MapPanel.Plan) trip.reset()
        panel.value = MapPanel.Plan
    }

    fun effectiveBase(): BaseMap = baseMap.value ?: BaseMap.Base

    fun setBaseMap(type: BaseMap) {
        baseMap.value = type
        prefs.edit().putString("baseMap", type.name).apply()
        canvas?.setBase(type)
    }

    /** 새로고침: 열린 패널·실시간 열차·주변 정류소를 바로 다시 받는다 */
    fun refreshNow() {
        refreshTick.value++
        lastFetch = null
        lastBox?.let { box -> if (lastLevel >= 2 && busLayer) loadStopsInView(box, immediate = true) }
        trainLines = emptySet()
        trainJob?.cancel()
        updateTrainLines()
    }

    fun toggleTrains() {
        trainsOn.value = !trainsOn.value
        updateTrainLines()
    }

    // ───────────────────────── 화면이 떠 있는 동안의 루프 ─────────────────────────

    /** TAGO 실시간 버스 서비스 지역 (도시 목록에 없는 지역은 정류장 위치만 제공) */
    private var tagoCities: Set<String>? = null

    suspend fun runStop(d: BusDest.Stop) {
        val supported = tagoCities ?: (busCitiesCached() as? TagoResult.Success)?.data?.map { it.code }?.toSet()?.also { tagoCities = it }
        if (supported != null && d.stop.cityCode !in supported && !com.eunho.tagohub.data.Jeju.isJeju(d.stop.nodeId) && !com.eunho.tagohub.data.Gyeonggi.isGyeonggi(d.stop.nodeId) &&
            !Jeonju.isJeonju(d.stop.nodeId) && !com.eunho.tagohub.data.SeoulBus.isSeoul(d.stop.nodeId) &&
            // 서울처럼 TAGO 실시간은 없어도 앱 안 노선 데이터가 있으면 노선 목록은 보여준다
            stopDb?.let { db -> withContext(Dispatchers.IO) { db.stopRoutes(d.stop.nodeId) } }.isNullOrEmpty()) {
            val name = cityNames.value[d.stop.cityCode] ?: "이 지역"
            d.rows.value = Load.Fail("$name 은(는) 국토교통부 TAGO 실시간 버스 서비스 지역이 아니라 도착정보를 받을 수 없습니다. 정류장 위치만 표시합니다.")
            return
        }
        var first = true
        while (true) {
            tracked { loadStopBoard(repo, d) }
            if (first) {
                first = false
                viewModelScope.launch { loadDirection(repo, d) }
            }
            delay(15_000)
        }
    }

    suspend fun runRoute(d: BusDest.Route) {
        val first = d.stops.value.valueOrNull() == null
        if (first) tracked { loadRouteData(repo, d) }
        if (!d.dirChosen) {
            // 정류장에서 노선을 열었으면 그 정류장을 지나는 방향부터
            d.dirChosen = true
            val l = located(d)
            d.dir.value = RouteDirections.dirOf(l, RouteDirections.turnIndex(l), d.focusNodeId)
        }
        pushRoute()
        // 노선 검색에서 열었을 때만 노선 전체가 보이게. 정류장에서 노선을 누른 경우는 지도 그대로
        if (first && d.fitOnOpen) {
            located(d).map { s -> LatLng(s.lat!!, s.lng!!) }
                .takeIf { it.size >= 2 }?.let { canvas?.fitTo(it) }
        }
        if (d.stopTimes.value.isEmpty()) viewModelScope.launch { loadRouteStopTimes(d) }
        // 도로 선형은 처음 한 번만 (노선별 캐시). 받는 동안에도 버스는 직선 구간으로 움직인다
        if (d.shape.value == null) {
            viewModelScope.launch {
                val pts = d.stops.value.valueOrNull()?.mapNotNull { s -> s.lat?.let { la -> s.lng?.let { LatLng(la, it) } } }.orEmpty()
                // 노선 선: 카카오 지도 도로를 따라 (카카오 대중교통 경로에서 이 노선 번호 구간).
                // 카카오가 못 찾은 구간은 지역 공식 모양(서울시·경기·전주시)으로, 그것도 없으면 OSRM.
                val saved = withContext(Dispatchers.IO) { com.eunho.tagohub.data.StaticCache.shape(d.routeId) }
                val shape = saved ?: run {
                    val official: List<LatLng>? = when {
                        com.eunho.tagohub.data.SeoulBus.isSeoul(d.routeId) ->
                            (Graph.seoulBus.path(d.routeId) as? TagoResult.Success)?.data?.takeIf { it.size >= 2 }
                        com.eunho.tagohub.data.Gyeonggi.isGyeonggi(d.routeId) ->
                            // 앱 안 노선 모양(경기 경로이력 2025-10). 지금 노선 정류장 90% 가 100m 안을 지나야 씀 (노선이 바뀌었으면 경기 API)
                            stopDb?.let { db -> withContext(Dispatchers.IO) { db.gyeonggiShape(d.routeId) } }
                                ?.takeIf { pts.size < 2 || com.eunho.tagohub.data.Shapes.fitsStops(it, pts) }
                                ?: (Graph.gyeonggi.line(d.routeId) as? TagoResult.Success)?.data?.takeIf { it.size >= 2 }
                        com.eunho.tagohub.data.Jeonju.isJeonju(d.routeId) ->
                            (Graph.jeonju.drawing(d.routeId) as? TagoResult.Success)?.data?.takeIf { it.size >= 2 }
                        else -> null
                    }
                    RoadShapeClient.shape(d.routeId, pts, d.routeNo, official)
                }
                if (saved == null && shape != null) withContext(Dispatchers.IO) { com.eunho.tagohub.data.StaticCache.putShape(d.routeId, shape) }
                if (shape != null && route.value === d) {
                    d.shape.value = shape
                    pushRoute()
                    pushBuses()
                }
            }
        }
        while (true) {
            tracked { refreshBusData(repo, d) }
            pushBuses()
            delay(10_000)
        }
    }

    /**
     * 화면 안에 역이 있는 서울 노선만 30초마다 조회.
     * 노선 집합이 그대로면 지도를 조금 움직여도 루프를 재시작하지 않는다.
     */
    private fun updateTrainLines() {
        val box = lastBox
        val idx = index
        val want: Set<String> = if (subwayLayer && trainsOn.value && seoul.hasKey && lastLevel >= 1 && box != null && idx != null) {
            val dLat = (box[2] - box[0]) * 0.1
            val dLng = (box[3] - box[1]) * 0.1
            idx.seoulLinesIn(box[0] - dLat, box[1] - dLng, box[2] + dLat, box[3] + dLng)
        } else emptySet()
        if (want == trainLines && (want.isEmpty() || trainJob?.isActive == true)) return
        trainLines = want
        trainJob?.cancel()
        trainsByLine.keys.retainAll(want)
        pushTrains()
        if (want.isEmpty()) return
        trainJob = viewModelScope.launch {
            while (true) {
                val results = coroutineScope {
                    want.mapNotNull { SeoulLines.byId[it] }.map { line -> async { line.id to tracked { seoul.positions(line) } } }.awaitAll()
                }
                var error: String? = null
                results.forEach { (id, r) ->
                    when (r) {
                        is TagoResult.Success -> { trainsByLine[id] = r.data }
                        is TagoResult.Error -> { error = r.message }
                    }
                }
                if (error != null && error != lastTrainError) banner.value = error
                lastTrainError = error
                pushTrains()
                delay(30_000)
            }
        }
    }

    // ───────────────────────── 지도에 그리기 ─────────────────────────

    private fun pushStations() {
        val c = canvas ?: return
        val idx = index
        if (!subwayLayer || idx == null) {
            c.setStations(emptyList())
            return
        }
        c.setStations(idx.groups.map { g ->
            StationPin(g.id, g.name, g.lat, g.lng, g.displayLines.map { subwayLineColor(it).toArgb() })
        })
    }

    /** 열차 경로를 그리기만 함 (지도 이동·축소 없음) */
    private fun pushTrainPath() {
        val c = canvas ?: return
        val v = trainView.value ?: return
        val idx = index ?: return
        c.setRoute(
            subwayLineColor(v.lineName).toArgb(),
            v.path.map { st -> RoutePin(idx.groupIdOf(st) ?: st.code, st.name.let(com.eunho.tagohub.data.StationName::norm), st.lat, st.lng, st.norm == v.focusNorm) },
            null,
        )
    }

    private fun pushSelectedStation() {
        canvas?.selectStation(station.value?.group?.id)
    }

    /**
     * 열차 위치 = 서울 API 의 역 + 상태(진입/도착/출발/전역출발) + 수신 시각.
     * 이전·다음 역은 행선지 방향으로 추정하고, 수신 후 지난 시간만큼 평균 속도로 다음 역 쪽으로 움직인다.
     */
    private fun pushTrains() {
        val idx = index
        val list = ArrayList<VehiclePin>()
        if (idx != null && trainsOn.value && subwayLayer) {
            val now = LocalDateTime.now()
            trainsByLine.values.flatten().forEach { t ->
                val cur = idx.locate(t.lineId, t.stationName) ?: return@forEach
                val dest = t.destName?.let { idx.locate(t.lineId, it) }
                val (prev, next) = idx.neighbors(t.lineId, cur, dest)
                val elapsed = t.received?.let { Duration.between(it, now).toMillis().coerceIn(0, 120_000) } ?: 0L
                // 같은 역의 상행·하행 열차가 겹치지 않게 방향별로 약 25m 비켜서
                val sign = if (t.upDown == 0) 1 else -1
                fun pt(s: MetroStation) = LatLng(s.lat + sign * 0.00022, s.lng - sign * 0.00028)

                var path = listOf(pt(cur))
                var start = 0.0
                var end = 0.0
                var duration = 0L
                fun move(from: MetroStation, to: MetroStation, startAt: Double, endAt: Double, already: Long) {
                    path = listOf(pt(from), pt(to))
                    val travel = (Shapes.length(path) / TRAIN_SPEED * 1000).toLong().coerceIn(40_000, 600_000)
                    start = (startAt + already.toDouble() / travel).coerceAtMost(endAt)
                    end = endAt
                    duration = ((end - start) * travel).toLong().coerceAtLeast(0)
                }
                when (t.status) {
                    0 -> if (prev != null) move(prev, cur, 0.75, 0.98, elapsed)   // 진입: 현재 역 바로 앞
                    2 -> if (next != null) move(cur, next, 0.0, HOLD, elapsed)    // 출발: 다음 역으로
                    3 -> if (prev != null) move(prev, cur, 0.0, HOLD, elapsed)    // 전역출발: 현재 역으로 오는 중
                    else -> Unit                                                  // 도착: 역에 정차
                }
                val line = SeoulLines.byId[t.lineId]?.apiName ?: cur.displayLine
                list += VehiclePin(
                    id = "t:${t.lineId}:${t.trainNo}",
                    path = path,
                    startFrac = start,
                    endFrac = end,
                    durationMs = duration,
                    color = subwayLineColor(line).toArgb(),
                    label = listOfNotNull(t.destName?.let { "${it}행" }, SeoulMappers.statusText(t.status)).joinToString(" "),
                    glyph = if (t.express) "급" else if (t.last) "막" else "",
                )
            }
        }
        trainCount.value = list.size
        canvas?.setTrains(list)
    }

    private fun pushStops() {
        val c = canvas ?: return
        if (!busLayer) {
            c.setStops(emptyList())
            return
        }
        val sel = selected.value?.stop
        val all = if (sel != null && sel.nodeId !in stops) stops.values + sel else stops.values
        c.setStops(all.mapNotNull { s ->
            val lat = s.lat ?: return@mapNotNull null
            val lng = s.lng ?: return@mapNotNull null
            StopPin(s.nodeId, s.name, lat, lng, s.nodeId == sel?.nodeId)
        })
    }

    // ───────────────────────── 노선 방향 (상·하행 한 방향씩) ─────────────────────────

    private fun located(d: BusDest.Route) = d.stops.value.valueOrNull().orEmpty().filter { it.lat != null && it.lng != null }

    /** 회차 정류장 순번 (패널 목록에 회차 표시). 나눌 수 없으면 null */
    fun routeTurnOrd(d: BusDest.Route): Int? {
        val l = located(d)
        return RouteDirections.turnIndex(l)?.let { l[it].ord }
    }

    /** 방향 버튼 이름 ("○○ 방면" 의 ○○ = 그 방향 마지막 정류장). 나눌 수 없는 노선은 null */
    fun routeDirLabels(d: BusDest.Route): List<String>? {
        val l = located(d)
        val t = RouteDirections.turnIndex(l) ?: return null
        return listOf(RouteDirections.label(l, t, 0).orEmpty(), RouteDirections.label(l, t, 1).orEmpty())
    }

    /** 패널 정류장 목록: 지금 방향만 (나눌 수 없으면 전체) */
    fun routeStopsInDir(d: BusDest.Route, list: List<RouteStop>): List<RouteStop> {
        val l = located(d)
        val t = RouteDirections.turnIndex(l) ?: return list
        val r = RouteDirections.range(l.size, t, d.dir.value)
        val ords = l[r.first].ord..l[r.last].ord
        return list.filter { it.ord in ords }
    }

    /** "○○ 방면" 버튼: 패널 목록이 그 방향으로, 지도에서는 그 방향 선이 진하게 */
    fun setRouteDir(d: BusDest.Route, dir: Int) {
        if (d.dir.value == dir) return
        d.dir.value = dir
        pushRoute()
    }

    /** 노선을 그리기만 함 (지도 이동·축소 없음). 상·하행은 나란히 띄워 그리도록 방향별 선으로 나눠 넘긴다 */
    private fun pushRoute() {
        val c = canvas ?: return
        val d = route.value
        if (d == null || d.stops.value.valueOrNull() == null) {
            c.setRoute(null, emptyList(), null)
            return
        }
        val all = located(d)
        val t = RouteDirections.turnIndex(all)
        val base = d.shape.value?.takeIf { it.size >= 2 } ?: all.map { LatLng(it.lat!!, it.lng!!) }
        val parts = if (t != null && base.size >= 2) {
            val idx = Shapes.stopIndices(base, all.map { LatLng(it.lat!!, it.lng!!) })
            val k = idx[t]
            if (k in 1 until base.lastIndex) listOf(base.subList(0, k + 1), base.subList(k, base.size)) else null
        } else null
        c.setRoute(
            busRouteColor(d.routeType).toArgb(),
            all.map { s -> RoutePin(s.nodeId, s.name, s.lat!!, s.lng!!, s.nodeId == d.focusNodeId) },
            d.shape.value,
            parts,
            // 패널에서 고른 방향은 진하게, 반대 방향은 흐리게
            if (parts != null) d.dir.value else null,
        )
    }

    /**
     * 지도의 버스 = GPS 를 주는 API 의 버스만 (제주 BIS, TAGO).
     * GPS 가 없는 API(전주시·경기 GBIS: 정류장 순번만)의 버스는 지도에 그리지 않는다 — 위치를 지어내지 않음.
     * 그런 버스는 노선 패널 정류장 목록에 "몇 번째 정류장"으로만 보인다.
     * GPS 버스는 노선 선 위로 붙인 위치를, 직전 위치 → 새 위치로 노선을 따라 이동 (그대로면 멈춤, 뒤로는 안 감).
     */
    private fun pushBuses() {
        val d = route.value
        if (d == null) {
            canvas?.setBuses(emptyList())
            return
        }
        val color = busRouteColor(d.routeType).toArgb()
        val shape = d.shape.value
        val now = System.currentTimeMillis()
        canvas?.setBuses(d.buses.value.mapIndexedNotNull { n, b ->
            val g = b.vehicleNo?.let { d.gps[it] }?.track ?: return@mapIndexedNotNull null // GPS 없음 → 지도에 안 그림
            val id = b.vehicleNo ?: "bus$n"
            val path = GpsTracker.path(g, d.geom?.line ?: shape)
            val dur = g.durMs.toDouble()
            val k = if (g.from == null) 1.0 else ((now - g.at).toDouble() / dur).coerceIn(0.0, 1.0)
            VehiclePin(id, path, k, 1.0, ((1 - k) * dur).toLong(), color, b.vehicleNo?.takeLast(4).orEmpty(), "")
        })
    }

    /** 현재 중심에서 먼 정류소부터 버려 마커 수를 제한 */
    private fun trimStops(lat: Double, lng: Double) {
        if (stops.size <= MAX_STOPS) return
        val keep = stops.values
            .sortedBy { s -> Geo.meters(lat, lng, s.lat ?: lat, s.lng ?: lng) }
            .take(MAX_STOPS)
        stops.clear()
        keep.forEach { stops[it.nodeId] = it }
    }

    private companion object {
        const val MAX_STOPS = 400
        /** 도시철도 평균 속도 (정차 포함) m/s ≈ 40km/h */
        const val TRAIN_SPEED = 11.0
        /** 다음 역 직전에서 멈춰 다음 갱신을 기다리는 지점 */
        const val HOLD = 0.92
    }
}
