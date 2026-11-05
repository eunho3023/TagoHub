package com.eunho.tagohub.web

import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.core.Row
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.BusLocation

/**
 * 웹·PC 버전 서울·경기 버스 (TAGO 가 다루지 않음). 서버 통로 /w/bus/seoul|gg/arrival|pos/<id> 의 JSON 을 해석.
 * 해석 규칙은 폰 앱 SeoulMappers2·GyeonggiMappers 와 같음.
 * 서울: 정류장 id "SEB"+stId, 정류장 번호(arsId)로 도착을 물음. 경기: 정류소·노선 id "GGB"+9자리.
 */
object MetroBus {
    const val SEOUL = "SEB"
    const val GG = "GGB"

    /** 서울시 API 로 물을 정류장 (정류장 번호가 있어야 물을 수 있음) */
    fun seoulStop(s: WebStop): Boolean = s.id.startsWith(SEOUL) && ars(s.no) != null

    /** 경기 GBIS 로 물을 정류장: GGB+9자리이고 경기(31…)·서울(11)·인천(23) 시군. 울산 등 다른 시의 GGB 는 TAGO */
    fun ggStop(s: WebStop): Boolean = s.id.startsWith(GG) && ggId(s.id) != null && ggCity(s.city)

    fun ggCity(city: String): Boolean = city.startsWith("31") || city == "11" || city == "23"

    /** 경기 id 에서 9자리 (아니면 null) */
    fun ggId(id: String): String? = id.removePrefix(GG).takeIf { id.startsWith(GG) && it.length == 9 && it.all(Char::isDigit) }

    /** 서울 노선 id 에서 숫자 부분 */
    fun seoulId(id: String): String? = id.removePrefix(SEOUL).takeIf { id.startsWith(SEOUL) && it.isNotEmpty() && it.length <= 12 && it.all(Char::isDigit) }

    /** 서울 정류장 번호는 5자리 (앞의 0 이 빠진 '1001' 도 맞춰 줌) */
    fun ars(no: String?): String? = no?.trim()?.takeIf { it.isNotEmpty() && it.length <= 5 && it.all(Char::isDigit) }?.padStart(5, '0')

    /** routeType: 1 공항, 2 마을, 3 간선, 4 지선, 5 순환, 6 광역, 7 인천, 8 경기, 10 관광, 13 동행, 14 한강, 15 심야, 0 공용 */
    fun seoulType(code: String?): String? = when (code?.trim()) {
        "1" -> "공항"; "2" -> "마을"; "3" -> "간선"; "4" -> "지선"; "5" -> "순환"; "6" -> "광역"
        "7" -> "인천"; "8" -> "경기"; "10" -> "관광"; "13" -> "동행"; "14" -> "한강"; "15" -> "심야"; "0" -> "공용"
        else -> null
    }

    /** "8분2초후[4번째 전]", "12분후[5번째 전]", "곧 도착" → (남은 초, 남은 정류장). 운행종료·출발대기는 null */
    fun parseArrMsg(msg: String?): Pair<Int, Int?>? {
        val m = msg?.trim() ?: return null
        if (m.isEmpty() || "운행종료" in m || "출발대기" in m || "회차대기" in m) return null
        val stops = Regex("\\[(\\d+)번째").find(m)?.groupValues?.get(1)?.toIntOrNull()
        if ("곧 도착" in m) return 30 to (stops ?: 0)
        val min = Regex("(\\d+)분").find(m)?.groupValues?.get(1)?.toIntOrNull()
        val sec = Regex("(\\d+)초").find(m)?.groupValues?.get(1)?.toIntOrNull()
        if (min == null && sec == null) return null
        return ((min ?: 0) * 60 + (sec ?: 0)) to stops
    }

    private fun rows(body: String, key: String): List<Row> = MiniJson.findRows(MiniJson.parse(body), key)

    /** 서울 정류장 도착 (getStationByUid): 한 행에 노선별 첫째·둘째 버스 */
    fun seoulArrivals(body: String): List<BusArrival> = rows(body, "arrmsg1").flatMap { r ->
        (1..2).mapNotNull { n ->
            val (sec, stops) = parseArrMsg(r.str("arrmsg$n")) ?: return@mapNotNull null
            BusArrival(
                routeId = SEOUL + r.text("busRouteId"),
                routeNo = r.str("busRouteAbrv") ?: r.text("rtNm"),
                routeType = seoulType(r.str("routeType")),
                vehicleType = when (r.str("busType$n")) { "1" -> "저상버스"; "2" -> "굴절버스"; else -> null },
                prevStops = stops,
                arrSeconds = sec,
                atStop = r.str("stationNm$n"),
                towards = r.str("adirection"),
            )
        }
    }

    /** 서울 노선 버스 위치 (getBusPosByRtid): gpsX 경도, gpsY 위도, sectOrd = 정류장 순번 */
    fun seoulBuses(body: String): List<BusLocation> = rows(body, "vehId").mapNotNull { r ->
        if (r.str("isrunyn") == "0") return@mapNotNull null
        BusLocation(
            nodeId = r.str("lastStnId")?.let { SEOUL + it },
            nodeName = null,
            ord = r.int("sectOrd"),
            vehicleNo = r.str("plainNo") ?: r.str("vehId") ?: return@mapNotNull null,
            lat = r.double("gpsY"),
            lng = r.double("gpsX"),
            lowFloor = r.str("busType")?.let { it == "1" },
        )
    }

    /** lowPlate: 1 저상, 2 2층, 5 전세, 6 예약, 7 트롤리 */
    private fun ggVehicle(lowPlate: Int?): String? = when (lowPlate) {
        1 -> "저상버스"; 2 -> "2층버스"; 5 -> "전세버스"; 6 -> "예약버스"; 7 -> "트롤리"; else -> null
    }

    private fun ggType(r: Row): String? = r.str("routeTypeName") ?: when (r.int("routeTypeCd")) {
        11 -> "직행좌석형시내버스"; 12 -> "좌석형시내버스"; 13 -> "일반형시내버스"; 14 -> "광역급행형시내버스"
        15 -> "따복형시내버스"; 16 -> "경기순환버스"; 21 -> "직행좌석형농어촌버스"; 22 -> "좌석형농어촌버스"
        23 -> "일반형농어촌버스"; 30 -> "마을버스"; 41 -> "고속형시외버스"; 42 -> "좌석형시외버스"
        43 -> "일반형시외버스"; 51 -> "리무진공항버스"; 52 -> "좌석형공항버스"; 53 -> "일반형공항버스"
        else -> null
    }

    /** 경기 정류소 도착 (getBusArrivalListv2): 한 행에 첫째·둘째 차량. predictTimeSec(초) 우선, 없으면 predictTime(분) */
    fun ggArrivals(body: String): List<BusArrival> = rows(body, "routeId").flatMap { r ->
        if (r.str("flag").equals("STOP", true)) return@flatMap emptyList()
        (1..2).mapNotNull { n ->
            val sec = r.int("predictTimeSec$n") ?: r.int("predictTime$n")?.times(60) ?: return@mapNotNull null
            BusArrival(
                routeId = GG + r.text("routeId"),
                routeNo = r.text("routeName"),
                routeType = ggType(r),
                vehicleType = ggVehicle(r.int("lowPlate$n")),
                prevStops = r.int("locationNo$n"),
                arrSeconds = sec,
                atStop = r.str("stationNm$n"),
                towards = r.str("routeDestName"),
            )
        }
    }

    /** 경기 노선 버스 위치 (getBusLocationListv2): 좌표 없음, 정류소 순번·상태만 */
    fun ggBuses(body: String): List<BusLocation> = rows(body, "plateNo").mapNotNull { r ->
        BusLocation(
            nodeId = r.str("stationId")?.let { GG + it },
            nodeName = null,
            ord = r.int("stationSeq"),
            vehicleNo = r.str("plateNo") ?: r.str("vehId") ?: return@mapNotNull null,
            lat = null,
            lng = null,
            lowFloor = r.int("lowPlate")?.let { it == 1 },
            state = r.int("stateCd"),
        )
    }
}
