package com.eunho.tagohub.data

import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoTime

/**
 * 제주 버스정보 (bus.jeju.go.kr/api, JSON, 인증키 없음).
 * 제주 정류장·노선 ID 는 TAGO ID 에서 "JEB" 를 뗀 9자리와 같다 (예: 406000191 ↔ JEB406000191).
 */
object Jeju {
    const val PREFIX = "JEB"
    const val CITY = "39"
    fun isJeju(tagoId: String) = tagoId.startsWith(PREFIX)
    fun std(tagoId: String) = tagoId.removePrefix(PREFIX)
    fun tago(stdId: String) = PREFIX + stdId.trim()
}

object JejuMappers {
    /** 정류장 도착 예정: PREDICT_TRAV_TM 분, REMAIN_STATION 남은 정류장, LOW_PLATE_TP 저상 Y/N */
    fun arrival(r: Row) = BusArrival(
        routeId = Jeju.tago(r.text("ROUTE_ID")),
        routeNo = r.text("ROUTE_NUM"),
        routeType = null,
        vehicleType = if (r.str("LOW_PLATE_TP").equals("Y", true)) "저상버스" else null,
        prevStops = r.int("REMAIN_STATION"),
        arrSeconds = r.int("PREDICT_TRAV_TM")?.times(60),
        atStop = r.str("CURR_STATION_NM"),
        towards = r.str("ROUTE_SUB_NM"),
    )

    /** 노선 정보 (노선 목록·정류장 통과 노선 공통) */
    fun route(r: Row) = BusRoute(
        routeId = Jeju.tago(r.text("ROUTE_ID")),
        routeNo = r.text("ROUTE_NUM"),
        routeType = null,
        startName = r.str("ORGT_NM"),
        endName = r.str("DST_NM"),
        firstTime = TagoTime.clockText(r.str("FIRST_TM")),
        lastTime = TagoTime.clockText(r.str("LAST_TM")),
        interval = r.int("MIN_INTERVAL"),
        intervalSat = null,
        intervalSun = r.int("MAX_INTERVAL"),
        cityCode = Jeju.CITY,
    )

    /** 노선 경유 정류장 (좌표는 내장 DB 등에서) */
    fun routeStops(rows: List<Row>, coord: (String) -> LatLng?): List<RouteStop> = rows.mapNotNull { r ->
        val sid = r.str("STATION_ID") ?: return@mapNotNull null
        val id = Jeju.tago(sid)
        val c = coord(id)
        RouteStop(id, r.text("STATION_NM"), null, r.int("STATION_ORD") ?: 0, null, c?.lat, c?.lng)
    }.sortedBy { it.ord }

    /**
     * 버스 위치 (전체 버스 중 이 노선만). LOCAL_X 경도, LOCAL_Y 위도.
     * 응답에 순번이 없어서 최근 정류장을 노선 정류장 목록에서 찾아 붙인다 (순환 노선은 GPS 와 가장 가까운 쪽).
     */
    fun buses(rows: List<Row>, routeStdId: String, stops: List<RouteStop>): List<BusLocation> = rows
        .filter { it.str("ROUTE_ID") == routeStdId }
        .mapNotNull { r ->
            val lat = r.double("LOCAL_Y")
            val lng = r.double("LOCAL_X")
            val sid = r.str("CURR_STATION_ID")?.let(Jeju::tago)
            val cands = stops.filter { it.nodeId == sid }
            val at = if (cands.size <= 1 || lat == null || lng == null) cands.firstOrNull() else cands.minBy {
                com.eunho.tagohub.core.Geo.meters(lat, lng, it.lat ?: lat, it.lng ?: lng)
            }
            BusLocation(
                nodeId = sid,
                nodeName = r.str("CURR_STATION_NM"),
                ord = at?.ord,
                vehicleNo = r.str("PLATE_NO") ?: r.str("VH_ID") ?: return@mapNotNull null,
                lat = lat,
                lng = lng,
                lowFloor = r.str("LOW_PLATE_TP")?.let { it.equals("Y", true) },
            )
        }
}
