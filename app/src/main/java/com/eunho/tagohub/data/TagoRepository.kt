package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.core.TagoResult
import com.eunho.tagohub.core.TagoTime
import com.eunho.tagohub.core.TagoXml
import com.eunho.tagohub.core.naturalOrder
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import retrofit2.HttpException
import java.io.IOException
import java.net.SocketTimeoutException
import java.time.LocalDate
import java.time.LocalDateTime

class TagoRepository(private val api: TagoApi) {

    // ───────────────────────── 공통 ─────────────────────────

    private suspend fun <T> run(block: suspend () -> T): TagoResult<T> {
        if (!Network.hasKey) {
            return TagoResult.Error("서비스키가 없습니다. local.properties 에 TAGO_SERVICE_KEY=... 를 추가하고 다시 빌드하세요.")
        }
        return try {
            TagoResult.Success(block())
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            TagoResult.Error(message(e))
        }
    }

    private fun message(e: Throwable): String = when (e) {
        is TagoApiException -> e.message ?: "API 오류"
        is HttpException -> {
            // 4xx/5xx 바디에 게이트웨이 오류 XML 이 들어있는 경우가 많다
            val body = runCatching { e.response()?.errorBody()?.string() }.getOrNull()
            body?.let { runCatching { TagoXml.parse(it) }.exceptionOrNull()?.message } ?: "HTTP ${e.code()} 오류"
        }
        is SocketTimeoutException -> "응답 시간이 초과됐습니다. 잠시 후 다시 시도하세요."
        is IOException -> "네트워크 연결을 확인하세요."
        else -> e.message ?: e.javaClass.simpleName
    }

    /** CancellationException 은 다시 던지는 runCatching */
    private suspend fun <T> attempt(block: suspend () -> T): Result<T> = try {
        Result.success(block())
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Result.failure(e)
    }

    /**
     * 전체 페이지 수집 + 페이지 크기 폴백 래더 (TagoTransit KAC 패턴과 동일).
     * 서버가 큰 numOfRows 를 거부하면(HTTP_ERROR 04, 타임아웃 등) 한 단계 작게 재시도.
     * 키·권한 오류처럼 재시도해도 소용없는 오류는 즉시 던진다.
     */
    /**
     * TAGO 는 동시 요청이 많으면 "가용한 세션이 존재하지 않습니다"(코드 99) 로 거절한다.
     * 도착정보가 새로고침 때마다 떴다 안 떴다 하던 원인 → 동시 요청을 4개로 제한하고 99 는 잠깐 쉬었다 재시도.
     */
    private val gate = Semaphore(4)

    private suspend fun fetch(page: Int, rows: Int, call: suspend (page: Int, rows: Int) -> String): com.eunho.tagohub.core.TagoPage {
        var attempt = 0
        while (true) {
            try {
                return gate.withPermit { TagoXml.parse(call(page, rows)) }
            } catch (e: TagoApiException) {
                if (e.code != "99" || attempt >= 2) throw e
                attempt++
                delay(400L * attempt)
            }
        }
    }

    private suspend fun all(
        ladder: List<Int> = listOf(1000, 300, 100),
        maxPages: Int = 30,
        call: suspend (page: Int, rows: Int) -> String,
    ): List<Row> {
        var lastError: Exception? = null
        for (rows in ladder) {
            val first = try {
                fetch(1, rows, call)
            } catch (e: CancellationException) {
                throw e
            } catch (e: TagoApiException) {
                if (e.isFatal) throw e
                lastError = e; continue
            } catch (e: Exception) {
                lastError = e; continue
            }
            val out = first.rows.toMutableList()
            var page = 2
            while (out.size < first.totalCount && page <= maxPages) {
                val next = fetch(page, rows, call)
                if (next.rows.isEmpty()) break
                out += next.rows
                page++
            }
            return out
        }
        throw lastError ?: TagoApiException("EMPTY", "응답이 없습니다.")
    }

    private val mutex = Mutex()
    private val cache = HashMap<String, Any>()

    @Suppress("UNCHECKED_CAST")
    private suspend fun <T : Any> cached(key: String, load: suspend () -> T): T {
        mutex.withLock { cache[key]?.let { return it as T } }
        val v = load()
        mutex.withLock { cache[key] = v }
        return v
    }

    private fun search(list: List<Place>, q: String) = PlaceFilter.filter(list, q)

    // ───────────────────────── 시내버스 ─────────────────────────

    private suspend fun busCityList(): List<City> = cached("busCities") {
        all(listOf(300)) { p, n -> api.busCities(p, n) }
            .map(Mappers::city).filter { it.code.isNotBlank() }.sortedBy { it.name }
    }

    /** 버스 도시 전체 (전국 통합 검색용) */
    suspend fun allBusCities() = run { busCityList() }

    /** 도시 코드 → 이름 (지도 위치로 지역을 자동 판별할 때 표시용) */
    suspend fun busCityName(code: String): String? = try {
        busCityList().firstOrNull { it.code == code }?.name
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        null
    }

    /** 정류소명·정류소번호·노선번호 통합 검색 (병렬) */
    suspend fun searchBus(cityCode: String, q: String) = run {
        coroutineScope {
            val byName = async {
                attempt { all(listOf(100), 3) { p, n -> api.busStops(cityCode, q, null, p, n) }.map { Mappers.busStop(it, cityCode) } }
            }
            val byNo = if (q.all(Char::isDigit)) async {
                attempt { all(listOf(100), 2) { p, n -> api.busStops(cityCode, null, q, p, n) }.map { Mappers.busStop(it, cityCode) } }
            } else null
            val routes = async {
                attempt { all(listOf(100), 3) { p, n -> api.busRoutes(cityCode, q, p, n) }.map { Mappers.busRoute(it).copy(cityCode = cityCode) } }
            }
            val parts = listOfNotNull(byNo?.await(), byName.await())
            val r = routes.await()
            val failures = parts.mapNotNull { it.exceptionOrNull() } + listOfNotNull(r.exceptionOrNull())
            if (failures.size == parts.size + 1) throw failures.first()

            BusSearch(
                stops = parts.flatMap { it.getOrDefault(emptyList()) }.distinctBy { it.nodeId },
                routes = r.getOrDefault(emptyList()).distinctBy { it.routeId }
                    .sortedWith(compareBy<BusRoute, String>(naturalOrder) { it.routeNo }),
                warning = failures.firstOrNull()?.let { "일부 결과를 불러오지 못했습니다: ${message(it)}" },
            )
        }
    }

    /** 노선번호 검색만 (정류장은 내장 DB 에서 찾으므로 전국 검색 호출이 절반으로 준다) */
    suspend fun routesByNo(cityCode: String, q: String) = run {
        all(listOf(100), 3) { p, n -> api.busRoutes(cityCode, q, p, n) }
            .map { Mappers.busRoute(it).copy(cityCode = cityCode) }
            .distinctBy { it.routeId }
    }

    suspend fun busStopsNear(lat: Double, lng: Double) = run {
        val stops = all(listOf(100), 2) { p, n -> api.busStopsNear(lat, lng, p, n) }.map { Mappers.busStop(it) }
        BusSearch(
            stops = stops.sortedBy { s -> if (s.lat != null && s.lng != null) Geo.meters(lat, lng, s.lat, s.lng) else Double.MAX_VALUE },
            routes = emptyList(),
            origin = lat to lng,
        )
    }

    suspend fun stopArrivals(cityCode: String, nodeId: String) = run {
        all(listOf(100), 3) { p, n -> api.busArrivals(cityCode, nodeId, p, n) }
            .map(Mappers::busArrival).sortedBy { it.arrSeconds ?: Int.MAX_VALUE }
    }

    suspend fun stopRoutes(cityCode: String, nodeId: String) = run {
        all(listOf(100), 3) { p, n -> api.busStopRoutes(cityCode, nodeId, p, n) }
            .map(Mappers::busRoute).distinctBy { it.routeId }
    }

    suspend fun routeInfo(cityCode: String, routeId: String) = run {
        all(listOf(10), 1) { _, _ -> api.busRouteInfo(cityCode, routeId) }.firstOrNull()?.let(Mappers::busRoute)
    }

    suspend fun routeStops(cityCode: String, routeId: String) = run {
        all(listOf(300, 100)) { p, n -> api.busRouteStops(cityCode, routeId, p, n) }
            .map(Mappers::routeStop).sortedBy { it.ord }
    }

    suspend fun busLocations(cityCode: String, routeId: String) = run {
        all(listOf(100), 3) { p, n -> api.busLocations(cityCode, routeId, p, n) }.map(Mappers::busLocation)
    }

    // ───────────────────────── 지하철 ─────────────────────────

    // 역 ID·시간표·출구는 잘 안 바뀌어서 한 번 받으면 휴대폰에 계속 저장 (앱 업데이트 때 새로 받음)

    suspend fun subwayStations(q: String): TagoResult<List<SubwayStation>> {
        withContext(Dispatchers.IO) { StaticCache.subwayStations(q) }?.let { return TagoResult.Success(it) }
        val r = run {
            all(listOf(100), 2) { p, n -> api.subwayStations(q, p, n) }
                .map(Mappers::subwayStation).filter { it.id.isNotBlank() }
        }
        if (r is TagoResult.Success) withContext(Dispatchers.IO) { StaticCache.putSubwayStations(q, r.data) }
        return r
    }

    suspend fun subwaySchedule(stationId: String, dailyType: String, upDown: String): TagoResult<List<SubwayDeparture>> {
        withContext(Dispatchers.IO) { StaticCache.schedule(stationId, dailyType, upDown) }?.let { return TagoResult.Success(it) }
        val r = run { Mappers.cleanSchedule(all(maxPages = 10) { p, n -> api.subwaySchedule(stationId, dailyType, upDown, p, n) }) }
        if (r is TagoResult.Success) withContext(Dispatchers.IO) { StaticCache.putSchedule(stationId, dailyType, upDown, r.data) }
        return r
    }

    suspend fun subwayExits(stationId: String): TagoResult<List<ExitInfo>> {
        withContext(Dispatchers.IO) { StaticCache.exits(stationId) }?.let { return TagoResult.Success(it) }
        val r = subwayExitsFromApi(stationId)
        if (r is TagoResult.Success) withContext(Dispatchers.IO) { StaticCache.putExits(stationId, r.data) }
        return r
    }

    private suspend fun subwayExitsFromApi(stationId: String) = run {
        coroutineScope {
            val b = async { attempt { all(listOf(300, 100)) { p, n -> api.subwayExitBuses(stationId, p, n) } } }
            val f = async { attempt { all(listOf(300, 100)) { p, n -> api.subwayExitFacilities(stationId, p, n) } } }
            val br = b.await()
            val fr = f.await()
            if (br.isFailure && fr.isFailure) throw br.exceptionOrNull()!!
            Mappers.exits(br.getOrDefault(emptyList()), fr.getOrDefault(emptyList()))
        }
    }

    // ───────────────────────── 기차 ─────────────────────────

    @Volatile private var trainStationCache: List<Place>? = null

    /** 시·도 17곳의 역 목록을 병렬로 받아 전국 역 검색을 만든다 (최초 1회) */
    private suspend fun trainStationList(): List<Place> {
        trainStationCache?.let { return it }
        val cities = all(listOf(100)) { p, n -> api.trainCities(p, n) }.map(Mappers::city)
        val results = coroutineScope {
            cities.map { c ->
                async { attempt { all(listOf(300, 100)) { p, n -> api.trainStations(c.code, p, n) }.map { Mappers.trainStation(it, c.name) } } }
            }.awaitAll()
        }
        val ok = results.mapNotNull { it.getOrNull() }
        if (ok.isEmpty()) throw results.firstNotNullOf { it.exceptionOrNull() }
        val list = ok.flatten().filter { it.id.isNotBlank() }.distinctBy { it.id }.sortedBy { it.name }
        if (results.all { it.isSuccess }) trainStationCache = list // 일부 실패 시 다음에 다시 시도
        return list
    }

    suspend fun trainStationPlaces(q: String) = run { search(trainStationList(), q) }

    suspend fun trainKinds() = run {
        cached("trainKinds") { all(listOf(100)) { p, n -> api.trainKinds(p, n) }.map(Mappers::trainKind) }
    }

    suspend fun trainTrips(dep: String, arr: String, date: LocalDate, grade: String?) = run {
        all(listOf(300, 100), 5) { p, n -> api.trainTrips(dep, arr, TagoTime.ymd(date), grade, p, n) }
            .map(Mappers::trainTrip).sortedBy { it.dep ?: LocalDateTime.MAX }
    }

    // ───────────────────────── 항공 ─────────────────────────

    suspend fun airportPlaces(q: String) = run {
        search(cached("airports") { all(listOf(100)) { p, n -> api.airports(p, n) }.map(Mappers::airport) }, q)
    }

    suspend fun airlines() = run {
        cached("airlines") { all(listOf(100)) { p, n -> api.airlines(p, n) }.map(Mappers::airline) }
    }

    suspend fun flights(dep: String, arr: String, date: LocalDate, airline: String?) = run {
        all(listOf(300, 100), 5) { p, n -> api.flights(dep, arr, TagoTime.ymd(date), airline, p, n) }
            .map(Mappers::flight).sortedBy { it.dep ?: LocalDateTime.MAX }
    }

    // ───────────────────────── 고속버스 ─────────────────────────

    suspend fun expTerminalPlaces(q: String) = run {
        val list = cached("expTerminals") {
            all { p, n -> api.expTerminals(null, p, n) }.map(Mappers::terminal).filter { it.id.isNotBlank() }
        }
        // 전체 목록이 비어 오면 서버 검색으로 폴백
        if (list.isEmpty() && q.isNotBlank()) {
            all(listOf(100)) { p, n -> api.expTerminals(q, p, n) }.map(Mappers::terminal)
        } else search(list, q)
    }

    suspend fun expGrades() = run {
        cached("expGrades") { all(listOf(100)) { p, n -> api.expGrades(p, n) }.map(Mappers::grade) }
    }

    suspend fun expTrips(dep: String, arr: String, date: LocalDate, grade: String?) = run {
        all(listOf(300, 100), 5) { p, n -> api.expTrips(dep, arr, TagoTime.ymd(date), grade, p, n) }
            .map(Mappers::coachTrip).sortedBy { it.dep ?: LocalDateTime.MAX }
    }

    // ───────────────────────── 시외버스 ─────────────────────────

    suspend fun suburbTerminalPlaces(q: String) = run {
        val list = cached("suburbTerminals") {
            all { p, n -> api.suburbTerminals(null, p, n) }.map(Mappers::terminal).filter { it.id.isNotBlank() }
        }
        if (list.isEmpty() && q.isNotBlank()) {
            all(listOf(100)) { p, n -> api.suburbTerminals(q, p, n) }.map(Mappers::terminal)
        } else search(list, q)
    }

    suspend fun suburbGrades() = run {
        cached("suburbGrades") { all(listOf(100)) { p, n -> api.suburbGrades(p, n) }.map(Mappers::grade) }
    }

    suspend fun suburbTrips(dep: String, arr: String, date: LocalDate, grade: String?) = run {
        all(listOf(300, 100), 5) { p, n -> api.suburbTrips(dep, arr, TagoTime.ymd(date), grade, p, n) }
            .map(Mappers::coachTrip).sortedBy { it.dep ?: LocalDateTime.MAX }
    }

    // ───────────────────────── 고속버스 실시간 도착 ─────────────────────────

    suspend fun expArrDepPlaces(q: String) = run {
        val list = cached("expArrTerminals") {
            all { p, n -> api.expArrTerminals(null, p, n) }.map(Mappers::expArrTerminal).filter { it.id.isNotBlank() }
        }
        if (list.isEmpty() && q.isNotBlank()) {
            all(listOf(100)) { p, n -> api.expArrTerminals(q, p, n) }.map(Mappers::expArrTerminal)
        } else search(list, q)
    }

    suspend fun expArrDestPlaces(depCode: String, q: String) = run {
        search(
            cached("expArrDest:$depCode") {
                all(listOf(300, 100)) { p, n -> api.expArrDestinations(depCode, p, n) }.map(Mappers::expArrTerminal)
            },
            q,
        )
    }

    suspend fun expArrivals(depCode: String, arrCode: String) = run {
        all(listOf(100), 5) { p, n -> api.expArrivals(depCode, arrCode, p, n) }
            .map(Mappers::expArrival).sortedWith(Mappers.expArrivalOrder)
    }
}
