package com.eunho.tagohub.web

import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.core.TagoXml
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.TagoIds
import com.eunho.tagohub.data.WebData
import com.eunho.tagohub.data.getText
import io.ktor.client.HttpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 정류장 하나 (서버 자료 조각의 한 줄: [id, 이름, 위도, 경도, 번호, 시군, [노선...]]) */
data class WebStop(val id: String, val name: String, val lat: Double, val lng: Double, val no: String?, val city: String, val routes: List<String>)

/** 노선 요약 (서버 routesum 조각): 정류장을 누르자마자 실시간보다 먼저 보여 줌 */
data class RouteSum(
    val id: String, val no: String, val start: String?, val end: String?, val type: String?,
    val first: String?, val last: String?, val interval: Int?, val intervalSat: Int?, val intervalSun: Int?,
) {
    /** 오늘(요일 [isoDay]: 월 1 … 일 7) 배차 간격(분) */
    fun intervalOn(isoDay: Int): Int? = when (isoDay) { 6 -> intervalSat ?: interval; 7 -> intervalSun ?: intervalSat ?: interval; else -> interval }
}

/** 노선의 정류장 하나 (서버 routes 조각: [순번, 정류장 id, 이름, 위도, 경도, 상하행]) */
data class RouteStopW(val seq: Int, val id: String?, val name: String, val lat: Double?, val lng: Double?, val upDown: String?)

/** 노선 전체 (서버 routes 조각) */
data class RouteFull(
    val id: String, val no: String, val start: String?, val end: String?, val city: String, val region: String?, val type: String?,
    val sum: RouteSum?, val stops: List<RouteStopW>,
)

/** 버스 위치를 받은 결과 */
sealed interface Positions {
    data class Ok(val buses: List<com.eunho.tagohub.data.BusLocation>) : Positions
    data object Unsupported : Positions
    data class Failed(val why: String) : Positions
}

/** 실시간 도착을 받은 결과 */
sealed interface Arrivals {
    data class Ok(val list: List<BusArrival>, val atMs: Long) : Arrivals
    /** 이 지역은 웹 통로(TAGO)로 실시간을 못 받음 */
    data object Unsupported : Arrivals
    data class Failed(val why: String) : Arrivals
}

/**
 * 웹·PC 버전 자료: 정류장 찾기는 서버의 이름 조각(/data/names), 도착 정보는 웹 전용 통로(/w/dg/1613000, 토큰 없음).
 * [base] 는 서버 주소 ("https://…workers.dev/").
 */
class WebRepo(base: String, private val http: HttpClient = HttpClient()) {
    private val base = base.trimEnd('/') + "/"
    private val names = HashMap<String, List<WebStop>>()
    private val tiles = HashMap<String, List<WebStop>>()
    private val sums = HashMap<String, Map<String, RouteSum>>()
    private val routeFiles = HashMap<String, Map<String, RouteFull>>()
    private var cities: Cities? = null
    private val citiesLock = Mutex()

    /** cities.json: 시군 이름, TS… 시군 → TAGO 도시코드 */
    private class Cities(val names: Map<String, String>, val alias: Map<String, String>)

    /**
     * 이름에 [query] 가 든 정류장 (조각은 검색어 첫 글자로 나눠 둠, 폐지·미사용 정류장은 서버가 이미 뺌). 이름이 검색어로 시작하는 것 먼저.
     * 같은 이름이 같은 자리([SAME_SPOT_M] 안)에 여러 시군으로 들어 있으면 하나로 (노선이 가장 많은 것) — 도착 정보는 [sameSpot] 으로 같이 물음.
     */
    suspend fun search(query: String, limit: Int = 60): List<WebStop> {
        val q = query.trim()
        val path = WebData.namePath(q) ?: return emptyList()
        val rows = names[path] ?: parseStops(http.getText(base + path)).also { names[path] = it }
        val key = q.replace(" ", "")
        return mergeSameSpot(rows.filter { it.name.replace(" ", "").contains(key) })
            .sortedWith(compareBy({ !it.name.startsWith(q) }, { it.name.length }, { it.name }))
            .take(limit)
    }

    /** 노선 요약들 (묶음 파일을 한꺼번에 받고 기억). 없는 노선은 빠짐 */
    suspend fun routeSums(ids: Collection<String>): Map<String, RouteSum> = coroutineScope {
        val byPath = ids.groupBy { WebData.routeSumPath(it) }
        val loaded = byPath.keys.map { path ->
            async { path to (sums[path] ?: runCatching { parseSums(http.getText(base + path)) }.getOrDefault(emptyMap()).also { sums[path] = it }) }
        }.awaitAll().toMap()
        ids.mapNotNull { id -> loaded[WebData.routeSumPath(id)]?.get(id)?.let { id to it } }.toMap()
    }

    /** 노선 전체 (정류장 순서·좌표·시간표). 파일의 예전 id 로 찾고 지금 id 로 돌려줌 ([TagoIds]) */
    suspend fun route(id: String): RouteFull? {
        val legacy = TagoIds.legacy(id)
        val path = WebData.routePath(legacy)
        val file = routeFiles[path] ?: parseRoutes(http.getText(base + path)).also { routeFiles[path] = it }
        return file[legacy]?.copy(id = TagoIds.current(legacy))
    }

    /**
     * 노선 도로 모양 (서버가 한 번 만들어 저장해 둔 것, 처음 보는 노선이면 서버가 만드느라 몇 초 걸림). 못 받으면 null → 정류장 직선.
     */
    suspend fun shape(routeId: String): List<Pair<Double, Double>>? = runCatching {
        val json = http.getText(base + "w/shape/" + TagoIds.legacy(routeId))
        val poly = (MiniJson.parse(json) as? Map<*, *>)?.get("poly")?.toString() ?: return null
        decodePolyline(poly, 5).takeIf { it.size >= 2 }
    }.getOrNull()

    /** 노선의 실시간 버스 위치 (웹 통로). TAGO 가 다루지 않는 노선(서울·경기·교통안전공단)은 [Positions.Unsupported] */
    suspend fun positions(route: RouteFull): Positions {
        if (route.city.isEmpty() || !route.city.all(Char::isDigit) || OTHER_SOURCES.any { route.id.startsWith(it) } || route.id.startsWith("TSB")) {
            return Positions.Unsupported
        }
        return try {
            val xml = http.getText(
                base + "w/dg/1613000/BusLcInfoInqireService/getRouteAcctoBusLcList",
                query = listOf("cityCode" to route.city, "routeId" to route.id),
            )
            Positions.Ok(TagoXml.parse(xml).rows.map { r ->
                com.eunho.tagohub.data.BusLocation(
                    nodeId = r.str("nodeid"), nodeName = r.str("nodenm"), ord = r.int("nodeord"),
                    vehicleNo = r.str("vehicleno"), lat = r.double("gpslati"), lng = r.double("gpslong"),
                )
            })
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Positions.Failed(e.message ?: "알 수 없는 오류")
        }
    }

    /** 이 범위 안의 정류장 (지도에 보이는 칸 조각들, 폐지·미사용 정류장도 칸에는 있어서 노선이 서는 것만) */
    suspend fun stopsIn(south: Double, west: Double, north: Double, east: Double, limit: Int = 400): List<WebStop> {
        val names = WebData.tiles(south, west, north, east)
        if (names.size > 12) return emptyList()   // 너무 넓으면 받지 않음 (지도를 더 확대해야 보임)
        return coroutineScope { names.map { async { tile(it) } }.awaitAll() }.flatten()
            .filter { it.lat in south..north && it.lng in west..east && (it.routes.isNotEmpty() || it.city.startsWith("TS") || it.id.startsWith("JEB") || it.id.startsWith("JUB")) }
            .let { mergeSameSpot(it) }
            .take(limit)
    }

    /** 정류장 id 로 정류장 (그 자리 칸 조각에서). 노선 화면에서 정류장을 누를 때 */
    suspend fun stopAt(id: String, lat: Double, lng: Double): WebStop? {
        val legacy = TagoIds.legacy(id)
        return tile(WebData.tile(lat, lng)).firstOrNull { it.id == legacy || it.id == id }
    }

    /** 정류장 화면 첫 표시: 이 정류장과 같은 자리 정류장들에 서는 노선 요약 (실시간 없이, 서버 자료만이라 바로 나옴) */
    suspend fun stopRoutes(stop: WebStop): List<RouteSum> {
        val ids = (listOf(stop) + sameSpot(stop)).flatMap { it.routes }.distinct()
        return routeSums(ids).values.distinctBy { TagoIds.current(it.id) }.map { it.copy(id = TagoIds.current(it.id)) }
    }

    /** cities.json (한 번만 받음, 동시에 여럿이 물어도 한 번) */
    private suspend fun cities(): Cities = citiesLock.withLock {
        cities ?: runCatching {
            val root = MiniJson.parse(http.getText(base + WebData.CITIES_PATH)) as Map<*, *>
            fun map(k: String) = (root[k] as? Map<*, *>)?.entries?.associate { it.key.toString() to it.value.toString() }.orEmpty()
            Cities(map("names"), map("alias"))
        }.getOrDefault(Cities(emptyMap(), emptyMap())).also { cities = it }
    }

    /** 시군 코드 → 이름 */
    suspend fun cityName(code: String): String? = cities().names[code]

    private suspend fun tile(name: String): List<WebStop> =
        tiles[name] ?: runCatching { parseStops(http.getText(base + WebData.tilePath(name))) }.getOrDefault(emptyList()).also { tiles[name] = it }

    /**
     * TAGO 에 물을 도시코드 후보. 보통은 정류장의 시군 그대로. 교통안전공단 정류장(TS… 시군)은 대응표 → 근처 같은 종류 정류장의 TAGO 시군
     * (폰 앱 tsbTargets 와 같은 생각: 한 정류장에 여러 시군 버스가 서면 시군마다 물어야 다 나옴)
     */
    internal suspend fun cityCandidates(stop: WebStop): List<String> {
        if (!stop.city.startsWith("TS")) return listOf(stop.city)
        val out = LinkedHashSet<String>()
        cities().alias[stop.city]?.let { out += it }
        val near = WebData.tiles(stop.lat - 0.07, stop.lng - 0.07, stop.lat + 0.07, stop.lng + 0.07).flatMap { tile(it) }
            .filter { it.id.startsWith("TSB") && it.city.isNotEmpty() && it.city.all(Char::isDigit) }
            .groupBy { it.city }
            .mapValues { (_, l) -> l.minOf { (it.lat - stop.lat) * (it.lat - stop.lat) + (it.lng - stop.lng) * (it.lng - stop.lng) } }
            .entries.sortedBy { it.value }.map { it.key }
        out += near
        return out.take(MAX_CITIES)
    }

    /**
     * 같은 자리의 다른 시군 정류장 ([SAME_SPOT_M] 안, 시군마다 가장 가까운 하나). 폰 앱 StopDb.sameSpot 과 같음:
     * 교통안전공단 파일의 정류장(TSB…)과 그 시의 정류장(JJB… 등)이 같은 곳에 따로 들어 있어서, 둘 다 물어야 버스가 다 나옴.
     */
    internal suspend fun sameSpot(stop: WebStop): List<WebStop> {
        val d = 0.005
        return WebData.tiles(stop.lat - d, stop.lng - d, stop.lat + d, stop.lng + d).flatMap { tile(it) }
            .filter { it.id != stop.id }
            .filter { o ->
                val m = meters(stop.lat, stop.lng, o.lat, o.lng)
                // 정류장 번호가 같은 것(같은 시군이어도), 또는 다른 시군의 바로 붙은 정류장
                (stop.no != null && o.no == stop.no && m <= SAME_NO_M) || (o.city != stop.city && m <= SAME_SPOT_M)
            }
            .groupBy { it.city + "|" + (it.no ?: it.id) }
            .map { (_, l) -> l.minBy { meters(stop.lat, stop.lng, it.lat, it.lng) } }
    }

    /** 실제로 물을 (도시코드, 정류장 id): 같은 자리 정류장 먼저 (그 시의 id 가 TAGO 에 더 잘 나옴), 그다음 고른 정류장 */
    internal suspend fun targets(stop: WebStop): List<Pair<String, String>> {
        val out = LinkedHashSet<Pair<String, String>>()
        for (s in sameSpot(stop).filter(::tagoStop) + listOf(stop).filter(::tagoStop)) {
            for (c in cityCandidates(s)) out += c to s.id
        }
        return out.take(MAX_TARGETS)
    }

    /** 정류장 실시간 도착 (가까운 순). TAGO 가 다루지 않는 지역(서울·경기 등)은 [Arrivals.Unsupported] */
    suspend fun arrivals(stop: WebStop, nowMs: Long): Arrivals {
        return try {
            val todo = targets(stop)
            if (todo.isEmpty()) return Arrivals.Unsupported
            val results = coroutineScope { todo.map { (c, id) -> async { runCatching { arrivalsIn(c, id) } } }.awaitAll() }
            if (results.all { it.isFailure }) throw results.first().exceptionOrNull()!!
            val list = results.flatMap { it.getOrDefault(emptyList()) }
                .distinctBy { it.routeId + "|" + it.arrSeconds }
                .sortedBy { it.arrSeconds ?: Int.MAX_VALUE }
            Arrivals.Ok(list, nowMs)
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Arrivals.Failed(e.message ?: "알 수 없는 오류")
        }
    }

    private suspend fun arrivalsIn(cityCode: String, nodeId: String): List<BusArrival> {
        val xml = http.getText(
            base + "w/dg/1613000/ArvlInfoInqireService/getSttnAcctoArvlPrearngeInfoList",
            query = listOf("cityCode" to cityCode, "nodeId" to TagoIds.current(nodeId)),
        )
        return TagoXml.parse(xml).rows.map { r ->
            BusArrival(
                routeId = r.text("routeid"),
                routeNo = r.text("routeno"),
                routeType = r.str("routetp"),
                vehicleType = r.str("vehicletp"),
                prevStops = r.int("arrprevstationcnt"),
                arrSeconds = r.int("arrtime"),
            )
        }
    }

    companion object {
        /** 한 정류장에 물어볼 시군 수 (웹 통로 요청 수 제한 안에서) */
        const val MAX_CITIES = 3
        /** 한 번 새로 고칠 때 묻는 최대 수 (15초마다 → 분당 20번, 서버 제한 120번 안) */
        const val MAX_TARGETS = 5
        /** 같은 정류장으로 보는 거리 (앱과 같게 30m) */
        const val SAME_SPOT_M = 30.0
        /** 정류장 번호가 같으면 이만큼 떨어져 있어도 같은 정류장 (자료마다 좌표가 조금씩 다름) */
        const val SAME_NO_M = 500.0

        /** 두 점 사이 거리(m), 짧은 거리용 근사 */
        fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dy = (lat2 - lat1) * 111_000.0
            val dx = (lng2 - lng1) * 111_000.0 * kotlin.math.cos(lat1 * kotlin.math.PI / 180)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        /** 폰 앱은 따로 받는 지역 (서울·경기 버스 API): TAGO 에는 실시간이 없어 웹 통로로는 못 받음 */
        private val OTHER_SOURCES = listOf("SEB", "GGB")

        fun tagoStop(stop: WebStop): Boolean = stop.city.isNotEmpty() && OTHER_SOURCES.none { stop.id.startsWith(it) }

        /** 같은 이름·같은 자리 정류장 묶기: 노선이 가장 많은 것(같으면 TAGO 시군 코드인 것) 하나만 */
        fun mergeSameSpot(stops: List<WebStop>): List<WebStop> {
            val out = ArrayList<WebStop>()
            val ordered = stops.sortedWith(compareBy({ -it.routes.size }, { it.city.startsWith("TS") }))
            for (s in ordered) if (out.none { same(it, s) }) out += s
            return out
        }

        /**
         * 같은 정류장인지: 정류장 번호가 같으면(여러 시군·자료에 따로 들어 있어도) 같은 정류장 ([SAME_NO_M] 안),
         * 번호가 없거나 달라도 이름이 같고 바로 붙어 있으면([SAME_SPOT_M] 안) 같은 정류장
         */
        fun same(a: WebStop, b: WebStop): Boolean {
            val d = meters(a.lat, a.lng, b.lat, b.lng)
            if (a.no != null && a.no == b.no && d <= SAME_NO_M) return true
            return a.name == b.name && d <= SAME_SPOT_M
        }

        /** routesum 조각: {노선 id: [번호, 기점, 종점, 종류, 첫차, 막차, 배차, 토, 일]} */
        fun parseSums(json: String): Map<String, RouteSum> {
            val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyMap()
            fun Any?.str() = this?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            return root.entries.mapNotNull { (k, v) ->
                val a = v as? List<*> ?: return@mapNotNull null
                val id = k.toString()
                id to RouteSum(
                    id, a.getOrNull(0).str() ?: return@mapNotNull null, a.getOrNull(1).str(), a.getOrNull(2).str(), a.getOrNull(3).str(),
                    a.getOrNull(4).str(), a.getOrNull(5).str(),
                    a.getOrNull(6).str()?.toDoubleOrNull()?.toInt(), a.getOrNull(7).str()?.toDoubleOrNull()?.toInt(), a.getOrNull(8).str()?.toDoubleOrNull()?.toInt(),
                )
            }.toMap()
        }

        /** Google 인코딩 폴리라인 → (위도, 경도) 목록 */
        fun decodePolyline(s: String, precision: Int): List<Pair<Double, Double>> {
            var factor = 1.0
            repeat(precision) { factor *= 10 }
            val out = ArrayList<Pair<Double, Double>>()
            var i = 0
            var lat = 0L
            var lng = 0L
            fun next(): Long {
                var shift = 0
                var result = 0L
                var b: Int
                do {
                    b = s[i++].code - 63
                    result = result or ((b and 0x1f).toLong() shl shift)
                    shift += 5
                } while (b >= 0x20 && i < s.length)
                return if (result and 1L != 0L) (result shr 1).inv() else result shr 1
            }
            while (i < s.length) {
                lat += next()
                if (i >= s.length) break
                lng += next()
                out += lat / factor to lng / factor
            }
            return out
        }

        /** routes 조각: {노선 id: {no, start, end, city, region, rtype, realtime, info, stops}} */
        fun parseRoutes(json: String): Map<String, RouteFull> {
            val root = MiniJson.parse(json) as? Map<*, *> ?: return emptyMap()
            fun Any?.str() = this?.toString()?.takeIf { it.isNotBlank() && it != "null" }
            return root.entries.mapNotNull { (k, v) ->
                val m = v as? Map<*, *> ?: return@mapNotNull null
                val id = k.toString()
                val info = m["info"] as? List<*>
                val no = m["no"].str() ?: return@mapNotNull null
                val sum = info?.let {
                    RouteSum(id, no, m["start"].str(), m["end"].str(), m["rtype"].str(), it.getOrNull(0).str(), it.getOrNull(1).str(),
                        it.getOrNull(2).str()?.toDoubleOrNull()?.toInt(), it.getOrNull(3).str()?.toDoubleOrNull()?.toInt(), it.getOrNull(4).str()?.toDoubleOrNull()?.toInt())
                }
                val stops = (m["stops"] as? List<*>).orEmpty().mapNotNull { r ->
                    val a = r as? List<*> ?: return@mapNotNull null
                    RouteStopW(a.getOrNull(0).str()?.toDoubleOrNull()?.toInt() ?: 0, a.getOrNull(1).str(), a.getOrNull(2).str().orEmpty(),
                        a.getOrNull(3).str()?.toDoubleOrNull(), a.getOrNull(4).str()?.toDoubleOrNull(), a.getOrNull(5).str())
                }.sortedBy { it.seq }
                id to RouteFull(id, no, m["start"].str(), m["end"].str(), m["city"].str().orEmpty(), m["region"].str(), m["rtype"].str(), sum, stops)
            }.toMap()
        }

        /** 조각 JSON 을 정류장 목록으로 */
        fun parseStops(json: String): List<WebStop> {
            val rows = MiniJson.parse(json) as? List<*> ?: return emptyList()
            return rows.mapNotNull { r ->
                val a = r as? List<*> ?: return@mapNotNull null
                WebStop(
                    id = a.getOrNull(0)?.toString() ?: return@mapNotNull null,
                    name = a.getOrNull(1)?.toString().orEmpty(),
                    lat = a.getOrNull(2)?.toString()?.toDoubleOrNull() ?: return@mapNotNull null,
                    lng = a.getOrNull(3)?.toString()?.toDoubleOrNull() ?: return@mapNotNull null,
                    no = a.getOrNull(4)?.toString()?.takeIf { it.isNotBlank() && it != "null" },
                    city = a.getOrNull(5)?.toString().orEmpty(),
                    routes = (a.getOrNull(6) as? List<*>)?.map { it.toString() }.orEmpty(),
                )
            }
        }
    }
}
