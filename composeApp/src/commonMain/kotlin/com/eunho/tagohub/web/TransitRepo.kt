package com.eunho.tagohub.web

import com.eunho.tagohub.core.TagoXml
import com.eunho.tagohub.data.TrainTable
import com.eunho.tagohub.data.WebData
import com.eunho.tagohub.data.getText
import io.ktor.client.HttpClient
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** 기차역 (서버 data/files/train_stations.tsv: id, 이름, 지역, 위도, 경도) */
data class TrainStation(val id: String, val name: String, val region: String)

/** 화면에 보일 열차 한 편: 시간표(바로) + 국토교통부 조회(종류·요금, 나중에 채움) */
data class TrainTrip(val no: String, val kind: String?, val depMin: Int, val arrMin: Int, val fare: Int?)

/** 지하철역 (국토교통부 역 찾기) */
data class SubwayStationW(val id: String, val name: String, val line: String)

/** 지하철 출발 한 번: 하루 중 분(새벽 0~2시는 24시 뒤로), 종착역, 도착만(종점) */
data class SubwayDep(val min: Int, val end: String, val arrivalOnly: Boolean)

/**
 * 웹·PC 버전 기차·지하철 자료.
 * 기차: 서버에 올린 한국철도공사 시간표 파일로 바로 보여 주고, 웹 통로(/w/)의 국토교통부 조회로 종류·요금을 채움.
 * 지하철: 웹 통로의 국토교통부 역 찾기·역 시간표 (서버가 하루 저장해 두 번째부터는 바로).
 */
class TransitRepo(base: String, private val http: HttpClient) {
    private val base = base.trimEnd('/') + "/"
    private val lock = Mutex()
    private var trains: List<TrainTable.Train>? = null
    private var stations: List<TrainStation>? = null
    private val schedules = HashMap<String, List<SubwayDep>>()

    /** 열차 시간표 전체 (약 230KB, 한 번만) */
    suspend fun trains(): List<TrainTable.Train> = lock.withLock {
        trains ?: TrainTable.parse(http.getText(base + WebData.filePath("train_timetable.tsv"))).also { trains = it }
    }

    /** 기차역 목록 (시간표에 나오는 역만, 한 번만) */
    suspend fun trainStations(): List<TrainStation> {
        stations?.let { return it }
        val known = TrainTable.stationNames(trains()).keys
        val list = http.getText(base + WebData.filePath("train_stations.tsv")).lineSequence().mapNotNull { line ->
            val f = line.split('\t')
            if (f.size < 3 || !f[0].startsWith("NAT")) null else TrainStation(f[0], f[1].trim(), f[2].trim())
        }.filter { TrainTable.norm(it.name) in known }.distinctBy { TrainTable.norm(it.name) }.toList()
        stations = list
        return list
    }

    /** 시간표로 그날([isoDay]) [dep] → [arr] 열차 (서버 파일이라 바로 나옴) */
    suspend fun trainLegs(dep: String, arr: String, isoDay: Int): List<TrainTrip> =
        TrainTable.between(trains(), dep, arr, isoDay).map { TrainTrip(it.no, it.kind.ifBlank { null }, it.depMin, it.arrMin, null) }

    /**
     * 국토교통부 조회 (웹 통로): 그날 [depId] → [arrId] 열차의 종류·요금. 열차 번호 → (종류, 어른 요금). 못 받으면 빈 것.
     * [ymd] 는 "20261105".
     */
    suspend fun trainInfo(depId: String, arrId: String, ymd: String): Map<String, Pair<String?, Int?>> = runCatching {
        val xml = http.getText(
            base + "w/dg/1613000/TrainInfo/GetStrtpntAlocFndTrainInfo",
            query = listOf("depPlaceId" to depId, "arrPlaceId" to arrId, "depPlandTime" to ymd),
        )
        TagoXml.parse(xml).rows.mapNotNull { r ->
            val no = r.str("trainno")?.trimStart('0')?.ifEmpty { "0" } ?: return@mapNotNull null
            no to (r.str("traingradename") to r.int("adultcharge")?.takeIf { it > 0 })
        }.toMap()
    }.getOrDefault(emptyMap())

    /** 지하철역 찾기 (이름) */
    suspend fun subwayStations(q: String): List<SubwayStationW> {
        val name = q.trim().takeIf { it.isNotEmpty() } ?: return emptyList()
        val xml = http.getText(base + "w/dg/1613000/SubwayInfo/GetKwrdFndSubwaySttnList", query = listOf("subwayStationName" to name))
        return TagoXml.parse(xml).rows.mapNotNull { r ->
            val id = r.str("subwayStationId") ?: return@mapNotNull null
            SubwayStationW(id, r.text("subwayStationName", "subwayStationNm"), r.text("subwayRouteName", "subwayRouteNm"))
        }.distinctBy { it.id }
    }

    /** 지하철역 시간표: [day] 01 평일·02 토·03 일/공휴일, [upDown] U 상행·D 하행 (시각순, 같은 시각·종착 한 번) */
    suspend fun subwaySchedule(stationId: String, day: String, upDown: String): List<SubwayDep> {
        val key = "$stationId|$day|$upDown"
        schedules[key]?.let { return it }
        val xml = http.getText(
            base + "w/dg/1613000/SubwayInfo/GetSubwaySttnAcctoSchdulList",
            query = listOf("subwayStationId" to stationId, "dailyTypeCode" to day, "upDownTypeCode" to upDown),
        )
        val list = parseSchedule(TagoXml.parse(xml).rows.map { r -> Triple(r.str("depTime"), r.str("arrTime"), r.text("endSubwayStationNm", "endSubwayStationName")) })
        schedules[key] = list
        return list
    }

    companion object {
        /** "053000" → 하루 중 분 (새벽 0~2시는 전날 운행이라 24시 뒤로) */
        fun serviceMinute(hhmmss: String?): Int? {
            val s = hhmmss?.filter(Char::isDigit)?.takeIf { it.length >= 4 && !it.all { c -> c == '0' } } ?: return null
            val h = s.substring(0, 2).toIntOrNull() ?: return null
            val m = s.substring(2, 4).toIntOrNull() ?: return null
            val min = h * 60 + m
            return if (h < 3) min + 1440 else min
        }

        fun parseSchedule(rows: List<Triple<String?, String?, String>>): List<SubwayDep> = rows.mapNotNull { (dep, arr, end) ->
            val d = serviceMinute(dep)
            val a = serviceMinute(arr)
            val min = d ?: a ?: return@mapNotNull null
            SubwayDep(min, end, d == null)
        }.distinctBy { it.min to it.end }.sortedBy { it.min }

        /** 오늘 요일 → 지하철 시간표 요일 코드 (01 평일, 02 토, 03 일) */
        fun dayCode(isoDay: Int): String = when (isoDay) { 6 -> "02"; 7 -> "03"; else -> "01" }

        /** 시간표 열차 + 국토교통부 정보 합치기 (번호로) */
        fun withInfo(legs: List<TrainTrip>, info: Map<String, Pair<String?, Int?>>): List<TrainTrip> = legs.map { t ->
            val i = info[t.no.trimStart('0').ifEmpty { "0" }]
            if (i == null) t else t.copy(kind = i.first ?: t.kind, fare = i.second ?: t.fare)
        }
    }
}
