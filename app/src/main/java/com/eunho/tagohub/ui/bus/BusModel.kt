package com.eunho.tagohub.ui.bus

import com.eunho.tagohub.core.Load
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.naturalOrder
import com.eunho.tagohub.core.toLoad
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.data.BusRoute
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.RouteStop
import com.eunho.tagohub.data.Graph
import com.eunho.tagohub.core.valueOrNull
import com.eunho.tagohub.data.RouteSpeed
import com.eunho.tagohub.data.TripPlanner
import com.eunho.tagohub.data.RouteGeom
import com.eunho.tagohub.data.StaleGuard
import com.eunho.tagohub.data.StopMerge
import com.eunho.tagohub.data.StaticCache
import com.eunho.tagohub.data.SeoulBus
import com.eunho.tagohub.data.GpsTracker
import com.eunho.tagohub.data.Gyeonggi
import com.eunho.tagohub.data.GyeonggiRouteBook
import com.eunho.tagohub.data.Jeju
import com.eunho.tagohub.data.Jeonju
import com.eunho.tagohub.data.JeonjuMappers
import com.eunho.tagohub.data.StopDb
import com.eunho.tagohub.data.TagoRepository
import com.eunho.tagohub.core.Row
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import java.time.LocalTime

/** 정류소에서 본 노선 한 줄: 경유 노선 + 그 노선의 도착 예정(최대 2대) */
data class StopRouteRow(
    val routeId: String,
    val routeNo: String,
    val routeType: String?,
    val startName: String?,
    val endName: String?,
    val arrivals: List<BusArrival>,
    /** 다른 시군 노선이면 그 시군 이름 (예: 김제시) */
    val region: String? = null,
    /** 노선을 열 때 쓸 도시코드 */
    val cityCode: String? = null,
    /** 도착 정보가 없고 지금이 첫차~막차 시간 밖이면 운행종료 */
    val ended: Boolean = false,
    /** 첫차·막차 (기점 출발 시각). 같은 번호의 변형 노선이 버스 위치를 공유할 때 지금 달리는 노선인지 가리는 데 씀 */
    val firstTime: String? = null,
    val lastTime: String? = null,
)

/**
 * 같은 번호의 변형 노선이 여러 줄로 나올 때, 실시간 도착이 있는 줄이 하나라도 있으면
 * 실시간이 없는 줄(도착 정보 없음·시간표/위치 추정뿐)은 숨겨 한 노선이 두 줄로 보이지 않게 한다.
 */
internal fun hideDuplicateNumbers(rows: List<StopRouteRow>): List<StopRouteRow> {
    val liveNumbers = rows.filter { r -> r.arrivals.any { !it.estimated } }.map { it.routeNo }.toSet()
    return rows.filter { r -> r.routeNo !in liveNumbers || r.arrivals.any { !it.estimated } }
}

/** 지금(분)이 첫차~막차 시간 밖인지. 막차가 자정을 넘기면(첫차보다 이르면) 다음 날로 본다 */
internal fun outsideService(first: String?, last: String?, nowMin: Int): Boolean {
    fun m(t: String?) = t?.let { Regex("(\\d{1,2}):(\\d{2})").find(it) }?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }
    val f = m(first) ?: return false
    var l = m(last) ?: return false
    var n = nowMin
    if (l < f) { l += 1440; if (n < f) n += 1440 }
    return n < f - 30 || n > l + 90 // 첫차 30분 전부터, 막차 출발 뒤 90분까지는 운행 중으로 봄
}

/** 화면 스택. 각 목적지가 자기 상태를 들고 있어 뒤로 가도 다시 그릴 수 있다. */
sealed class BusDest {
    /** preset: 지도에서 이미 합쳐진 같은 자리의 다른 시군 정류장 */
    class Stop(val stop: BusStop, private val preset: List<BusStop>? = null) : BusDest() {
        /** 같은 자리의 다른 시군 정류장 (패널 머리에 번호를 함께 표시) */
        val others = MutableStateFlow(preset.orEmpty())
        /** 다음 정류장 이름 = "○○ 방면" */
        val direction = MutableStateFlow<String?>(null)
        val rows = MutableStateFlow<Load<List<StopRouteRow>>>(Load.Loading)
        /** 도착정보를 마지막으로 받은 시각 (매초 카운트다운 기준) */
        val updated = MutableStateFlow<LocalTime?>(null)

        internal var lastArrivals: List<BusArrival>? = null

        /** 도착정보에 없는 노선을 버스 위치로 어림한 결과 (노선 → 시각, 결과) */
        internal val estimated = HashMap<String, Pair<Long, BusArrival?>>()

        /** 같은 자리의 다른 시군 정류장과 그 도착정보·경유노선 */
        internal var companions: List<BusStop>? = preset
        internal val compArrivals = HashMap<String, List<BusArrival>>()
        internal val compRoutes = HashMap<String, List<BusRoute>>()
        /** 정류장마다 실제로 조회할 대상 (TSB 정류장은 버스가 서는 시군마다 하나씩) */
        internal var entries: List<BusStop>? = null
        /** 경유노선은 바뀌지 않으니 한 번만 받는다 (새로고침이 빨라짐) */
        internal var lastRoutes: List<BusRoute>? = null
        internal var emptyStreak = 0

        /** 서버가 마지막으로 준 도착 목록 (새로고침해도 안 비움: 직전과 같은 정보인지 알아보는 데 씀) */
        internal var lastServer: List<BusArrival>? = null

        /**
         * 도착정보 위에 띄울 안내: 새로고침이 실패해 이전 정보를 보여주는 중이거나, 서버가 준 정보가 직전과 똑같을 때.
         * (정상이면 null) — "초만 움직이고 위치가 안 바뀐다" 같은 경우에 왜 그런지 화면에서 알 수 있게
         */
        val note = MutableStateFlow<String?>(null)

        /** 새로고침을 누르면 처음 열 때와 같게 다시 받도록 실시간 정보 기억을 비움 (경유노선은 안 바뀌어서 그대로) */
        fun resetLive() {
            lastArrivals = null
            emptyStreak = 0
            compArrivals.clear()
            entries = null
            companions = preset
        }
    }

    class Route(
        val cityCode: String,
        val routeId: String,
        val routeNo: String,
        val routeType: String?,
        val focusNodeId: String?,
        /** 노선 검색에서 열었으면 노선 전체가 보이게 맞춤. 정류장에서 열었으면 지도 그대로 */
        val fitOnOpen: Boolean = false,
    ) : BusDest() {
        val info = MutableStateFlow<Load<BusRoute?>>(Load.Loading)
        val stops = MutableStateFlow<Load<List<RouteStop>>>(Load.Loading)
        val buses = MutableStateFlow<List<BusLocation>>(emptyList())
        val updated = MutableStateFlow<LocalTime?>(null)

        /** 번호로 찾은 TAGO 노선 ID (세종·원주·포항처럼 앱 안 노선에 ID 가 없을 때) */
        internal var tagoId: String? = null

        /** 패널에서 보고 있는 방향 (0 = 기점→회차점, 1 = 회차점→끝). 지도에서는 이 방향 선이 진하게 */
        val dir = MutableStateFlow(0)
        /** 처음 열 때 방향을 정했는지 (정류장에서 열었으면 그 정류장 방향) */
        internal var dirChosen = false

        /** 도로 선형 (OSRM). 없으면 정류소 직선 */
        val shape = MutableStateFlow<List<LatLng>?>(null)


        /**
         * 차량별 실시간 GPS 이동: 직전 위치(from) → 새 위치(to), 새 위치를 받은 시각(at).
         * 위치가 8m 안에서 그대로면 갱신하지 않음 → 지도에서도 그 자리에 멈춰 있음 (신호·정체·정차)
         */
        val gps = HashMap<String, GpsTracker.State>()

        /** 노선 모양·정류장으로 GPS 진행 거리 계산 (모양이 늦게 오면 다시 만듦) */
        internal var geom: RouteGeom? = null



        /** 노선 정류장 순번 → (첫차, 막차) 예상 도착 시각(분). 전주 노선만 */
        val stopTimes = MutableStateFlow<Map<Int, Pair<Int, Int>>>(emptyMap())

        /** 차량별로 정류장 순번이 줄어든(옛 데이터) 응답이 연속으로 온 횟수 */
        internal val backOrd = HashMap<String, Int>()

        /** 응답에서 빠진 횟수. 2번까지는 직전 위치를 유지해 깜빡임 방지 */
        internal val misses = HashMap<String, Int>()
    }
}

/**
 * 정류소 도착 안내.
 * - 경유노선은 처음 한 번만 받는다.
 * - 도착정보 요청이 실패하면 직전 정보를 그대로 둔다 (카운트다운 계속).
 * - 빈 응답이 한 번 오면 무시하고, 두 번 연속일 때만 "도착 정보 없음"으로 바꾼다
 *   (TAGO 가 가끔 빈 응답을 줘서 새로고침마다 떴다 안 떴다 하던 문제).
 */
/**
 * 정류장 도착정보: 지역별 자체 API (전주시·제주 BIS·경기 GBIS), 그 외 지역만 TAGO.
 * 자체 API 가 있는 지역은 TAGO 로 대체하지 않는다 (정보가 섞이지 않게).
 */
internal /**
 * 버스 도착정보: TAGO 버스도착정보(정류소별 도착예정정보) 하나로 — 제주·경기·전주 포함.
 * 서울은 TAGO 에 없어서(도시코드 목록에 서울 없음) 서울시 API (정류장 번호로).
 */
suspend fun arrivalsFor(repo: TagoRepository, s: BusStop): TagoResult<List<BusArrival>> = when {
    SeoulBus.isSeoul(s.nodeId) -> s.nodeNo?.let { Graph.seoulBus.arrivals(it) } ?: TagoResult.Success(emptyList())
    else -> kotlinx.coroutines.coroutineScope {
        // TAGO(시간·남은 정류장·저상) + 지역 API(방면·현재 정류장)를 동시에 받아 합침
        val tago = async { repo.stopArrivals(tagoCity(repo, s), s.nodeId) }
        val local = async {
            runCatching {
                val regional = com.eunho.tagohub.data.RegionalBus.of(s)
                when {
                    // 부산·울산·광주·여수 (울산은 TAGO ID 가 경기와 같은 GGB 접두사라 경기보다 먼저 확인)
                    regional != null -> Graph.regionalBus.arrivals(regional)
                    Gyeonggi.isGyeonggi(s.nodeId) -> Graph.gyeonggi.arrivals(s.nodeId)
                    Jeju.isJeju(s.nodeId) -> Graph.jeju.arrivals(s.nodeId)
                    Jeonju.isJeonju(s.nodeId) -> Graph.jeonju.arrivals(s.nodeId)
                    else -> null
                }
            }.getOrNull()
        }
        com.eunho.tagohub.data.ArrivalMerge.merge(tago.await(), local.await())
    }
}

/**
 * TAGO 요청용 도시코드. 교통안전공단 정류장 중 별도 코드(TS…)로 넣은 것(전주시 안 등)은
 * TAGO 주변 정류장에서 같은 정류장을 찾아 실제 도시코드를 쓰고, 없으면 이름으로 맞춘 대응표.
 */
private val cityResolved = java.util.concurrent.ConcurrentHashMap<String, String>()

internal suspend fun tagoCity(repo: TagoRepository, s: BusStop): String {
    if (!s.cityCode.startsWith("TS")) return s.cityCode
    cityResolved[s.nodeId]?.let { return it }
    // 앱 안 데이터만: 이름으로 맞춘 대응표 → 없으면 가장 가까운 TSB 시군
    val db = StopDb.peek()
    val lat = s.lat
    val lng = s.lng
    val byName = db?.let { withContext(Dispatchers.IO) { it.tagoCity(s.cityCode) } }
    val nearest = if (byName == null && db != null && lat != null && lng != null) {
        withContext(Dispatchers.IO) { db.nearbyTsbCities(lat, lng, 1) }.firstOrNull()
    } else null
    val code: String = byName ?: nearest ?: s.cityCode
    cityResolved[s.nodeId] = code
    return code
}

/** 정류장 경유노선: 지역별 자체 API, 그 외 지역만 TAGO */
internal suspend fun stopRoutesFor(repo: TagoRepository, s: BusStop): TagoResult<List<BusRoute>> = when {
    Jeju.isJeju(s.nodeId) -> cachedRoutes(s.nodeId) { Graph.jeju.stopRoutes(s.nodeId) }
    Gyeonggi.isGyeonggi(s.nodeId) -> gyeonggiStopRoutes(s)
    Jeonju.isJeonju(s.nodeId) -> cachedRoutes(s.nodeId) { Graph.jeonju.stopRoutes(s.nodeId) }
    SeoulBus.isSeoul(s.nodeId) -> TagoResult.Success( // 서울: 앱 안 노선 데이터 (서울 노선 파일)
        StopDb.peek()?.let { db -> withContext(Dispatchers.IO) { db.stopRoutes(s.nodeId) } }.orEmpty())
    else -> (StopDb.peek()?.let { db -> withContext(Dispatchers.IO) { db.stopRoutes(s.nodeId) } }?.takeIf { it.isNotEmpty() }
        ?.let { TagoResult.Success(it) }) // 서울·인천: 앱 안 노선 데이터 (서버 호출 없음)
        ?: cachedRoutes(s.nodeId + "_" + tagoCity(repo, s)) { repo.stopRoutes(tagoCity(repo, s), s.nodeId) }.let { r ->
        // 교통안전공단 노선(TSB…)은 기점·종점 이름을 교통안전공단 데이터로
        val db = StopDb.peek()
        if (r is TagoResult.Success && db != null) {
            TagoResult.Success(withContext(Dispatchers.IO) {
                r.data.map { route -> db.routeById(route.routeId)?.let { k -> route.copy(startName = k.startName ?: route.startName, endName = k.endName ?: route.endName) } ?: route }
            })
        } else r
    }
}

// ───────── 휴대폰 저장 (잘 안 바뀌는 정보는 한 번 받으면 7일 동안 서버에 다시 묻지 않음) ─────────

internal suspend fun cachedRoutes(key: String, fetch: suspend () -> TagoResult<List<BusRoute>>): TagoResult<List<BusRoute>> {
    withContext(Dispatchers.IO) { StaticCache.routes(key) }?.let { return TagoResult.Success(it) }
    val r = fetch()
    if (r is TagoResult.Success && r.data.isNotEmpty()) withContext(Dispatchers.IO) { StaticCache.putRoutes(key, r.data) }
    return r
}

internal suspend fun cachedStops(routeId: String, fetch: suspend () -> List<RouteStop>?): List<RouteStop>? {
    withContext(Dispatchers.IO) { StaticCache.routeStops(routeId) }?.let { return it }
    val r = fetch()
    if (!r.isNullOrEmpty()) withContext(Dispatchers.IO) { StaticCache.putRouteStops(routeId, r) }
    return r
}

internal suspend fun cachedInfo(routeId: String, fetch: suspend () -> BusRoute?): BusRoute? {
    withContext(Dispatchers.IO) { StaticCache.route(routeId) }?.let { return it }
    val r = fetch()
    if (r != null) withContext(Dispatchers.IO) { StaticCache.putRoute(routeId, r) }
    return r
}

/** 노선 정보(첫차·막차·배차간격): 지역별 자체 API, 그 외 TAGO. 교통안전공단 노선은 시간 정보가 TAGO 에만 있어 TAGO */
internal suspend fun routeInfoFor(repo: TagoRepository, routeId: String, cityCode: String): BusRoute? = cachedInfo(routeId) { routeInfoFetch(repo, routeId, cityCode) }

private suspend fun routeInfoFetch(repo: TagoRepository, routeId: String, cityCode: String): BusRoute? = when {
    Jeju.isJeju(routeId) -> Graph.jeju.routeInfo(routeId)
    Gyeonggi.isGyeonggi(routeId) -> (Graph.gyeonggi.routeInfo(routeId) as? TagoResult.Success)?.data
    Jeonju.isJeonju(routeId) -> (Graph.jeonju.routeInfo(routeId) as? TagoResult.Success)?.data
    SeoulBus.isSeoul(routeId) -> (Graph.seoulBus.routeInfo(routeId) as? TagoResult.Success)?.data
    else -> (repo.routeInfo(cityCode, routeId) as? TagoResult.Success)?.data
}

/** 노선 정류장 목록 (좌표 포함): 지역별 자체 API, 그 외 TAGO */
internal suspend fun routeStopsFor(repo: TagoRepository, routeId: String, cityCode: String): List<RouteStop> =
    StopDb.peek()?.let { db -> withContext(Dispatchers.IO) { db.routeStops(routeId) } }?.takeIf { it.isNotEmpty() }
        ?: cachedStops(routeId) { routeStopsFetch(repo, routeId, cityCode) }.orEmpty()

private suspend fun routeStopsFetch(repo: TagoRepository, routeId: String, cityCode: String): List<RouteStop>? = when {
    Jeju.isJeju(routeId) -> (Graph.jeju.routeStops(routeId) as? TagoResult.Success)?.data
    Gyeonggi.isGyeonggi(routeId) -> (Graph.gyeonggi.routeStops(routeId) as? TagoResult.Success)?.data
    Jeonju.isJeonju(routeId) -> (Graph.jeonju.routeStopRows(routeId) as? TagoResult.Success)?.data?.let { rows ->
        val coords = jeonjuCoords(rows)
        JeonjuMappers.routeStops(rows) { coords[it] }
    }
    SeoulBus.isSeoul(routeId) -> StopDb.peek()?.let { db -> withContext(Dispatchers.IO) { db.routeStops(routeId) } }
    else -> (repo.routeStops(cityCode, routeId) as? TagoResult.Success)?.data
}

/**
 * 경기 정류장의 노선 목록: 앱 안 노선 정류장(경기 노선경유정류소) + 경기 노선 번호부 → 서버 호출 없음.
 * 번호부에 없는 노선(폐지)은 뺀다. 둘 중 하나라도 없으면 경기 API (한 번 받으면 저장).
 */
private suspend fun gyeonggiStopRoutes(s: BusStop): TagoResult<List<BusRoute>> {
    val ids = StopDb.peek()?.let { db -> withContext(Dispatchers.IO) { db.routeIdsAtStop(s.nodeId, Gyeonggi.PREFIX) } }.orEmpty()
    if (ids.isNotEmpty()) {
        GyeonggiRouteBook.get(Graph.gyeonggi)?.let { book ->
            val list = ids.mapNotNull { book[it] }
            if (list.isNotEmpty()) return TagoResult.Success(list)
        }
    }
    return cachedRoutes(s.nodeId) { Graph.gyeonggi.stopRoutes(s.nodeId) }
}

/** 목록에 붙일 지역 이름: "전라북도 전주시" → "전주" */
private fun regionLabel(db: StopDb?, cityCode: String): String? =
    db?.shortCityName(cityCode)?.let(::shortRegion)

/** "전주시완산구" → "전주", "김제시" → "김제", "임실군" → "임실", "중구" → "중구" */
internal fun shortRegion(n: String): String {
    // "서울특별시"가 "서울특별"로 잘리지 않게: 특별시·광역시·특별자치시·특별자치도는 앞 이름만
    Regex("^(.{2,}?)(특별자치시|특별자치도|특별시|광역시)").find(n)?.let { return it.groupValues[1] }
    val head = Regex("^(.{2,}?[시군])").find(n)?.groupValues?.get(1) ?: n
    return if (head.length > 2 && (head.endsWith("시") || head.endsWith("군"))) head.dropLast(1) else head
}

/**
 * 같은 자리의 다른 시군 정류장: 전국 정류장 파일 + TAGO 주변 정류장(파일에 없는 정류장까지).
 * 예: 전주 기린대로 병무청(30703) ↔ 전북 통합 정류장(3050070) 은 파일에 없어서 주변 정류장으로 찾는다.
 * 제주·경기처럼 섬이거나 자체 API 지역 안쪽은 파일로 충분해서 TAGO 를 부르지 않는다.
 */
private suspend fun findCompanions(repo: TagoRepository, s: BusStop, db: StopDb?): List<BusStop> {
    // 앱 안 정류장 데이터만 (교통안전공단 파일로 전주시 안 정류장(예: 3050070)까지 들어 있어 서버 검색이 필요 없음)
    val fromFile = if (db != null) withContext(Dispatchers.IO) { db.sameSpot(s) } else emptyList()
    return fromFile.groupBy { if (it.cityCode.startsWith("TS")) it.nodeId else it.cityCode }.map { (_, l) -> l.first() }
}

private fun isApiRegion(s: BusStop) = Jeju.isJeju(s.nodeId) || Gyeonggi.isGyeonggi(s.nodeId) || Jeonju.isJeonju(s.nodeId) || SeoulBus.isSeoul(s.nodeId)

/** 한 정류장(같은 ID)을 시군별로 조회할 때 구분하는 키 */
private fun entryKey(s: BusStop) = s.nodeId + "|" + s.cityCode

/**
 * TSB(교통안전공단) 정류장은 노선을 운영하는 시군의 도시코드로 조회해야 그 시군 버스가 나온다.
 * 한 정류장에 여러 시군 버스가 서면(예: 기린대로 병무청에 임실 농어촌버스 752·203) 시군마다 따로 조회해야 해서
 * 후보 도시코드(TAGO 주변 정류장의 코드, 이름 대응표, 근처 TSB 시군)를 경유노선으로 확인하고, 노선이 나오는 시군을 모두 쓴다.
 */
private suspend fun tsbTargets(repo: TagoRepository, s: BusStop, db: StopDb?): List<BusStop> {
    val cands = LinkedHashSet<String>()
    if (!s.cityCode.startsWith("TS")) cands += s.cityCode
    val lat = s.lat
    val lng = s.lng
    if (db != null) {
        if (s.cityCode.startsWith("TS")) withContext(Dispatchers.IO) { db.tagoCity(s.cityCode) }?.let { cands += it }
        if (lat != null && lng != null) cands += withContext(Dispatchers.IO) { db.nearbyTsbCities(lat, lng, 7) }
    }
    val probed = coroutineScope {
        cands.take(8).map { code ->
            async { code to ((cachedRoutes("${s.nodeId}_$code") { repo.stopRoutes(code, s.nodeId) } as? TagoResult.Success)?.data.orEmpty().isNotEmpty()) }
        }.awaitAll()
    }
    val ok = probed.filter { it.second }.map { it.first }
    return (ok.ifEmpty { listOfNotNull(cands.firstOrNull()) }).map { s.copy(cityCode = it) }.ifEmpty { listOf(s) }
}

internal suspend fun loadStopBoard(repo: TagoRepository, d: BusDest.Stop) {
    // 처음 열 때: 노선 목록(대부분 앱 안 데이터)을 먼저 보여주고, 실시간 도착은 받아지는 대로 채움 (전에는 다 받을 때까지 빈 화면 2초)
    if (d.rows.value is Load.Loading && d.lastRoutes == null) {
        (stopRoutesFor(repo, d.stop) as? TagoResult.Success)?.data?.takeIf { it.isNotEmpty() }?.let { quick ->
            d.lastRoutes = quick
            if (d.rows.value is Load.Loading) d.rows.value = Load.Ok(mergeStopRows(emptyList(), quick).map { it.copy(cityCode = d.stop.cityCode) })
        }
    }
    val (arr, routes) = coroutineScope {
        val a = async { arrivalsFor(repo, d.stop) }
        val r = if (d.lastRoutes == null) async { stopRoutesFor(repo, d.stop) } else null
        a.await() to r?.await()
    }
    (routes as? TagoResult.Success)?.let { d.lastRoutes = it.data }

    when (arr) {
        is TagoResult.Success -> {
            // 서버가 직전과 똑같은 정보를 주면(버스 위치는 30~60초마다 바뀜) 그렇다고 알림
            val unchanged = d.lastServer != null && arr.data == d.lastServer
            d.lastServer = arr.data
            d.note.value = if (unchanged && arr.data.isNotEmpty()) "정보가 직전과 같아요 · 버스 위치는 30~60초마다 바뀌어요" else null
            if (arr.data.isNotEmpty() || d.emptyStreak >= 1 || d.lastArrivals.isNullOrEmpty()) {
                // 새로고침해도 남은 시간이 거꾸로 늘지 않게 (화면에서 이미 줄어든 값을 유지)
                val elapsed = d.updated.value?.let { java.time.Duration.between(it, LocalTime.now()).seconds.toInt().coerceIn(0, 600) } ?: 0
                d.lastArrivals = com.eunho.tagohub.data.ArrivalSmoothing.bus(d.lastArrivals, elapsed, dropPassedBySeoulPosition(d, arr.data))
                // 서버 정보가 그대로면 "받은 시각"을 그대로 둠: 새로고침할 때마다 시각을 지금으로 바꾸면 남은 초가 도로 늘어나 보임
                if (!unchanged || d.updated.value == null) d.updated.value = LocalTime.now()
            }
            d.emptyStreak = if (arr.data.isEmpty()) d.emptyStreak + 1 else 0
        }
        is TagoResult.Error -> {
            if (d.lastArrivals == null && d.lastRoutes == null) {
                d.rows.value = Load.Fail(arr.message)
                return
            }
            // 실패했는데 이전 정보를 계속 보여주면 초만 줄어드는 것처럼 보이므로 이유를 알림
            d.note.value = "새로고침에 실패해서 이전 정보를 보여주고 있어요 (${arr.message.take(60)})"
        }
    }
    // 같은 자리의 다른 시군 정류장 (번호를 함께 보여주고, 그 정류장에 서는 버스도 같은 목록에)
    val db = StopDb.peek()
    val comps = d.companions ?: findCompanions(repo, d.stop, db).also { d.companions = it }
    if (d.others.value != comps) d.others.value = comps
    // 조회 대상: 자체 API 지역 정류장은 그대로, TSB 정류장은 버스가 서는 시군마다 하나씩
    val entries = d.entries ?: coroutineScope {
        // 누른 정류장이 TSB 면, 그 정류장에 서는 다른 시군 버스도 (첫 시군은 위에서 이미 조회)
        val mainExtra = async {
            if (!isApiRegion(d.stop) && d.stop.nodeId.startsWith("TSB")) {
                val own = tagoCity(repo, d.stop)
                tsbTargets(repo, d.stop, db).filter { it.cityCode != own }
            } else emptyList()
        }
        val fromComps = comps.map { c -> async { if (!isApiRegion(c) && c.nodeId.startsWith("TSB")) tsbTargets(repo, c, db) else listOf(c) } }
        mainExtra.await() + fromComps.awaitAll().flatten()
    }.also { d.entries = it }
    if (entries.isNotEmpty()) {
        coroutineScope {
            entries.map { c ->
                async {
                    val a = arrivalsFor(repo, c)
                    val r = if (d.compRoutes[entryKey(c)] == null) stopRoutesFor(repo, c) else null
                    Triple(c, a, r)
                }
            }.awaitAll()
        }.forEach { (c, a, r) ->
            (r as? TagoResult.Success)?.let { d.compRoutes[entryKey(c)] = it.data }
            (a as? TagoResult.Success)?.let { d.compArrivals[entryKey(c)] = it.data } // 실패하면 직전 정보 유지
        }
    }

    val main = fillFromPositions(
        repo, d.stop,
        mergeStopRows(d.lastArrivals.orEmpty(), d.lastRoutes.orEmpty())
            .map { it.copy(cityCode = d.stop.cityCode, region = regionLabel(db, d.stop.cityCode)) },
        d.estimated,
    )
    val mainNos = main.map { it.routeNo }.toSet()
    val others = entries.flatMap { c ->
        mergeStopRows(d.compArrivals[entryKey(c)].orEmpty(), d.compRoutes[entryKey(c)].orEmpty())
            .map { it.copy(region = regionLabel(db, c.cityCode), cityCode = c.cityCode) }
    }.filter { it.routeNo !in mainNos } // 이미 이 정류장 정보에 있는 노선은 두 번 안 보이게
        .distinctBy { it.routeId + "|" + it.cityCode }
    d.rows.value = Load.Ok((main + others).sortedWith(stopRowOrder))
}

/**
 * 정류장 방면 = 이 정류장을 지나는 노선들에서 바로 다음 정류장 이름 (가장 많이 나온 것).
 * 카카오맵의 "삼천흥건2차아파트 방면" 과 같은 방식.
 */
internal suspend fun loadDirection(repo: TagoRepository, d: BusDest.Stop) {
    if (d.direction.value != null) return
    val routes = d.lastRoutes.orEmpty().take(4)
    if (routes.isEmpty()) return
    val names = coroutineScope {
        routes.map { r -> async { nextStopName(repo, d.stop, r) } }.awaitAll()
    }.filterNotNull()
    d.direction.value = names.groupingBy { it }.eachCount().maxByOrNull { it.value }?.key
}

private suspend fun nextStopName(repo: TagoRepository, stop: BusStop, r: BusRoute): String? {
    val list: List<RouteStop> = when {
        Jeju.isJeju(r.routeId) -> (Graph.jeju.routeStops(r.routeId) as? TagoResult.Success)?.data
        Gyeonggi.isGyeonggi(r.routeId) -> (Graph.gyeonggi.routeStops(r.routeId) as? TagoResult.Success)?.data
        Jeonju.isJeonju(r.routeId) -> (Graph.jeonju.routeStopRows(r.routeId) as? TagoResult.Success)?.data
            ?.let { rows -> JeonjuMappers.routeStops(rows) { null } }
        else -> (repo.routeStops(stop.cityCode, r.routeId) as? TagoResult.Success)?.data
    } ?: return null
    val i = list.indexOfFirst { it.nodeId == stop.nodeId }
    return if (i >= 0) list.getOrNull(i + 1)?.name else null
}

/** 도착 예정이 있는 노선 먼저, 빨리 오는 순, 그다음 노선번호 순 */
internal val stopRowOrder: Comparator<StopRouteRow> = compareBy<StopRouteRow>(
    { it.arrivals.isEmpty() },
    { it.arrivals.firstOrNull()?.arrSeconds ?: Int.MAX_VALUE },
).thenBy(naturalOrder) { it.routeNo }

internal fun mergeStopRows(
    arrivals: List<BusArrival>,
    routes: List<BusRoute>,
    nowMin: Int = LocalTime.now().let { it.hour * 60 + it.minute },
): List<StopRouteRow> {
    val byRoute = arrivals.groupBy { it.routeId }
    val known = routes.map { r ->
        val a = byRoute[r.routeId].orEmpty().take(2)
        // 도착 정보에는 항상 지금 노선 번호가 와서 그걸 먼저 (앱 안 노선 자료가 예전 번호일 수 있음: 경기 707-1 → 1200)
        val live = a.firstOrNull()
        StopRouteRow(r.routeId, live?.routeNo?.takeIf { it.isNotBlank() } ?: r.routeNo, r.routeType ?: live?.routeType, r.startName, r.endName, a,
            ended = a.isEmpty() && outsideService(r.firstTime, r.lastTime, nowMin), firstTime = r.firstTime, lastTime = r.lastTime)
    }
    val ids = routes.map { it.routeId }.toSet()
    // 경유노선 목록에 없지만 도착정보에는 있는 노선도 놓치지 않게
    val extra = byRoute.filterKeys { it !in ids }.map { (id, list) ->
        StopRouteRow(id, list.first().routeNo, list.first().routeType, null, null, list.take(2))
    }
    return (known + extra).sortedWith(
        compareBy<StopRouteRow>(
            { it.arrivals.isEmpty() },
            { it.arrivals.firstOrNull()?.arrSeconds ?: Int.MAX_VALUE },
        ).thenBy(naturalOrder) { it.routeNo }
    )
}

/** 전주시 노선 위치 응답 → 정류장 좌표 붙이기 (전주시 API 는 좌표를 주지 않아 내장 정류장 DB 에서) */
private suspend fun jeonjuCoords(rows: List<Row>): Map<String, LatLng> {
    val ids = rows.mapNotNull { it.str("stopStandardid", "bnodeId")?.let(Jeonju::tago) }
    val db = StopDb.peek() ?: return emptyMap()
    return withContext(Dispatchers.IO) { db.coords(ids) }
}

internal suspend fun loadRouteData(repo: TagoRepository, d: BusDest.Route) {
    if (Jeju.isJeju(d.routeId)) {
        val st = cachedStops(d.routeId) { (Graph.jeju.routeStops(d.routeId) as? TagoResult.Success)?.data }
        d.stops.value = if (st != null) Load.Ok(st) else Load.Fail("제주 노선 정류장을 받지 못했어요.")
        d.info.value = Load.Ok(cachedInfo(d.routeId) { Graph.jeju.routeInfo(d.routeId) })
        return
    }
    // 자체 API 지역: 노선 정류장·정보 모두 그 지역 API (TAGO 로 대체하지 않음)
    if (Gyeonggi.isGyeonggi(d.routeId)) {
        val info = cachedInfo(d.routeId) { (Graph.gyeonggi.routeInfo(d.routeId) as? TagoResult.Success)?.data }
        // 앱 안 노선 정류장(경기 노선경유정류소, 2023) — 첫 정류장이 지금 노선 기점과 다르면 개편된 것으로 보고 경기 API
        val local = StopDb.peek()?.let { db -> withContext(Dispatchers.IO) { db.routeStops(d.routeId) } }.orEmpty()
        val st = if (local.isNotEmpty() && GyeonggiRouteBook.sameStop(local.first().name, info?.startName)) local
            else cachedStops(d.routeId) { (Graph.gyeonggi.routeStops(d.routeId) as? TagoResult.Success)?.data }
        d.stops.value = if (st != null) Load.Ok(st) else Load.Fail("경기 노선 정류장을 받지 못했어요.")
        d.info.value = Load.Ok(info)
        return
    }
    if (Jeonju.isJeonju(d.routeId)) {
        val st = cachedStops(d.routeId) {
            (Graph.jeonju.positions(d.routeId) as? TagoResult.Success)?.data?.let { rows ->
                val coords = jeonjuCoords(rows)
                JeonjuMappers.routeStops(rows) { coords[it] }
            }
        }
        d.stops.value = if (st != null) Load.Ok(st) else Load.Fail("전주 노선 정류장을 받지 못했어요.")
        d.info.value = Load.Ok(cachedInfo(d.routeId) { (Graph.jeonju.routeInfo(d.routeId) as? TagoResult.Success)?.data })
        return
    }
    // 앱에 넣어둔 노선(서울·인천·세종·원주·포항): 정류장 순서·노선 정보 모두 앱 안 데이터 (서버 호출 없음)
    StopDb.peek()?.let { db ->
        var local = withContext(Dispatchers.IO) { db.routeStops(d.routeId) }
        if (local.isNotEmpty()) {
            // 서울 노선은 정류장 방향 코드가 없어서 회차 정류장(서울시 API transYn, 처음 한 번만)으로 상·하행 나눔
            if (SeoulBus.isSeoul(d.routeId) && local.all { it.upDown.isNullOrBlank() }) {
                local = com.eunho.tagohub.data.RouteDirections.markTurn(local, Graph.seoulBus.turnStation(d.routeId))
            }
            d.stops.value = Load.Ok(local)
            val seoulInfo = if (SeoulBus.isSeoul(d.routeId)) cachedInfo(d.routeId) { (Graph.seoulBus.routeInfo(d.routeId) as? TagoResult.Success)?.data } else null
            d.info.value = Load.Ok(seoulInfo ?: withContext(Dispatchers.IO) { db.routeById(d.routeId) })
            return
        }
    }
    // 서울 정류장에서 연 노선인데 앱 안 서울 노선 표에 없는 노선(서울을 지나는 경기·인천 노선 등):
    // 서울시 API 가 정류장·노선 정보를 다 줌 (TAGO 는 서울 노선 ID 를 몰라서 "정류장 정보가 없어요"가 됐음)
    if (SeoulBus.isSeoul(d.routeId)) {
        coroutineScope {
            launch { d.info.value = Load.Ok(cachedInfo(d.routeId) { (Graph.seoulBus.routeInfo(d.routeId) as? TagoResult.Success)?.data }) }
            launch {
                if (d.stops.value !is Load.Ok) d.stops.value = Load.Loading
                val st = cachedStops(d.routeId) { (Graph.seoulBus.routeStops(d.routeId) as? TagoResult.Success)?.data }
                d.stops.value = if (!st.isNullOrEmpty()) Load.Ok(st) else Load.Fail("노선 정류장을 받지 못했어요.")
            }
        }
        return
    }
    coroutineScope {
        launch {
            val k = StopDb.peek()?.let { db -> withContext(Dispatchers.IO) { db.routeById(d.routeId) } }
            d.info.value = Load.Ok(k ?: cachedInfo(d.routeId) { (repo.routeInfo(d.cityCode, d.routeId) as? TagoResult.Success)?.data })
        }
        launch {
            if (d.stops.value !is Load.Ok) d.stops.value = Load.Loading
            val st = cachedStops(d.routeId) { (repo.routeStops(d.cityCode, d.routeId) as? TagoResult.Success)?.data }
            d.stops.value = if (st != null) Load.Ok(st) else Load.Fail("노선 정류장을 받지 못했어요.")
        }
    }
}

/**
 * 버스 위치.
 * 1) TAGO 버스위치 = 실시간 GPS (전주 포함). 정류장 순번도 전주시 API 와 같다.
 * 2) TAGO 가 안 되면 전주시 API (정류장 단위, GPS 없음)
 */
internal suspend fun refreshBusData(repo: TagoRepository, d: BusDest.Route) {
    fetchBusData(repo, d)
    // 같은 번호 노선(기점·종점이 다른 변형)이 버스 위치를 공유하는 지역이 있어서(전주: 변형마다 같은 버스가 옴, 번호로 TAGO 노선을 고르는 세종·원주·포항),
    // 운행 시각이 지난 변형 노선에는 다른 변형의 버스가 잡힘 → 그 노선의 운행 시각 밖이면 버스를 비움
    if (d.buses.value.isNotEmpty() && !variantRunning(d)) d.buses.value = emptyList()
    // 버스가 정류장을 옮겨 간 실제 시간으로 이 노선의 도로 상황(평소 대비 느림/빠름)을 갱신
    val buses = d.buses.value
    if (buses.isEmpty()) return
    val line by lazy { TripPlanner.Line(d.routeId, d.routeNo, d.stops.value.valueOrNull().orEmpty()) }
    RouteSpeed.observe(d.routeId, buses) { a, b -> TripPlanner.minutesBetween(line, a, b) }
}

private suspend fun fetchBusData(repo: TagoRepository, d: BusDest.Route) {
    if (Gyeonggi.isGyeonggi(d.routeId)) {
        val g = Graph.gyeonggi.buses(d.routeId)
        if (g is TagoResult.Success) {
            // 앱 안 노선 정류장은 순번 체계가 API 와 다를 수 있어서, 버스가 있는 정류장 ID 로 순번을 맞춤
            val stops = d.stops.value.valueOrNull().orEmpty()
            val ordOf = stops.groupBy { it.nodeId }.mapValues { e -> e.value.map { it.ord } }
            val buses = g.data.map { b ->
                val cands = b.nodeId?.let { ordOf[it] }
                if (cands.isNullOrEmpty()) b else b.copy(ord = cands.minByOrNull { kotlin.math.abs(it - (b.ord ?: it)) })
            }
            d.buses.value = mergeWithGrace(d, guard(d, buses))
            d.updated.value = LocalTime.now()
        }
        return
    }
    if (Jeju.isJeju(d.routeId)) {
        val stops = d.stops.value.valueOrNull().orEmpty()
        val j = Graph.jeju.buses(d.routeId, stops)
        if (j is TagoResult.Success) {
            d.buses.value = mergeWithGrace(d, guard(d, j.data))
            updateGps(d)
            d.updated.value = LocalTime.now()
        }
        return
    }
    if (Jeonju.isJeonju(d.routeId)) {
        // 버스 목록·정류장 순번·저상 여부: 전주시 API (GPS 없음 → 지도에는 안 그리고 패널 목록에만)
        val j = Graph.jeonju.positions(d.routeId)
        if (j is TagoResult.Success) {
            val buses = JeonjuMappers.buses(j.data) { null }
            d.buses.value = mergeWithGrace(d, guard(d, buses))
            updateGps(d)
            d.updated.value = LocalTime.now()
        }
        return
    }
    if (SeoulBus.isSeoul(d.routeId)) {
        val s = Graph.seoulBus.buses(d.routeId)
        if (s is TagoResult.Success) {
            d.buses.value = mergeWithGrace(d, guard(d, s.data))
            updateGps(d)
            d.updated.value = LocalTime.now()
        }
        return
    }
    // 앱에 넣어둔 노선: 실시간 연결 방식에 따라
    val db = StopDb.peek()
    when (db?.let { withContext(Dispatchers.IO) { it.routeRealtime(d.routeId) } }) {
        "none" -> return // 서울: 실시간 없음
        "resolve" -> {
            // 세종·원주·포항: 노선 ID 가 없어서 번호로 TAGO 노선을 한 번 찾아 연결. 정류장 순번 체계가 달라 순번은 쓰지 않음(정류장 ID 로 맞춤)
            val id = d.tagoId ?: resolveTagoRoute(repo, d, db)?.also { d.tagoId = it } ?: return
            val r = repo.busLocations(d.cityCode, id)
            if (r is TagoResult.Success) {
                d.buses.value = mergeWithGrace(d, r.data.map { it.copy(ord = null) })
                updateGps(d)
                d.updated.value = LocalTime.now()
            }
            return
        }
    }
    // 자체 API 가 없는 지역은 TAGO
    val tago = repo.busLocations(d.cityCode, d.routeId)
    if (tago is TagoResult.Success) {
        d.buses.value = mergeWithGrace(d, guard(d, tago.data))
        updateGps(d)
        d.updated.value = LocalTime.now()
    }
}

private fun updateGps(d: BusDest.Route) {
    val now = System.currentTimeMillis()
    val stops = d.stops.value.valueOrNull().orEmpty()
    val shape = d.shape.value
    val geom = d.geom?.takeIf { it.shape === shape } ?: RouteGeom(shape, stops).also { d.geom = it }
    val seen = HashSet<String>()
    d.buses.value.forEach { b ->
        val id = b.vehicleNo ?: return@forEach
        val lat = b.lat ?: return@forEach
        val lng = b.lng ?: return@forEach
        seen += id
        d.gps[id] = GpsTracker.next(d.gps[id], LatLng(lat, lng), b.ord, geom, now)
    }
    d.gps.keys.retainAll(seen)
}

/** 번호로 TAGO 노선 찾기: 같은 번호 중 기점·종점 이름이 맞는 것, 없으면 첫 번째 */
private suspend fun resolveTagoRoute(repo: TagoRepository, d: BusDest.Route, db: StopDb): String? {
    val local = withContext(Dispatchers.IO) { db.routeById(d.routeId) } ?: return null
    val cands = (repo.routesByNo(d.cityCode, local.routeNo) as? TagoResult.Success)?.data.orEmpty().filter { it.routeNo == local.routeNo }
    fun same(a: String?, b: String?) = !a.isNullOrBlank() && !b.isNullOrBlank() && (a.contains(b) || b.contains(a))
    return (cands.firstOrNull { same(it.startName, local.startName) && same(it.endName, local.endName) }
        ?: cands.firstOrNull { same(it.startName, local.startName) || same(it.endName, local.endName) }
        ?: cands.firstOrNull())?.routeId
}

/** 늦게 온 옛 데이터(정류장 순번이 줄어든 위치)는 직전 위치 유지 → 버스가 뒤로 가지 않음 */
private fun guard(d: BusDest.Route, fresh: List<BusLocation>): List<BusLocation> =
    StaleGuard.filter(d.buses.value, withIds(fresh), d.backOrd, d.stops.value.valueOrNull()?.size ?: 0)

/**
 * 차량 번호가 비어 있는 응답(TAGO 일부 도시)은 버스를 구분할 수 없어 한 대로 뭉치거나 지도에 안 그려졌음.
 * 정류장·순번으로 임시 번호("#…")를 붙여 여러 대가 따로 보이게 함. 차량 번호는 앞뒤 공백을 지움.
 */
internal fun withIds(list: List<BusLocation>): List<BusLocation> {
    val seen = HashMap<String, Int>()
    return list.map { b ->
        val v = b.vehicleNo?.trim()
        if (!v.isNullOrEmpty()) return@map if (v == b.vehicleNo) b else b.copy(vehicleNo = v)
        val base = "#${b.nodeId.orEmpty()}:${b.ord ?: ""}"
        val n = (seen[base] ?: 0) + 1
        seen[base] = n
        b.copy(vehicleNo = if (n > 1) "$base-$n" else base)
    }
}

/** 이번 응답에 없는 차량도 2회까지는 직전 위치로 유지 (TAGO 응답에서 가끔 빠짐) */
private fun mergeWithGrace(d: BusDest.Route, freshRaw: List<BusLocation>): List<BusLocation> {
    val fresh = withIds(freshRaw)
    fun key(b: BusLocation) = b.vehicleNo ?: "${b.nodeId}:${b.ord}"
    val now = fresh.associateBy(::key)
    now.keys.forEach { d.misses.remove(it) }
    val kept = d.buses.value.filter { old ->
        val k = key(old)
        if (k in now) return@filter false
        val n = (d.misses[k] ?: 0) + 1
        d.misses[k] = n
        n <= 2
    }
    d.misses.keys.retainAll(now.keys + kept.map(::key))
    return fresh + kept
}

private val busPosCache = java.util.concurrent.ConcurrentHashMap<String, Pair<Long, List<Int>>>()
private const val BUS_POS_TTL_MS = 15_000L
private const val BUS_POS_MAX_ROUTES = 5

/**
 * 서울: 곧 도착(1정류장 전 이내)으로 뜬 첫 버스가 실시간 위치상 이미 정류장을 지나갔으면 목록에서 뺀다.
 * 노선당 위치 조회가 한 번 늘어나므로, 해당하는 노선만 최대 [BUS_POS_MAX_ROUTES] 개, 15초 동안 결과를 재사용한다.
 * 위치를 못 받거나 정류장이 노선에 여러 번 나오면 판단하지 않고 그대로 둔다.
 */
private suspend fun dropPassedBySeoulPosition(d: BusDest.Stop, list: List<BusArrival>): List<BusArrival> {
    if (!SeoulBus.isSeoul(d.stop.nodeId)) return list
    val db = StopDb.peek() ?: return list
    val firsts = list.filter { (it.prevStops ?: Int.MAX_VALUE) <= 1 }
        .groupBy { it.routeId }.values.map { g -> g.minBy { it.arrSeconds ?: Int.MAX_VALUE } }.take(BUS_POS_MAX_ROUTES)
    if (firsts.isEmpty()) return list
    val gone = ArrayList<BusArrival>()
    for (a in firsts) {
        val stopOrds = withContext(Dispatchers.IO) { db.routeStops(a.routeId) }.filter { it.nodeId == d.stop.nodeId }.map { it.ord }
        if (stopOrds.size != 1) continue
        val now = System.currentTimeMillis()
        val cached = busPosCache[a.routeId]?.takeIf { now - it.first < BUS_POS_TTL_MS }?.second
        val busOrds = cached ?: (Graph.seoulBus.buses(a.routeId) as? TagoResult.Success)?.data?.mapNotNull { it.ord }
            ?.also { busPosCache[a.routeId] = now to it } ?: continue
        if (com.eunho.tagohub.data.PassedByPosition.passed(stopOrds, busOrds)) gone += a
    }
    return if (gone.isEmpty()) list else list.filterNot { x -> gone.any { it === x } }
}
