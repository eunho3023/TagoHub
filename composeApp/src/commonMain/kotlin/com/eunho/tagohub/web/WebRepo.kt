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
    private var cities: Cities? = null
    private val citiesLock = Mutex()

    /** cities.json: 시군 이름, TS… 시군 → TAGO 도시코드 */
    private class Cities(val names: Map<String, String>, val alias: Map<String, String>)

    /** 이름에 [query] 가 든 정류장 (조각은 검색어 첫 글자로 나눠 둠). 이름이 검색어로 시작하는 것 먼저 */
    suspend fun search(query: String, limit: Int = 60): List<WebStop> {
        val q = query.trim()
        val path = WebData.namePath(q) ?: return emptyList()
        val rows = names[path] ?: parseStops(http.getText(base + path)).also { names[path] = it }
        val key = q.replace(" ", "")
        return rows.filter { it.name.replace(" ", "").contains(key) }
            .sortedWith(compareBy({ !it.name.startsWith(q) }, { it.name.length }, { it.name }))
            .take(limit)
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
        val d = 0.0005
        return WebData.tiles(stop.lat - d, stop.lng - d, stop.lat + d, stop.lng + d).flatMap { tile(it) }
            .filter { it.city != stop.city && it.id != stop.id }
            .map { it to meters(stop.lat, stop.lng, it.lat, it.lng) }
            .filter { it.second <= SAME_SPOT_M }
            .groupBy { it.first.city }
            .map { (_, l) -> l.minBy { it.second }.first }
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

        /** 두 점 사이 거리(m), 짧은 거리용 근사 */
        fun meters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
            val dy = (lat2 - lat1) * 111_000.0
            val dx = (lng2 - lng1) * 111_000.0 * kotlin.math.cos(lat1 * kotlin.math.PI / 180)
            return kotlin.math.sqrt(dx * dx + dy * dy)
        }

        /** 폰 앱은 따로 받는 지역 (서울·경기 버스 API): TAGO 에는 실시간이 없어 웹 통로로는 못 받음 */
        private val OTHER_SOURCES = listOf("SEB", "GGB")

        fun tagoStop(stop: WebStop): Boolean = stop.city.isNotEmpty() && OTHER_SOURCES.none { stop.id.startsWith(it) }

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
