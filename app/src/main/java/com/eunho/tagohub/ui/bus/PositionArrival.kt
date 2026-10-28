package com.eunho.tagohub.ui.bus

import com.eunho.tagohub.core.valueOrNull
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.RouteLive
import com.eunho.tagohub.data.RouteSpeed
import com.eunho.tagohub.data.TagoRepository
import com.eunho.tagohub.data.TripPlanner
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.withTimeoutOrNull

/** 위치로 어림한 도착이 의미 있는 최대 거리(정류장 수). 이보다 멀리 있는 버스는 "곧 온다"고 하기 어려움 */
internal const val MAX_ESTIMATE_STOPS = 25

/** 같은 정류장·노선의 어림 결과를 다시 쓰는 시간 (새로고침마다 노선 API 를 또 부르지 않게) */
private const val ESTIMATE_TTL_MS = 30_000L
private const val ESTIMATE_TIMEOUT_MS = 8_000L
private const val MAX_ESTIMATE_ROUTES = 6


private fun clockMinutes(t: String?): Int? =
    t?.let { Regex("(\\d{1,2}):?(\\d{2})").find(it) }?.let { it.groupValues[1].toInt() * 60 + it.groupValues[2].toInt() }

/**
 * 지금 이 노선(기점·종점별 변형)에 버스가 달리고 있을 수 있는 시각인지: 첫차 15분 전부터 막차 출발 + 한 바퀴 도는 시간 + 10분까지.
 * 전주처럼 같은 번호의 변형 노선(예: 06:00 한 번만 다니는 "효자로전북도청 → 전주동물원")이 버스 위치를 공유하는 경우,
 * 운행이 끝난 변형 노선에 다른 변형 노선의 버스가 잡히는 것을 걸러내는 데 쓴다. 시각을 모르면 true.
 */
internal fun runsNow(first: String?, last: String?, nowMin: Int, tripMinutes: Int, graceMin: Int = 10): Boolean {
    val f = clockMinutes(first) ?: return true
    var l = clockMinutes(last) ?: return true
    var n = nowMin
    if (l < f) { l += 1440; if (n < f) n += 1440 }
    return n >= f - 15 && n <= l + tripMinutes + graceMin
}

/**
 * 변형 노선의 운행 시각 판정에 더하는 여유(분). 전주는 변형마다 같은 버스가 오는 게 확인돼서 빠듯하게(10분),
 * 다른 지역은 시각 자료가 부정확해도 실제 버스를 숨기지 않게 넉넉하게(70분).
 */
internal fun variantGraceMin(routeId: String) = if (com.eunho.tagohub.data.Jeonju.isJeonju(routeId)) 10 else 70

/** 이 노선(변형)에 지금 버스가 달릴 수 있는 시각인지. 정류장·시각 정보가 아직 없으면 true */
internal fun variantRunning(d: BusDest.Route): Boolean {
    val stops = d.stops.value.valueOrNull().orEmpty()
    val info = d.info.value.valueOrNull() ?: return true
    if (stops.isEmpty()) return true
    val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    return runsNow(info.firstTime, info.lastTime, nowMin, tripMinutes(TripPlanner.Line(d.routeId, d.routeNo, stops), stops), variantGraceMin(d.routeId))
}

/** 이 노선 한 번 도는 데 걸리는 시간(분): 첫 정류장 → 끝 정류장. 좌표를 모르면 정류장 수 기준 */
internal fun tripMinutes(line: TripPlanner.Line?, stops: List<com.eunho.tagohub.data.RouteStop>): Int =
    if (stops.size < 2) 0 else Math.round(TripPlanner.minutesBetween(line, stops.first().ord, stops.last().ord)).toInt()

/**
 * 이 정류장에 오는 노선의 버스 위치를 보고 "몇 정류장 전"인지 센다: [BusArrival.prevStops] 가 곧 정류장 수(0 = 이미 와 있음).
 * 시간은 평소 소요 시간에 그 노선의 지금 도로 상황 보정([RouteSpeed])을 곱한 어림값이라 [BusArrival.estimated] 가 true.
 * 버스 위치를 못 받거나 이 정류장 앞쪽에 오는 버스가 없으면 null.
 */
internal suspend fun positionArrival(repo: TagoRepository, stop: BusStop, row: StopRouteRow): BusArrival? {
    val d = BusDest.Route(row.cityCode ?: stop.cityCode, row.routeId, row.routeNo, row.routeType, stop.nodeId)
    loadRouteData(repo, d)
    val stops = d.stops.value.valueOrNull().orEmpty()
    if (stops.isEmpty()) return null
    val line = TripPlanner.Line(d.routeId, d.routeNo, stops)
    // 같은 번호의 다른 기점·종점 노선과 버스 위치를 공유하는 도시가 있어서, 운행 시각 밖의 변형 노선은 버스가 있어도 여기 버스가 아님
    val nowMin = java.time.LocalTime.now().let { it.hour * 60 + it.minute }
    if (!runsNow(row.firstTime, row.lastTime, nowMin, tripMinutes(line, stops), variantGraceMin(row.routeId))) return null
    refreshBusData(repo, d)
    val buses = d.buses.value
    if (buses.isEmpty()) return null
    val busIdx = buses.mapNotNull { b -> stops.indexOfFirst { s -> if (b.ord != null) b.ord == s.ord else b.nodeId == s.nodeId }.takeIf { it >= 0 } }
    // 노선이 같은 정류장을 두 번 지나면(회차) 더 가까이 오는 쪽
    val best = stops.indices.filter { stops[it].nodeId == stop.nodeId }
        .mapNotNull { t -> RouteLive.stopsAway(t, busIdx)?.takeIf { it <= MAX_ESTIMATE_STOPS }?.let { t to it } }
        .minByOrNull { it.second } ?: return null
    val (target, away) = best
    val seconds = if (away == 0) 0 else {
        val usual = TripPlanner.minutesBetween(line, stops[target - away].ord, stops[target].ord)
        Math.round(usual * RouteSpeed.factor(d.routeId) * 60).toInt()
    }
    return BusArrival(
        routeId = row.routeId, routeNo = row.routeNo, routeType = row.routeType,
        vehicleType = null, prevStops = away, arrSeconds = seconds, estimated = true,
        behind = RouteLive.allAway(target, busIdx),
    )
}

/**
 * 도착정보(API)와 버스 위치를 맞춰 본다. 최대 [MAX_ESTIMATE_ROUTES] 개 노선, 동시에.
 *  1) 도착정보가 비었는데(운행종료도 아님) 달리고 있는 노선: 위치로 "N정류장 전 · 약 M분"을 어림해 채운다.
 *     같은 번호의 다른 변형 노선에 이미 실제 도착정보가 있으면 그 노선의 버스일 가능성이 커서 어림하지 않는다.
 *  2) 도착정보와 위치가 어긋나면 위치를 따른다: 이미 정류장에 와 있으면 "정류장 도착", 도착정보의 첫 버스보다 [RouteLive.NEARER_GAP] 정류장 이상 가까운 버스가 위치에 있으면(API 가 빠뜨림) 그 버스를 앞에 끼운다
 *     (도착 예측 API 가 버스 위치보다 느리게 갱신됨).
 * [cache] 는 정류장 화면이 들고 있는 (노선 → 시각, 결과) 표.
 */
internal suspend fun fillFromPositions(
    repo: TagoRepository,
    stop: BusStop,
    rows: List<StopRouteRow>,
    cache: MutableMap<String, Pair<Long, BusArrival?>>,
    now: Long = System.currentTimeMillis(),
): List<StopRouteRow> {
    val liveNumbers = rows.filter { r -> r.arrivals.any { !it.estimated } }.map { it.routeNo }.toSet()
    fun needsEstimate(r: StopRouteRow) = r.arrivals.isEmpty() && !r.ended && r.routeNo !in liveNumbers
    fun needsCheck(r: StopRouteRow) = r.arrivals.firstOrNull()?.let { a -> !a.estimated && (a.prevStops ?: 0) >= 1 } == true
    val targets = rows.filter { needsEstimate(it) || needsCheck(it) }.take(MAX_ESTIMATE_ROUTES)
    if (targets.isEmpty()) return rows
    val found: Map<String, BusArrival?> = coroutineScope {
        targets.map { r ->
            async {
                val hit = cache[r.routeId]?.takeIf { now - it.first < ESTIMATE_TTL_MS }
                r.routeId to if (hit != null) hit.second else {
                    val a = runCatching { withTimeoutOrNull(ESTIMATE_TIMEOUT_MS) { positionArrival(repo, stop, r) } }.getOrNull()
                    cache[r.routeId] = now to a
                    a
                }
            }
        }.awaitAll().toMap()
    }
    return rows.map { r ->
        val pos = found[r.routeId] ?: return@map r
        when {
            r.arrivals.isEmpty() -> r.copy(arrivals = listOf(pos))
            // 도착정보의 첫 버스가 바로 앞(1정류장 이내)인데 위치상 이미 와 있으면 같은 버스: 도착으로 고침 (더 먼 버스면 다른 버스라 아래에서 끼움)
            pos.prevStops == 0 && (r.arrivals.first().prevStops ?: 0) <= 1 -> r.copy(arrivals = listOf(r.arrivals.first().copy(prevStops = 0, arrSeconds = 0)) + r.arrivals.drop(1))
            // 위치에는 훨씬 가까운 버스가 보이는데 도착정보에는 없음(도착 예측 API 가 일부 버스만 줌): 그 버스를 맨 앞에 끼움
            RouteLive.preferPosition(r.arrivals.first().prevStops, pos.behind) -> r.copy(arrivals = (listOf(pos) + r.arrivals).take(2))
            else -> r
        }
    }
}
