package com.eunho.tagohub.data

import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoTime
import kotlin.math.ceil

/**
 * 전주시 버스정보 API ↔ 앱 모델.
 * 전주 표준 ID(9자리)는 TAGO ID 앞에 "JUB" 를 붙인 것과 같다 (정류장·노선 모두).
 *   정류장 305032403 ↔ JUB305032403 (내장 정류장 DB 와 일치 확인)
 */
object Jeonju {
    const val PREFIX = "JUB"
    fun isJeonju(tagoId: String) = tagoId.startsWith(PREFIX)
    fun std(tagoId: String) = tagoId.removePrefix(PREFIX)
    fun tago(stdId: String) = PREFIX + stdId.trim()
}

/** 환승 경로검색 한 건 */
data class TransferOption(
    val startId: String,
    val startNo: String,
    val chgStopId: String,
    val chgName: String,
    val chgRemark: String?,
    val finId: String,
    val finNo: String,
    val rStop1: Int,
    val rStop2: Int,
    val startFromSeq: Int,
    val startToSeq: Int,
    val finFromSeq: Int,
    val finToSeq: Int,
)

object JeonjuMappers {

    /** 정류장 도착 예정 (RTime = 초, RStop = 남은 정류장 수, viaStopname = 버스가 지금 있는 정류장) */
    fun arrival(r: Row) = BusArrival(
        routeId = Jeonju.tago(r.text("brtStdid")),
        routeNo = r.text("brtId"),
        routeType = routeType(r.str("brtClass"), r.text("brtId")),
        vehicleType = if (r.str("busLow") == "1") "저상버스" else null,
        prevStops = r.int("RStop"),
        arrSeconds = r.int("RTime"),
        atStop = r.str("viaStopname"),
        towards = r.str("brtVianame")?.substringAfter("->", "")?.ifBlank { null },
    )

    /**
     * 정류장 경유노선 (busstop/general_busstop_common). 같은 번호라도 기점·종점이 다른 노선이 따로 온다
     * (예: 110 비전대→대성동종점 / 110 혁신전기안전공사→대성동종점).
     */
    /** 전주 노선 종류: 순환(brtClass 1) / 간선(4자리 번호, 예: 4000·6002) / 일반 */
    fun routeType(brtClass: String?, routeNo: String): String = when {
        brtClass == "1" -> "순환"
        Regex("^\\d{4}").containsMatchIn(routeNo.trim()) -> "간선"
        else -> "일반"
    }

    fun stopRoute(r: Row) = BusRoute(
        routeId = Jeonju.tago(r.text("brtStdId").ifEmpty { r.text("brtStdid") }),
        routeNo = r.text("brtId"),
        routeType = routeType(r.str("brtClass"), r.text("brtId")),
        startName = r.str("brtSname"),
        endName = r.str("brtEname"),
        firstTime = TagoTime.clockText(r.str("brtFirstTime", "brtFirsttime")),
        lastTime = TagoTime.clockText(r.str("brtLastTime", "brtLasttime")),
        interval = r.int("brtMininTerVal", "brtMininTerval", "brtMininterval"),
        intervalSat = null,
        intervalSun = r.int("brtMaxinTerVal", "brtMaxinTerval", "brtMaxinterval"),
        cityCode = "35010",
    )

    /** 노선 정보 (nosun/bus_location_busstop_info_common). 기점·종점은 "A->B" 형태라 앞뒤를 나눈다 */
    fun routeInfo(r: Row) = BusRoute(
        routeId = Jeonju.tago(r.text("brtStdid").ifEmpty { r.text("brtStdId") }),
        routeNo = r.text("brtId"),
        routeType = routeType(r.str("brtClass"), r.text("brtId")),
        startName = r.str("brtSname")?.substringBefore("->")?.trim(),
        endName = r.str("brtEname")?.substringAfter("->")?.trim(),
        firstTime = TagoTime.clockText(r.str("brtFirstTime")),
        lastTime = TagoTime.clockText(r.str("brtLastTime")),
        interval = r.int("brtMininTerval", "brtMininTerVal"),
        intervalSat = null,
        intervalSun = r.int("brtMaxinTerval", "brtMaxinTerVal"),
        cityCode = "35010",
    )

    /** 노선 위치 응답 = 노선 전체 정류장 목록 (순서대로). 좌표는 내장 정류장 DB 에서 */
    fun routeStops(rows: List<Row>, coord: (String) -> LatLng?): List<RouteStop> = rows.mapNotNull { r ->
        val std = r.str("stopStandardid", "bnodeId") ?: return@mapNotNull null
        val id = Jeonju.tago(std)
        val c = coord(id)
        RouteStop(
            nodeId = id,
            name = r.text("stopKname"),
            nodeNo = r.str("stopId"),
            ord = r.int("brsSeqno", "brnSeqno") ?: 0,
            upDown = null,
            lat = c?.lat,
            lng = c?.lng,
        )
    }.sortedBy { it.ord }

    /** busNo 가 채워진 행 = 그 정류장에 있는 버스 */
    fun buses(rows: List<Row>, coord: (String) -> LatLng?): List<BusLocation> = rows.mapNotNull { r ->
        val no = r.str("busNo")?.trim()?.ifBlank { null } ?: return@mapNotNull null
        val id = Jeonju.tago(r.str("stopStandardid", "bnodeId") ?: return@mapNotNull null)
        val c = coord(id)
        BusLocation(
            nodeId = id,
            nodeName = r.str("stopKname"),
            ord = r.int("brsSeqno", "brnSeqno"),
            vehicleNo = no,
            lat = c?.lat,
            lng = c?.lng,
            lowFloor = when (r.str("busLow")) {
                "1" -> true
                "0" -> false
                else -> null
            },
        )
    }

    /** 노선 모양 (vtxx = 경도, vtxy = 위도, 응답 순서대로) */
    fun drawing(rows: List<Row>): List<LatLng> = rows.mapNotNull { r ->
        val lat = r.double("vtxy") ?: return@mapNotNull null
        val lng = r.double("vtxx") ?: return@mapNotNull null
        LatLng(lat, lng)
    }

    /** 환승 검색 결과. 같은 행이 반복돼서 와서 중복 제거 */
    fun transfers(rows: List<Row>): List<TransferOption> = rows.mapNotNull { r ->
        TransferOption(
            startId = r.str("startId") ?: return@mapNotNull null,
            startNo = r.text("startNo"),
            chgStopId = r.str("chgBnodeId") ?: return@mapNotNull null,
            chgName = r.text("chgName"),
            chgRemark = r.str("reMark"),
            finId = r.str("finId") ?: return@mapNotNull null,
            finNo = r.text("finNo"),
            rStop1 = r.int("RStop1") ?: 0,
            rStop2 = r.int("RStop2") ?: 0,
            startFromSeq = r.int("startFromSeqNo") ?: 0,
            startToSeq = r.int("startToseqNo") ?: 0,
            finFromSeq = r.int("finFromseqNo") ?: 0,
            finToSeq = r.int("finToseqNo") ?: 0,
        )
    }.distinctBy { listOf(it.startId, it.chgStopId, it.finId, it.startFromSeq, it.finToSeq) }
}

// ───────────────────────── 길찾기 ─────────────────────────

/** 버스 한 구간: boardStop 에서 타서 alightStop 에서 내림 (fromSeq/toSeq = 노선 정류장 순번) */
data class TripLeg(
    val routeId: String,
    val routeNo: String,
    val boardStopId: String,
    val boardName: String,
    val alightStopId: String,
    val alightName: String,
    val fromSeq: Int,
    val toSeq: Int,
    val stops: Int,
    /** 노선을 따라 실제로 가는 거리(m). 0 이면 모름 → 정류장 수로 시간 계산 */
    val meters: Double = 0.0,
    /** 지하철 구간 (서울시 경로검색) */
    val subway: Boolean = false,
    /** 타는 곳·내리는 곳 좌표 (서울시 경로검색은 정류장 순번 대신 좌표를 줌) */
    val boardLat: Double? = null,
    val boardLng: Double? = null,
    val alightLat: Double? = null,
    val alightLng: Double? = null,
    /** 이 구간의 실제 경로 좌표 (카카오 길찾기) — 있으면 지도에 그대로 그림 */
    val path: List<LatLng>? = null,
    /** 노선 종류 (카카오: 간선·지선·광역·마을·시외·농어촌 …) */
    val vehicleType: String? = null,
    /** 같은 구간을 가는 다른 노선 (카카오: "76" 외 "76-1" 등) */
    val alternatives: List<String> = emptyList(),
)

/** 출발지 → (걷기) → 버스 1~2개 (환승 시 걸어서 갈아탈 수 있음) → (걷기) → 도착지 */
data class TripPlan(
    val origin: BusStop,
    val dest: BusStop,
    val walkStartM: Double,
    val walkEndM: Double,
    val legs: List<TripLeg>,
    val transferRemark: String? = null,
    /** 첫 버스가 출발 정류장에 오기까지 (실시간, 초). 모르면 null */
    val firstWaitSec: Int? = null,
    /** 환승할 때 내린 정류장에서 탈 정류장까지 걷는 거리 (같은 정류장이면 0) */
    val transferWalkM: Double = 0.0,
    /** 경로검색 API 가 준 소요시간(분). 있으면 이걸 쓴다 (서울시 경로검색) */
    val fixedMinutes: Int? = null,
    /** 경로를 찾아준 곳 (예: "서울시 경로검색"). 앱이 계산했으면 null */
    val source: String? = null,
    /** 요금(원) — 카카오 길찾기 */
    val fare: Int? = null,
    /** 걷는 구간의 실제 경로 (카카오 길찾기). 있으면 직선 대신 이걸 그림 */
    val walkPaths: List<List<LatLng>>? = null,
) {
    val stops: Int get() = legs.sumOf { it.stops }
    val rideMeters: Double get() = legs.sumOf { it.meters }
    val walkMinutes: Int get() = ceil((walkStartM + walkEndM + transferWalkM) / TripPlanner.WALK_M_PER_MIN).toInt()

    /** 예상 총 시간(분) = 걷기 + 버스(거리 기준, 모르면 정류장 수 기준) + 환승 + 첫 버스 대기 */
    val minutes: Int
        get() = fixedMinutes?.let { it + ((firstWaitSec ?: 0) + 59) / 60 } ?: walkMinutes +
            ceil(legs.sumOf { TripPlanner.rideMinutes(it) }).toInt() +
            (if (legs.size > 1) TripPlanner.TRANSFER_MIN else 0) +
            ((firstWaitSec ?: 0) + 59) / 60
}

object TripPlanner {
    const val WALK_M_PER_MIN = 72.0       // 1.2 m/s
    const val BUS_M_PER_MIN = 300.0       // 시내버스 평균 18km/h
    const val DWELL_MIN_PER_STOP = 0.3    // 정차 시간
    const val MIN_PER_STOP = 1.6          // 거리를 모를 때 정류장당
    const val TRANSFER_MIN = 4
    const val TRANSFER_WALK_MAX = 200.0   // 환승할 때 걸어서 옮길 수 있는 최대 거리
    const val MAX_LEG_STOPS = 70

    fun rideMinutes(l: TripLeg): Double =
        if (l.meters > 0) l.meters / BUS_M_PER_MIN + l.stops * DWELL_MIN_PER_STOP else l.stops * MIN_PER_STOP

    /** 노선에서 순번 fromOrd → toOrd 사이 예상 소요(분). 좌표가 없으면 정류장당 평균 */
    fun minutesBetween(line: Line?, fromOrd: Int, toOrd: Int): Double {
        if (line != null) {
            val i = line.stops.indexOfFirst { it.ord == fromOrd }
            val j = line.stops.indexOfFirst { it.ord == toOrd }
            if (i >= 0 && j >= i) return line.dist(i, j) / BUS_M_PER_MIN + (j - i) * DWELL_MIN_PER_STOP
        }
        return (toOrd - fromOrd).coerceAtLeast(0) * MIN_PER_STOP
    }

    fun meters(a: BusStop, b: BusStop): Double {
        val la = a.lat ?: return 0.0
        val lb = b.lat ?: return 0.0
        return com.eunho.tagohub.core.Geo.meters(la, a.lng ?: return 0.0, lb, b.lng ?: return 0.0)
    }

    /** 좌표가 있는 노선: 정류장 사이 누적 거리로 구간 거리를 바로 계산 */
    class Line(val routeId: String, val routeNo: String, stops: List<RouteStop>) {
        val stops: List<RouteStop> = stops.filter { it.lat != null && it.lng != null }
        private val cum = DoubleArray(this.stops.size).also { c ->
            for (i in 1 until this.stops.size) {
                val a = this.stops[i - 1]
                val b = this.stops[i]
                c[i] = c[i - 1] + com.eunho.tagohub.core.Geo.meters(a.lat!!, a.lng!!, b.lat!!, b.lng!!)
            }
        }
        fun dist(i: Int, j: Int) = cum[j] - cum[i]
        fun firstIndex(stopId: String) = stops.indexOfFirst { it.nodeId == stopId }
        fun leg(i: Int, j: Int) = TripLeg(
            routeId, routeNo,
            stops[i].nodeId, stops[i].name, stops[j].nodeId, stops[j].name,
            stops[i].ord, stops[j].ord, j - i, dist(i, j),
        )
    }

    /** 직통: 출발 정류장 뒤에 도착 정류장이 나오는 노선. 노선마다 가장 빠른 조합 하나 */
    fun directs(origins: List<Pair<BusStop, Double>>, dests: List<Pair<BusStop, Double>>, lines: List<Line>): List<TripPlan> =
        lines.mapNotNull { line ->
            var best: TripPlan? = null
            for ((o, ow) in origins) {
                val i = line.firstIndex(o.nodeId)
                if (i < 0) continue
                for ((d, dw) in dests) {
                    val j = (i + 1 until minOf(line.stops.size, i + MAX_LEG_STOPS)).firstOrNull { line.stops[it].nodeId == d.nodeId } ?: continue
                    val plan = TripPlan(o, d, ow, dw, listOf(line.leg(i, j)))
                    if (best == null || plan.minutes < best.minutes) best = plan
                }
            }
            best
        }

    /** 이전 버전 호환 (정류장 목록만 있을 때) */
    fun directs(
        origins: List<Pair<BusStop, Double>>,
        dests: List<Pair<BusStop, Double>>,
        routes: Map<String, Pair<String, List<RouteStop>>>,
    ): List<TripPlan> {
        val out = ArrayList<TripPlan>()
        for ((routeId, pair) in routes) {
            val (routeNo, stops) = pair
            var best: TripPlan? = null
            for ((o, ow) in origins) {
                val i = stops.indexOfFirst { it.nodeId == o.nodeId }
                if (i < 0) continue
                for ((d, dw) in dests) {
                    val j = (i + 1 until stops.size).firstOrNull { stops[it].nodeId == d.nodeId } ?: continue
                    val plan = TripPlan(
                        o, d, ow, dw,
                        listOf(TripLeg(routeId, routeNo, o.nodeId, o.name, d.nodeId, d.name, stops[i].ord, stops[j].ord, j - i)),
                    )
                    if (best == null || plan.minutes < best.minutes) best = plan
                }
            }
            best?.let { out += it }
        }
        return out
    }

    /**
     * 가장 가까운 환승: 출발 쪽 노선(A)의 하류 정류장과 도착 쪽 노선(B)의 상류 정류장이
     * 같은 정류장이거나 200m 안에서 만나는 지점을 모두 보고, 노선 조합마다 가장 빠른 것 하나.
     * 격자로 근처 정류장만 비교해서 빠르다.
     */
    fun nearestTransfers(
        origins: List<Pair<BusStop, Double>>,
        dests: List<Pair<BusStop, Double>>,
        fromLines: List<Line>,
        toLines: List<Line>,
    ): List<TripPlan> {
        class Up(val line: Line, val m: Int, val j: Int, val dest: BusStop, val dw: Double)
        val cell = 0.002
        fun key(lat: Double, lng: Double) = Pair(Math.floor(lat / cell).toInt(), Math.floor(lng / (cell * 1.25)).toInt())
        val grid = HashMap<Pair<Int, Int>, MutableList<Up>>()
        for (b in toLines) for ((d, dw) in dests) {
            val j = b.firstIndex(d.nodeId)
            if (j <= 0) continue
            for (m in maxOf(0, j - MAX_LEG_STOPS) until j) {
                val t = b.stops[m]
                grid.getOrPut(key(t.lat!!, t.lng!!)) { mutableListOf() } += Up(b, m, j, d, dw)
            }
        }
        val best = HashMap<String, TripPlan>()
        for (a in fromLines) for ((o, ow) in origins) {
            val i = a.firstIndex(o.nodeId)
            if (i < 0) continue
            for (k in i + 1 until minOf(a.stops.size, i + MAX_LEG_STOPS)) {
                val s = a.stops[k]
                val (cy, cx) = key(s.lat!!, s.lng!!)
                for (dy in -1..1) for (dx in -1..1) {
                    val ups = grid[Pair(cy + dy, cx + dx)] ?: continue
                    for (u in ups) {
                        if (u.line.routeId == a.routeId) continue
                        val t = u.line.stops[u.m]
                        val walk = if (t.nodeId == s.nodeId) 0.0 else com.eunho.tagohub.core.Geo.meters(s.lat, s.lng!!, t.lat!!, t.lng!!)
                        if (walk > TRANSFER_WALK_MAX) continue
                        val plan = TripPlan(
                            o, u.dest, ow, u.dw,
                            listOf(a.leg(i, k), u.line.leg(u.m, u.j)),
                            transferWalkM = walk,
                        )
                        val id = a.routeId + ">" + u.line.routeId
                        val cur = best[id]
                        if (cur == null || plan.minutes < cur.minutes) best[id] = plan
                    }
                }
            }
        }
        return best.values.toList()
    }

    /** 전주시 환승 경로검색 결과 → 경로 (노선 좌표가 있으면 실제 거리로 다시 계산) */
    fun fromTransfers(
        origin: BusStop, dest: BusStop, walkStartM: Double, walkEndM: Double,
        options: List<TransferOption>,
        lines: Map<String, Line> = emptyMap(),
        stopName: (String) -> String?,
    ): List<TripPlan> = options.map { t ->
        val chg = Jeonju.tago(t.chgStopId)
        val chgName = t.chgName.ifBlank { stopName(chg).orEmpty() }
        fun meters(routeId: String, fromSeq: Int, toSeq: Int): Double {
            val line = lines[routeId] ?: return 0.0
            val i = line.stops.indexOfFirst { it.ord == fromSeq }
            val j = line.stops.indexOfFirst { it.ord == toSeq }
            return if (i >= 0 && j > i) line.dist(i, j) else 0.0
        }
        val a = Jeonju.tago(t.startId)
        val b = Jeonju.tago(t.finId)
        TripPlan(
            origin = origin, dest = dest, walkStartM = walkStartM, walkEndM = walkEndM,
            legs = listOf(
                TripLeg(a, t.startNo, origin.nodeId, origin.name, chg, chgName, t.startFromSeq, t.startToSeq, t.rStop1, meters(a, t.startFromSeq, t.startToSeq)),
                TripLeg(b, t.finNo, chg, chgName, dest.nodeId, dest.name, t.finFromSeq, t.finToSeq, t.rStop2, meters(b, t.finFromSeq, t.finToSeq)),
            ),
            transferRemark = t.chgRemark,
        )
    }

    /**
     * 정리: 같은 버스 조합은 가장 빠른 것만 → 크게 돌아가는 경로 제외(괜찮은 대안이 있을 때) → 예상시간 순.
     * 돌아감 = 버스로 가는 거리 ÷ 출발·도착 정류장 직선거리.
     */
    fun rank(plans: List<TripPlan>, n: Int = 10): List<TripPlan> {
        val unique = plans.groupBy { p -> p.legs.joinToString(">") { it.routeId } }
            .values.map { same -> same.minBy { it.minutes } }
        fun detour(p: TripPlan): Double {
            val straight = maxOf(meters(p.origin, p.dest), 400.0)
            val ride = if (p.rideMeters > 0) p.rideMeters else p.stops * 400.0
            return ride / straight
        }
        val reasonable = unique.filter { detour(it) <= 2.5 }
        val pool = if (reasonable.size >= 3) reasonable else unique.sortedBy { detour(it) }.take(maxOf(reasonable.size, n))
        return pool
            .sortedWith(compareBy({ it.minutes + (it.legs.size - 1) * 2 }, { it.legs.size }, { it.walkStartM + it.walkEndM + it.transferWalkM }))
            .take(n)
    }
}
