package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoTime
import java.time.LocalDateTime

// ───────────────────────── 모델 ─────────────────────────

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
    val seoulLine: SeoulLine? get() = SeoulLines.byDataLine[line]
    /** 화면 표시용 노선명: 서울 실시간 노선이면 "1호선"처럼, 아니면 원래 노선명 */
    val displayLine: String get() = seoulLine?.apiName ?: line
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
        s.replace(" ", "").replace("수도권", "").replace("도시철도", "").replace("광역철도", "").removeSuffix("선")

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
        SeoulLine("1009", "9호선", listOf("서울 도시철도 9호선", "수도권 도시철도 9호선")),
        SeoulLine("1063", "경의중앙선", listOf("경의중앙선")),
        SeoulLine("1065", "공항철도", listOf("인천국제공항선")),
        SeoulLine("1067", "경춘선", listOf("경춘선")),
        SeoulLine("1075", "수인분당선", listOf("분당선", "수인선")),
        SeoulLine("1077", "신분당선", listOf("신분당선")),
        SeoulLine("1081", "경강선", listOf("경강선")),
        SeoulLine("1092", "우이신설선", listOf("우이신설선")),
        SeoulLine("1093", "서해선", listOf("서해선")),
        SeoulLine("1094", "신림선", listOf("수도권 경량도시철도 신림선")),
        SeoulLine("1032", "GTX-A", listOf("수도권 광역급행철도")),
    )
    val byId: Map<String, SeoulLine> = all.associateBy { it.id }
    val byDataLine: Map<String, SeoulLine> = all.flatMap { l -> l.dataLines.map { it to l } }.toMap()
}

// ───────────────────────── 역 색인 ─────────────────────────

class StationIndex(val stations: List<MetroStation>) {

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

    private fun buildGroups(): List<StationGroup> {
        val buckets = ArrayList<MutableList<MetroStation>>()
        stations.groupBy { it.norm }.values.forEach { same ->
            val local = ArrayList<MutableList<MetroStation>>()
            same.forEach { s ->
                val hit = local.firstOrNull { g -> g.any { Geo.meters(it.lat, it.lng, s.lat, s.lng) < 500 } }
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
