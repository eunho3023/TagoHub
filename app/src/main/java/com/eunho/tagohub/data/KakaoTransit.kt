package com.eunho.tagohub.data

import com.eunho.tagohub.core.MiniJson

/**
 * 카카오맵 REST API — 대중교통 경로 조회 (GET https://dapi.kakao.com/v2/routing/publictraffic).
 * 요청: start_x·start_y·end_x·end_y (WGS84 경도·위도), 헤더 Authorization: KakaoAK {REST API 키}.
 * 응답: status(OK·STARTNODES_NULL·ENDNODES_NULL·EQUAL_POINTS·INVALID_REQUEST·NO_RESULTS),
 *   routes[].properties(type BUS·SUBWAY·BUS_AND_SUBWAY, totalDistance m, totalTime 초, transfers, fare.value 원)
 *   routes[].steps[].properties(type BUS·SUBWAY·WALKING, guidance, distance m, time 초, stops[].name, vehicles[].name·type)
 *   routes[].steps[].path.points = [[x, y], …] (경도, 위도)
 * 정류장은 이름만 오고 ID 가 없어서, 실시간 도착은 앱이 가까운 같은 이름 정류장을 찾아 붙인다.
 */
object KakaoTransit {
    const val URL = "https://dapi.kakao.com/v2/routing/publictraffic"

    data class Vehicle(val name: String, val type: String?)
    data class Step(val type: String, val guidance: String?, val distance: Int, val time: Int, val stops: List<String>, val vehicles: List<Vehicle>, val points: List<LatLng>)
    data class Route(val type: String, val totalDistance: Int, val totalTime: Int, val transfers: Int, val fare: Int?, val steps: List<Step>)
    data class Result(val status: String, val routes: List<Route>)

    private fun Any?.map(): Map<*, *>? = this as? Map<*, *>
    private fun Any?.list(): List<*> = this as? List<*> ?: emptyList<Any?>()
    private fun Map<*, *>.str(k: String): String? = this[k]?.toString()?.takeIf { it.isNotBlank() && it != "null" }
    private fun Map<*, *>.int(k: String): Int? = str(k)?.toDoubleOrNull()?.toInt()

    fun parse(body: String): Result {
        val root = runCatching { MiniJson.parse(body) }.getOrNull().map() ?: return Result("INVALID_RESPONSE", emptyList())
        val status = root.str("status") ?: "INVALID_RESPONSE"
        val routes = root["routes"].list().mapNotNull { r ->
            val rm = r.map() ?: return@mapNotNull null
            val p = rm["properties"].map() ?: return@mapNotNull null
            val steps = rm["steps"].list().mapNotNull { st ->
                val sm = st.map() ?: return@mapNotNull null
                val sp = sm["properties"].map() ?: return@mapNotNull null
                Step(
                    type = sp.str("type") ?: return@mapNotNull null,
                    guidance = sp.str("guidance"),
                    distance = sp.int("distance") ?: 0,
                    time = sp.int("time") ?: 0,
                    stops = sp["stops"].list().mapNotNull { it.map()?.str("name")?.trim() },
                    vehicles = sp["vehicles"].list().mapNotNull { v -> v.map()?.let { vm -> vm.str("name")?.let { Vehicle(it.trim(), vm.str("type")?.trim()) } } },
                    points = sm["path"].map()?.get("points").list().mapNotNull { pt ->
                        val xy = pt.list()
                        val x = xy.getOrNull(0)?.toString()?.toDoubleOrNull()
                        val y = xy.getOrNull(1)?.toString()?.toDoubleOrNull()
                        if (x != null && y != null) LatLng(y, x) else null
                    },
                )
            }
            Route(p.str("type") ?: "", p.int("totalDistance") ?: 0, p.int("totalTime") ?: 0, p.int("transfers") ?: 0,
                p["fare"].map()?.int("value"), steps)
        }
        return Result(status, routes)
    }

    /** 앱 길찾기 결과로. 버스·지하철 단계 = 구간, 걷기 단계 = 출발·환승·도착 걷기 (실제 경로 포함) */
    fun toPlan(r: Route): TripPlan? {
        val rides = r.steps.filter { it.type == "BUS" || it.type == "SUBWAY" }
        if (rides.isEmpty()) return null
        val legs = rides.map { s ->
            val sub = s.type == "SUBWAY"
            val no = s.vehicles.firstOrNull()?.name ?: s.guidance ?: if (sub) "지하철" else "버스"
            val board = s.stops.firstOrNull() ?: ""
            val alight = s.stops.lastOrNull() ?: ""
            TripLeg(
                routeId = "$ID_PREFIX${if (sub) "SUB" else "BUS"}:$no",
                routeNo = no,
                boardStopId = "$ID_PREFIX$board",
                boardName = board,
                alightStopId = "$ID_PREFIX$alight",
                alightName = alight,
                fromSeq = 0, toSeq = 0,
                stops = (s.stops.size - 1).coerceAtLeast(0),
                meters = s.distance.toDouble(),
                subway = sub,
                boardLat = s.points.firstOrNull()?.lat, boardLng = s.points.firstOrNull()?.lng,
                alightLat = s.points.lastOrNull()?.lat, alightLng = s.points.lastOrNull()?.lng,
                path = s.points.takeIf { it.size >= 2 },
                vehicleType = s.vehicles.firstOrNull()?.type,
                alternatives = s.vehicles.drop(1).map { it.name },
            )
        }
        val firstRide = r.steps.indexOfFirst { it.type != "WALKING" }
        val lastRide = r.steps.indexOfLast { it.type != "WALKING" }
        val walk = r.steps.withIndex().filter { it.value.type == "WALKING" }
        val first = legs.first()
        val last = legs.last()
        return TripPlan(
            origin = BusStop("", first.boardStopId, first.boardName, null, first.boardLat, first.boardLng),
            dest = BusStop("", last.alightStopId, last.alightName, null, last.alightLat, last.alightLng),
            walkStartM = walk.filter { it.index < firstRide }.sumOf { it.value.distance }.toDouble(),
            walkEndM = walk.filter { it.index > lastRide }.sumOf { it.value.distance }.toDouble(),
            legs = legs,
            transferWalkM = walk.filter { it.index in (firstRide + 1) until lastRide }.sumOf { it.value.distance }.toDouble(),
            fixedMinutes = (r.totalTime + 59) / 60,
            source = "카카오 길찾기",
            fare = r.fare,
            walkPaths = walk.map { it.value.points }.filter { it.size >= 2 },
        )
    }

    /** 결과 코드를 사용자 문구로 (경로가 없을 때) */
    fun message(status: String): String? = when (status) {
        "EQUAL_POINTS" -> "출발지와 도착지가 같습니다."
        "STARTNODES_NULL" -> "출발지 근처에 대중교통이 없습니다."
        "ENDNODES_NULL" -> "도착지 근처에 대중교통이 없습니다."
        "NO_RESULTS" -> "대중교통 경로를 찾지 못했습니다."
        else -> null
    }

    /**
     * 노선 모양용: 카카오 대중교통 결과에서 이 노선 번호를 타는 버스 구간 경로.
     * 구간 시작·끝이 요청한 두 정류장(a, b) 200m 안이어야 함 (다른 데서 타는 경로 제외).
     */
    fun ridePath(r: Result, routeNo: String, a: LatLng, b: LatLng): List<LatLng>? {
        fun near(p: LatLng?, q: LatLng) = p != null && com.eunho.tagohub.core.Geo.meters(p.lat, p.lng, q.lat, q.lng) <= 200.0
        return r.routes.asSequence().flatMap { it.steps.asSequence() }
            .filter { it.type == "BUS" && it.points.size >= 2 && it.vehicles.any { v -> sameRouteNo(v.name, routeNo) } }
            .firstOrNull { near(it.points.first(), a) && near(it.points.last(), b) }
            ?.points
    }

    const val ID_PREFIX = "KAKAO:"
    fun isKakao(id: String?) = id?.startsWith(ID_PREFIX) == true

    /** 노선 번호 비교용: 공백·"번" 제거 */
    fun sameRouteNo(a: String?, b: String?): Boolean {
        fun n(s: String?) = s.orEmpty().replace(" ", "").removeSuffix("번")
        return n(a).isNotEmpty() && n(a) == n(b)
    }
}

/** 카카오맵 REST API — 도보·자전거 경로 조회 (routing/walk, routing/bicycle 같은 형식). route.legs[].steps[].path.points = [[x, y], …] */
object KakaoWalk {
    const val URL = "https://dapi.kakao.com/v2/routing/walk"
    const val BICYCLE_URL = "https://dapi.kakao.com/v2/routing/bicycle"

    /** route.properties 의 총 거리(m)·총 시간(초) */
    fun totals(body: String): Pair<Int, Int>? {
        val root = runCatching { com.eunho.tagohub.core.MiniJson.parse(body) }.getOrNull() as? Map<*, *> ?: return null
        val p = (root["route"] as? Map<*, *>)?.get("properties") as? Map<*, *> ?: return null
        val d = p["totalDistance"]?.toString()?.toDoubleOrNull()?.toInt() ?: return null
        val t = p["totalTime"]?.toString()?.toDoubleOrNull()?.toInt() ?: return null
        return d to t
    }

    /** status 와 전체 경로 좌표 (OK 가 아니면 빈 목록) */
    fun parse(body: String): Pair<String, List<LatLng>> {
        val root = runCatching { com.eunho.tagohub.core.MiniJson.parse(body) }.getOrNull() as? Map<*, *> ?: return "INVALID_RESPONSE" to emptyList()
        val status = root["status"]?.toString() ?: "INVALID_RESPONSE"
        val out = ArrayList<LatLng>()
        val legs = ((root["route"] as? Map<*, *>)?.get("legs") as? List<*>).orEmpty()
        for (leg in legs) for (step in ((leg as? Map<*, *>)?.get("steps") as? List<*>).orEmpty()) {
            val pts = ((step as? Map<*, *>)?.get("path") as? Map<*, *>)?.get("points") as? List<*> ?: continue
            for (pt in pts) {
                val xy = pt as? List<*> ?: continue
                val x = xy.getOrNull(0)?.toString()?.toDoubleOrNull() ?: continue
                val y = xy.getOrNull(1)?.toString()?.toDoubleOrNull() ?: continue
                val p = LatLng(y, x)
                if (out.lastOrNull() != p) out += p
            }
        }

        return status to out
    }
}

data class KakaoDriveRoute(
    val distanceMeters: Int,
    val durationSeconds: Int,
    val points: List<LatLng>,
)

object KakaoDrive {
    fun parse(body: String): Pair<String, KakaoDriveRoute?> {
        val root = runCatching { com.eunho.tagohub.core.MiniJson.parse(body) }.getOrNull() as? Map<*, *>
            ?: return "INVALID_RESPONSE" to null
        val route = (root["routes"] as? List<*>)?.firstOrNull() as? Map<*, *>
            ?: return "NO_ROUTE" to null
        val resultCode = route["result_code"]?.toString()?.toIntOrNull()
        if (resultCode != 0) {
            return (route["result_msg"]?.toString()?.takeIf { it.isNotBlank() } ?: "ROUTE_ERROR") to null
        }
        val summary = route["summary"] as? Map<*, *> ?: return "INVALID_RESPONSE" to null
        val distance = summary["distance"]?.toString()?.toDoubleOrNull()?.toInt()
            ?: return "INVALID_RESPONSE" to null
        val duration = summary["duration"]?.toString()?.toDoubleOrNull()?.toInt()
            ?: return "INVALID_RESPONSE" to null
        val points = ArrayList<LatLng>()
        val sections = route["sections"] as? List<*> ?: emptyList<Any?>()
        sections.forEach sectionLoop@{ section ->
            val roads = (section as? Map<*, *>)?.get("roads") as? List<*> ?: return@sectionLoop
            roads.forEach roadLoop@{ road ->
                val vertexes = (road as? Map<*, *>)?.get("vertexes") as? List<*> ?: return@roadLoop
                vertexes.chunked(2).forEach pointLoop@{ pair ->
                    val lng = pair.getOrNull(0)?.toString()?.toDoubleOrNull() ?: return@pointLoop
                    val lat = pair.getOrNull(1)?.toString()?.toDoubleOrNull() ?: return@pointLoop
                    if (lat in -90.0..90.0 && lng in -180.0..180.0) {
                        val point = LatLng(lat, lng)
                        if (points.lastOrNull() != point) points += point
                    }
                }
            }
        }
        if (points.size < 2) return "INVALID_RESPONSE" to null
        return "OK" to KakaoDriveRoute(distance, duration, points)
    }
}
