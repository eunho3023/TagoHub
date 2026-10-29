package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoTime
import java.time.LocalDateTime

// ───────────────────────── 모델 ─────────────────────────

/**
 * 표준데이터에서 확인된 좌표 오류. (노선, 역 코드) → (위도, 경도).
 * 6호선 연신내(2615)는 경도가 127.0914(신내 근처)로 잘못 적혀 있어 3호선 연신내(126.9209)와 14km 떨어진 다른 역으로 보였음.
 * 자료를 다시 만들어도 유지되게 앱에서 고친다.
 */
object StationDataFixes {
    private val fixes = mapOf(("6호선" to "2615") to (37.6190 to 126.9212))

    fun apply(s: MetroStation): MetroStation = fixes[s.line to s.code]?.let { (la, lo) -> s.copy(lat = la, lng = lo) } ?: s
}

/** 전국도시철도역사정보표준데이터 한 행 */
data class MetroStation(
    val code: String,
    val name: String,
    val line: String,
    val lat: Double,
    val lng: Double,
    val operator: String,
) {
    val norm: String = StationName.norm(name)
    val seoulLine: SeoulLine?
        get() = SeoulLines.byDataLine[line]
            ?.takeIf { regionalCity(operator) == null }

    /** 화면 표시용 노선명: 서울 실시간 노선이면 "1호선"처럼, 아니면 원래 노선명 */
    val displayLine: String
        get() {
            val mappedLine = SeoulLines.byDataLine[line]?.apiName ?: line
            val city = regionalCity(operator) ?: return mappedLine
            return if (city in mappedLine) mappedLine else "$city $mappedLine"
        }

    private fun regionalCity(operator: String): String? = when {
        operator.contains("대전") -> "대전"
        operator.contains("부산") -> "부산"
        operator.contains("대구") -> "대구"
        operator.contains("광주") -> "광주"
        else -> null
    }
}

/** 환승역처럼 같은 이름·가까운 위치의 역을 지도 마커 하나로 묶은 것 */
data class StationGroup(
    val id: String,
    val name: String,
    val lat: Double,
    val lng: Double,
    val stations: List<MetroStation>,
) {
    val seoulLineIds: Set<String> get() = stations.mapNotNull { it.seoulLine?.id }.toSet()
    val displayLines: List<String> get() = stations.map { it.displayLine }.distinct()

    /**
     * 이 묶음 안에서 [lineName] 노선의 역. 노선을 모르면 [near] 에서 가장 가까운 역, 그것도 없으면 첫 역.
     * 환승역은 노선마다 역 위치가 달라서(당산 2·9호선 약 180m) 지도 핀은 평균 좌표가 아니라 이 역의 좌표에 꽂는다.
     */
    fun stationOn(lineName: String?, near: Pair<Double, Double>? = null): MetroStation? =
        lineName?.let { w -> stations.firstOrNull { StationName.sameLine(it.displayLine, w) || StationName.sameLine(it.line, w) } }
            ?: near?.let { t -> stations.minByOrNull { Geo.meters(t.first, t.second, it.lat, it.lng) } }
            ?: stations.firstOrNull()

    /**
     * 이 역에서 [lat],[lng] 까지의 거리(m): 노선별 역 중 가장 가까운 곳 기준.
     * 환승역은 [lat]·[lng] 가 노선별 역의 평균이라(당산 2호선·9호선처럼 100m 넘게 떨어진 경우) 중심점으로 재면 실제 역 앞에서도 멀게 나온다.
     */
    fun distanceTo(lat: Double, lng: Double): Double =
        stations.minOfOrNull { Geo.meters(lat, lng, it.lat, it.lng) } ?: Geo.meters(lat, lng, this.lat, this.lng)
}

/** 서울 realtimePosition 한 행 */
data class SubwayTrain(
    val lineId: String,
    val trainNo: String,
    val stationId: String,
    val stationName: String,
    val destName: String?,
    /** 0 상행(2호선 내선), 1 하행(2호선 외선) */
    val upDown: Int,
    /** 0 진입, 1 도착, 2 출발, 3 전역출발 */
    val status: Int,
    val express: Boolean,
    val last: Boolean,
    val received: LocalDateTime?,
)

/** 서울 realtimeStationArrival 한 행 */
data class SubwayArrival(
    val lineId: String,
    val upDown: String,
    val lineText: String,
    val seconds: Int?,
    val message: String,
    val current: String?,
    val code: Int?,
    val dest: String?,
    val express: Boolean,
    val last: Boolean,
    val order: String?,
    val received: LocalDateTime?,
    /** 열차번호 (실시간 위치의 trainNo 와 매칭) */
    val trainNo: String? = null,
)

// ───────────────────────── 이름·노선 매핑 ─────────────────────────

object StationName {
    private val paren = Regex("""\(.*?\)""")

    private fun lineKey(s: String): String =
        s.replace(" ", "")
            .replace("수도권", "")
            .replace("도시철도", "")
            .replace("광역철도", "")
            .replace("지하철", "")
            .replace("인천국제공항", "공항")
            .replace(Regex("^(서울|부산|대구|대전|광주|인천)"), "")
            .removeSuffix("선")

    /**
     * TAGO 지하철 노선명 ↔ 표준데이터/서울 노선명 비교.
     * TAGO "경의중앙" ↔ "경의중앙선", "공항" ↔ "공항철도", "1호선" ↔ "1호선"
     */
    fun sameLine(a: String, b: String): Boolean {
        val x = lineKey(a)
        val y = lineKey(b)
        if (x.isEmpty() || y.isEmpty()) return false
        return x == y || x.contains(y) || y.contains(x)
    }

    /** "청량리(서울시립대입구)" → "청량리", "서울역" → "서울", "서울역(경의선)" → "서울" */
    fun norm(s: String): String {
        val t = s.replace(paren, "").replace(" ", "").trim()
        return if (t.length >= 3 && t.endsWith("역")) t.dropLast(1) else t
    }
}

/** 서울 실시간 API 노선 ↔ 표준데이터 노선명 */
data class SeoulLine(val id: String, val apiName: String, val dataLines: List<String>)

object SeoulLines {
    val all = listOf(
        SeoulLine("1001", "1호선", listOf("1호선", "경부선", "경원선", "경인선", "장항선")),
        SeoulLine("1002", "2호선", listOf("2호선")),
        SeoulLine("1003", "3호선", listOf("3호선", "일산선")),
        SeoulLine("1004", "4호선", listOf("4호선", "안산과천선", "진접선")),
        SeoulLine("1005", "5호선", listOf("5호선")),
        SeoulLine("1006", "6호선", listOf("6호선")),
        SeoulLine("1007", "7호선", listOf("7호선", "도시철도 7호선")),
        SeoulLine("1008", "8호선", listOf("8호선", "수도권 광역철도 8호선")),
        SeoulLine("1009", "9호선", listOf("9호선", "서울 도시철도 9호선", "수도권 도시철도 9호선")),
        SeoulLine("1063", "경의중앙선", listOf("경의중앙선")),
        SeoulLine("1065", "공항철도", listOf("인천국제공항선", "공항철도선")),
        SeoulLine("1067", "경춘선", listOf("경춘선")),
        SeoulLine("1075", "수인분당선", listOf("분당선", "수인선", "수인분당선")),
        SeoulLine("1077", "신분당선", listOf("신분당선")),
        SeoulLine("1081", "경강선", listOf("경강선")),
        SeoulLine("1092", "우이신설선", listOf("우이신설선")),
        SeoulLine("1093", "서해선", listOf("서해선")),
        SeoulLine("1094", "신림선", listOf("신림선", "수도권 경량도시철도 신림선")),
        SeoulLine("1032", "GTX-A", listOf("GTX-A", "수도권 광역급행철도")),
    )
    val byId: Map<String, SeoulLine> = all.associateBy { it.id }
    val byDataLine: Map<String, SeoulLine> = all.flatMap { l -> l.dataLines.map { it to l } }.toMap()
}

// ───────────────────────── 역 색인 ─────────────────────────

class StationIndex(rawStations: List<MetroStation>) {
    /** 표준데이터의 알려진 좌표 오류를 고친 역 목록 */
    val stations: List<MetroStation> = rawStations.map(StationDataFixes::apply)


    /** (서울 노선ID, 정규화 역명) → 역. 같은 역이 두 번 실린 경우(용산 경부선/경원선) 첫 행 사용 */
    private val bySeoulKey: Map<Pair<String, String>, MetroStation> = buildMap {
        stations.forEach { s -> s.seoulLine?.let { l -> putIfAbsent(l.id to s.norm, s) } }
    }

    val groups: List<StationGroup> = buildGroups()
    private val groupById = groups.associateBy { it.id }

    fun group(id: String): StationGroup? = groupById[id]

    /** (표준데이터 노선명, 정규화 역명) → 역 */
    private val byDataLine: Map<Pair<String, String>, MetroStation> = buildMap {
        stations.forEach { s -> putIfAbsent(s.line to s.norm, s) }
    }

    /**
     * 실시간 API 의 (subwayId, statnNm) 으로 역 좌표 찾기.
     * 1) 그대로  2) 역명 변경·표기 차이 (실시간도착_역정보 20260902 696역 전수 검사로 찾은 것)
     * 3) 표준데이터에 그 노선 행이 없는 공용 역은 같은 역의 다른 노선 행 (2호선 까치산 → 5호선 까치산 등)
     */
    fun locate(lineId: String, stationName: String): MetroStation? {
        val n = StationName.norm(stationName)
        bySeoulKey[lineId to n]?.let { return it }
        val alias = LINE_ALIASES[lineId to n] ?: NAME_ALIASES[n]
        if (alias != null) bySeoulKey[lineId to alias]?.let { return it }
        val name = alias ?: n
        return SHARED_LINES[lineId].orEmpty().firstNotNullOfOrNull { byDataLine[it to name] }
    }

    private val bySeoulLine: Map<String, List<MetroStation>> =
        stations.filter { it.seoulLine != null }.groupBy { it.seoulLine!!.id }

    /**
     * 서울 API 는 다음 역을 주지 않아서 행선지로 추정한다.
     * 다음 역 = 같은 노선에서 현재 역보다 행선지에 가까운 역 중 현재 역과 가장 가까운 역 (6km 이내)
     * 이전 역 = 행선지에서 더 먼 역 중 현재 역과 가장 가까운 역
     */
    fun neighbors(lineId: String, cur: MetroStation, dest: MetroStation?): Pair<MetroStation?, MetroStation?> {
        if (dest == null || dest.norm == cur.norm) return null to null
        val same = bySeoulLine[lineId].orEmpty().filter { it.norm != cur.norm }
        fun d(a: MetroStation, b: MetroStation) = Geo.meters(a.lat, a.lng, b.lat, b.lng)
        val toDest = d(cur, dest)
        val next = same.filter { d(it, dest) < toDest - 50 }.minByOrNull { d(cur, it) }?.takeIf { d(cur, it) < 6000 }
        val prev = same.filter { d(it, dest) > toDest + 50 }.minByOrNull { d(cur, it) }?.takeIf { d(cur, it) < 6000 }
        return prev to next
    }

    /** 행선지까지 거쳐 가는 역 순서 (현재 역 포함, 행선지 포함). 다음 역 추정을 반복 */
    fun pathToward(lineId: String, from: MetroStation, dest: MetroStation?, maxSteps: Int = 80): List<MetroStation> {
        val out = arrayListOf(from)
        if (dest == null) return out
        var cur = from
        repeat(maxSteps) {
            if (cur.norm == dest.norm) return out
            val next = neighbors(lineId, cur, dest).second ?: return out.also { if (it.last().norm != dest.norm) it += dest }
            out += next
            cur = next
        }
        return out
    }

    /** 열차가 지나온 역 (기점이 맨 앞, 바로 전 역이 맨 뒤. [from] 은 제외). 이전 역 추정을 반복 */
    fun pathBehind(lineId: String, from: MetroStation, dest: MetroStation?, maxSteps: Int = 80): List<MetroStation> {
        if (dest == null) return emptyList()
        val out = ArrayDeque<MetroStation>()
        val seen = hashSetOf(from.norm)
        var cur = from
        repeat(maxSteps) {
            val prev = neighbors(lineId, cur, dest).first ?: return out.toList()
            if (!seen.add(prev.norm)) return out.toList()   // 순환선에서 한 바퀴 돌아오면 멈춤
            out.addFirst(prev)
            cur = prev
        }
        return out.toList()
    }

    private val groupOfStation: Map<MetroStation, String> by lazy {
        groups.flatMap { g -> g.stations.map { it to g.id } }.toMap()
    }

    /** 역 → 지도 마커(묶음) id */
    fun groupIdOf(s: MetroStation): String? = groupOfStation[s]

    /** 화면 범위 안에 역이 하나라도 있는 서울 실시간 노선 */
    fun seoulLinesIn(south: Double, west: Double, north: Double, east: Double): Set<String> =
        stations.asSequence()
            .filter { it.lat in south..north && it.lng in west..east }
            .mapNotNull { it.seoulLine?.id }
            .toSet()

    /** 지도 POI 는 역 출입구 좌표일 수 있어 역 중심점과 차이가 난다. 이름이 맞는 가까운 역만 연결한다. */
    fun matchPoi(name: String, lat: Double, lng: Double, maxDistanceM: Double = 1_000.0): StationGroup? =
        groups.asSequence()
            .map { group -> group to group.distanceTo(lat, lng) }
            .filter { (_, distance) -> distance <= maxDistanceM }
            .filter { (group, _) -> PoiMatch.similar(name, group.name) }
            .minByOrNull { (_, distance) -> distance }
            ?.first

    /** 노선 띠: 이전 역 · 이 역 · 다음 역 */
    data class Around(val prev: String?, val cur: String, val next: String?)

    /**
     * KRIC 노선 목록([all], 같은 운영기관·노선)에서 [curCode] 역의 이전·다음 역.
     *
     * 목록의 순서 번호는 지선이 본선과 같은 번호를 나눠 써서(경의중앙선: 서울역·신촌 지선이 수색·디지털미디어시티와 21·22번,
     * 1호선: 가산디지털단지·독산·금천구청 지선이 구일·개봉·오류동과 46~48번) 번호순으로 줄 세우면 엉뚱한 역이 이웃이 된다.
     * 역 코드 앞 글자(K·P·X…)가 같은 역끼리 한 가지(줄기)로 보고, 이웃은 같은 가지 안에서만 찾는다.
     * 가장 큰 가지를 본선으로 하고, 지선의 첫 역 앞은 본선에서 가장 가까운 역(갈라지는 곳)이다.
     * 이웃이 [NEIGHBOR_MAX_GAP_M] 보다 멀리 있으면 이어지지 않는 구간(GTX-A 서울역↔구성처럼 따로 운영되는 구간)이라 비운다.
     * 지선의 끝 역은 이웃을 다음 역 쪽에 둔다(처음 역처럼). 마지막에 [completeNeighbor] 로 다른 운영기관 구간의 빠진 이웃을 채운다.
     */
    fun neighborsOf(group: StationGroup, lineName: String, all: List<KricRouteStation>, curCode: String?): Around? {
        val cur = all.firstOrNull { it.stationCode == curCode } ?: return null
        fun key(r: KricRouteStation) = r.stationCode.takeWhile { !it.isDigit() }
        val branches = all.groupBy(::key)
        val mainKey = branches.maxByOrNull { it.value.size }!!.key
        val curSt = group.stations.firstOrNull { StationName.sameLine(it.line, lineName) || StationName.sameLine(it.displayLine, lineName) }
        fun pos(r: KricRouteStation): MetroStation? = curSt?.let { c ->
            stations.asSequence()
                .filter { it.norm == StationName.norm(r.stationName) && (StationName.sameLine(it.line, lineName) || StationName.sameLine(it.displayLine, lineName)) }
                .filter { Geo.meters(c.lat, c.lng, it.lat, it.lng) <= NEIGHBOR_LOOKUP_M }
                .minByOrNull { Geo.meters(c.lat, c.lng, it.lat, it.lng) }
        }
        fun meters(a: KricRouteStation, b: KricRouteStation): Double? {
            val pa = pos(a) ?: return null
            val pb = pos(b) ?: return null
            return Geo.meters(pa.lat, pa.lng, pb.lat, pb.lng)
        }
        /** 같은 번호의 후보가 여럿이면(같은 가지 안에서는 드묾) 현재 역에서 가까운 쪽 */
        fun nearestTo(list: List<KricRouteStation>, from: KricRouteStation): KricRouteStation? =
            list.minByOrNull { meters(from, it) ?: Double.MAX_VALUE }

        val myKey = key(cur)
        val mine = branches[myKey].orEmpty().sortedBy { it.stationOrder }
        var prev: KricRouteStation?
        var next: KricRouteStation?
        if (myKey == mainKey) {
            val lowOrder = mine.filter { it.stationOrder < cur.stationOrder }.maxOfOrNull { it.stationOrder }
            val upOrder = mine.filter { it.stationOrder > cur.stationOrder }.minOfOrNull { it.stationOrder }
            prev = nearestTo(mine.filter { it.stationOrder == lowOrder }, cur)
            next = nearestTo(mine.filter { it.stationOrder == upOrder }, cur)
        } else {
            // 지선: 가지 안 순서대로, 첫 역의 앞은 본선에서 가장 가까운 역
            val i = mine.indexOf(cur)
            val main = branches[mainKey].orEmpty()
            // 갈라지는 곳: 지선 번호 바로 아래·위의 본선 역 중 지선 첫 역에 가까운 쪽 (경의선 신촌 지선은 가좌, 1호선 가산디지털단지 지선은 구로)
            val below = main.filter { it.stationOrder < mine.first().stationOrder }.maxOfOrNull { it.stationOrder }
            val above = main.filter { it.stationOrder > mine.last().stationOrder }.minOfOrNull { it.stationOrder }
            val junction = nearestTo(main.filter { it.stationOrder == below || it.stationOrder == above }, mine.first())
            prev = mine.getOrNull(i - 1) ?: junction
            next = mine.getOrNull(i + 1)
        }
        if (prev != null && (meters(cur, prev) ?: 0.0) > NEIGHBOR_MAX_GAP_M) prev = null
        if (next != null && (meters(cur, next) ?: 0.0) > NEIGHBOR_MAX_GAP_M) next = null
        // 지선의 맨 끝 역(경의선 서울역)은 줄기의 처음 역처럼 보이게 이웃(신촌)을 다음 역 쪽에 둠
        if (myKey != mainKey && prev != null && next == null) { next = prev; prev = null }
        val listed = all.map { StationName.norm(it.stationName) }.toSet()
        val (p, n) = completeNeighbor(group, lineName, prev?.stationName, next?.stationName, listed)
        return Around(p, cur.stationName, n)
    }

    /**
     * 노선 띠의 이전·다음 역 중 한쪽이 비었을 때(KRIC 노선 목록이 운영 구간에서 끊기는 역: 서울교통공사 1호선의 서울역은 코레일 쪽 목록이 없어 다음 역이 비었음)
     * 표준데이터의 같은 노선 역 위치로 빠진 쪽 이웃을 채운다: 알려진 이웃의 반대 방향에서 가장 가까운 같은 노선 역.
     * [listed] 에 든 역(KRIC 목록에 이미 있는 같은 운영기관 역)은 후보에서 뺀다 — 목록에 있는데 이웃이 아니라면 진짜 이웃이 아님(경의중앙선 서울역 ↔ 용산).
     * 그쪽에 역이 없으면(진짜 종점) 비운 채로 둔다. 둘 다 비었거나 둘 다 있으면 그대로.
     */
    fun completeNeighbor(group: StationGroup, lineName: String, prev: String?, next: String?, listed: Set<String> = emptySet()): Pair<String?, String?> {
        if ((prev == null) == (next == null)) return prev to next
        val cur = group.stations.firstOrNull { StationName.sameLine(it.line, lineName) || StationName.sameLine(it.displayLine, lineName) } ?: return prev to next
        // 노선 이름 비교는 도시 접두사("인천1호선"·"부산1호선")를 떼서 다른 도시의 같은 호선이 섞이므로 가까운 역만 봄
        val onLine = stations.filter {
            (StationName.sameLine(it.line, lineName) || StationName.sameLine(it.displayLine, lineName)) &&
                Geo.meters(cur.lat, cur.lng, it.lat, it.lng) <= NEIGHBOR_RANGE_M
        }
        val knownName = StationName.norm((prev ?: next)!!)
        val known = onLine.filter { it.norm == knownName }.minByOrNull { Geo.meters(cur.lat, cur.lng, it.lat, it.lng) } ?: return prev to next
        val kx = 111_000.0 * Math.cos(Math.toRadians(cur.lat))
        val vx = (cur.lng - known.lng) * kx
        val vy = (cur.lat - known.lat) * 111_000.0
        val far = onLine.asSequence()
            .filter { it.norm != cur.norm && it.norm != known.norm && it.norm !in listed }
            .filter { c -> (c.lng - cur.lng) * kx * vx + (c.lat - cur.lat) * 111_000.0 * vy > 0 }   // 알려진 이웃의 반대쪽
            .map { it to Geo.meters(cur.lat, cur.lng, it.lat, it.lng) }
            .filter { it.second <= 5_000.0 }
            .minByOrNull { it.second }?.first ?: return prev to next
        val label = far.name.substringBefore('(').trim()
        return if (prev == null) label to next else prev to label
    }

    /** 이름 없이 역 아이콘만 눌렀을 때: [maxDistanceM] 안에서 가장 가까운 역 */
    fun nearestGroup(lat: Double, lng: Double, maxDistanceM: Double = 80.0): StationGroup? =
        groups.asSequence()
            .map { group -> group to group.distanceTo(lat, lng) }
            .filter { (_, distance) -> distance <= maxDistanceM }
            .minByOrNull { (_, distance) -> distance }
            ?.first

    private fun buildGroups(): List<StationGroup> {
        val buckets = ArrayList<MutableList<MetroStation>>()
        stations.groupBy { it.norm }.values.forEach { same ->
            val local = ArrayList<MutableList<MetroStation>>()
            same.forEach { s ->
                val hit = local.firstOrNull { g -> g.any { Geo.meters(it.lat, it.lng, s.lat, s.lng) < SAME_STATION_M } }
                if (hit != null) hit += s else local += mutableListOf(s)
            }
            buckets += local
        }
        return buckets.mapIndexed { i, list ->
            StationGroup(
                id = "g$i",
                name = list.minByOrNull { it.name.length }!!.name.let(StationName::norm),
                lat = list.map { it.lat }.average(),
                lng = list.map { it.lng }.average(),
                stations = list,
            )
        }
    }

    companion object {
        /** 노선 띠의 이웃 역을 찾을 때 같은 이름·노선의 역을 이 거리(m) 안에서만 봄 (다른 도시의 같은 호선 이름 제외) */
        private const val NEIGHBOR_RANGE_M = 25_000.0

        /** 이웃 사이 거리를 잴 때 같은 이름의 역을 찾는 범위(m): 서로 이어지지 않는 먼 구간(GTX-A 서울역↔구성)도 거리를 알아야 걸러짐 */
        private const val NEIGHBOR_LOOKUP_M = 80_000.0

        /** 이웃 역이 이보다 멀면 이어지지 않는 구간으로 봄 (GTX-A 서울역↔구성 약 22km, 노선 안 인접 역 간격은 최대 약 11km) */
        private const val NEIGHBOR_MAX_GAP_M = 14_000.0

        /**
         * 같은 이름의 역을 한 역으로 묶는 거리(m). 표준데이터의 일부 노선(6호선 등) 좌표가 환승역에서 800~1,100m 어긋나 있어서
         * (약수·신당·합정·삼각지 …) 500m 로는 환승역이 둘로 갈라져 한 노선만 보였음. 다른 도시의 같은 이름 역은 수 km 이상 떨어져 있어 안전.
         */
        const val SAME_STATION_M = 1300.0

        /** 실시간 API 역명 → 표준데이터 역명 (정규화 후) */
        private val NAME_ALIASES = mapOf(
            "지제" to "평택지제",
            "신길온천" to "능길",          // 2025 역명 변경
            "뚝섬유원지" to "자양",        // 자양(뚝섬한강공원)
            "응암순환" to "응암",          // 6호선 응암순환(상선)
            "세종왕릉" to "세종대왕릉",
            "운정중앙" to "운정",          // GTX-A
        )

        /** 노선별 역명 차이 */
        private val LINE_ALIASES = mapOf(
            ("1007" to "총신대입구") to "이수", // 7호선은 '이수'
        )

        /** 표준데이터에 그 노선 행이 없는 공용 역: 같은 역의 다른 노선 행으로 */
        private val SHARED_LINES = mapOf(
            "1002" to listOf("5호선"),                                   // 까치산
            "1063" to listOf("1호선"),                                   // 서울역
            "1067" to listOf("경원선", "경의중앙선"),                      // 청량리·회기
            "1075" to listOf("경원선", "경의중앙선", "수인선", "안산과천선"), // 청량리·능길
        )

        fun parseTsv(text: String): StationIndex {
            val rows = text.lineSequence().drop(1).filter { it.isNotBlank() }.mapNotNull { line ->
                val c = line.split('\t')
                if (c.size < 6) return@mapNotNull null
                val lat = c[3].toDoubleOrNull() ?: return@mapNotNull null
                val lng = c[4].toDoubleOrNull() ?: return@mapNotNull null
                MetroStation(c[0], c[1], c[2], lat, lng, c[5])
            }.toList()
            return StationIndex(rows)
        }
    }
}

// ───────────────────────── 서울 응답 매핑 ─────────────────────────

object SeoulMappers {
    fun train(r: Row) = SubwayTrain(
        lineId = r.text("subwayId"),
        trainNo = r.text("trainNo"),
        stationId = r.text("statnId"),
        stationName = r.text("statnNm"),
        destName = r.str("statnTnm"),
        upDown = r.int("updnLine") ?: 0,
        status = r.int("trainSttus") ?: 0,
        express = (r.str("directAt") ?: "0") != "0",
        last = r.str("lstcarAt") == "1",
        received = TagoTime.dateTime(r.str("recptnDt")),
    )

    fun arrival(r: Row) = SubwayArrival(
        lineId = r.text("subwayId"),
        upDown = r.text("updnLine"),
        lineText = r.text("trainLineNm"),
        seconds = r.int("barvlDt"),
        message = r.text("arvlMsg2"),
        current = r.str("arvlMsg3"),
        code = r.int("arvlCd"),
        dest = r.str("bstatnNm"),
        express = r.str("btrainSttus")?.let { it != "일반" } ?: false,
        last = r.str("lstcarAt") == "1",
        order = r.str("ordkey"),
        received = TagoTime.dateTime(r.str("recptnDt")),
        trainNo = r.str("btrainNo"),
    )

    fun statusText(status: Int): String = when (status) {
        0 -> "진입"
        1 -> "도착"
        2 -> "출발"
        3 -> "전역출발"
        else -> ""
    }
}

/** 서울 지하철 도착 문구 정리 (도착 예정 초가 0으로 올 때가 많아 메시지로 보여줌) */
object SubwayText {
    /**
     * "[4]번째 전역 (효창공원앞)" → "4번째 전역", "서울 도착" → "도착", "서울 진입" → "진입", "서울 출발" → "출발",
     * "12분 후 (광명사거리)" → "12분 후", "전역 출발" 그대로
     */
    fun main(a: SubwayArrival): String {
        val m = a.message.trim()
        Regex("\\[(\\d+)]번째 전역").find(m)?.let { return "${it.groupValues[1]}번째 전역" }
        Regex("(\\d+)분\\s*후").find(m)?.let { return "${it.groupValues[1]}분 후" }
        when (a.code) {
            0 -> return "진입"
            1 -> return "도착"
            2 -> return "출발"
            3 -> return "전역 출발"
            4 -> return "전역 진입"
            5 -> return "전역 도착"
        }
        return m.ifBlank { "정보 없음" }
    }
}
