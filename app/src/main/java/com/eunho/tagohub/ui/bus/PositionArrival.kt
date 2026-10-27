package com.eunho.tagohub.ui.bus

import com.eunho.tagohub.core.valueOrNull
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.RouteLive
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

/**
 * 정류장 도착정보(API)에 없는데 지금 운행 중인 노선: 그 노선의 버스 위치로 "N정류장 전 · 약 M분"을 어림해서 채운다.
 * 도착 예측 API 는 일부 버스만 주는 경우가 있어서, 버스가 달리고 있는데 "도착 정보 없음"으로 보이던 것을 막는다.
 * 버스 위치를 못 받거나 이 정류장 앞쪽에 오는 버스가 없으면 null (그대로 "도착 정보 없음").
 */
internal suspend fun positionArrival(repo: TagoRepository, stop: BusStop, row: StopRouteRow): BusArrival? {
    val d = BusDest.Route(row.cityCode ?: stop.cityCode, row.routeId, row.routeNo, row.routeType, stop.nodeId)
    loadRouteData(repo, d)
    val stops = d.stops.value.valueOrNull().orEmpty()
    if (stops.isEmpty()) return null
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
        val line = TripPlanner.Line(d.routeId, d.routeNo, stops)
        Math.round(TripPlanner.minutesBetween(line, stops[target - away].ord, stops[target].ord) * 60).toInt()
    }
    return BusArrival(
        routeId = row.routeId, routeNo = row.routeNo, routeType = row.routeType,
        vehicleType = null, prevStops = away, arrSeconds = seconds, estimated = true,
    )
}

/**
 * [rows] 중 도착정보가 비어 있고(운행종료도 아닌) 노선에 [positionArrival] 로 어림한 도착을 채운다. 최대 [MAX_ESTIMATE_ROUTES] 개, 동시에.
 * [cache] 는 정류장 화면이 들고 있는 (노선 → 시각, 결과) 표.
 */
internal suspend fun fillFromPositions(
    repo: TagoRepository,
    stop: BusStop,
    rows: List<StopRouteRow>,
    cache: MutableMap<String, Pair<Long, BusArrival?>>,
    now: Long = System.currentTimeMillis(),
): List<StopRouteRow> {
    val targets = rows.filter { it.arrivals.isEmpty() && !it.ended }.take(MAX_ESTIMATE_ROUTES)
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
    return rows.map { r -> found[r.routeId]?.let { r.copy(arrivals = listOf(it)) } ?: r }
}
