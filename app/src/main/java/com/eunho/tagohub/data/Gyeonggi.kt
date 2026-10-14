package com.eunho.tagohub.data

import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoTime

/**
 * 경기도 버스정보 (GBIS v2, apis.data.go.kr/6410000, format=json).
 * 경기 정류장·노선 ID 는 TAGO ID 에서 "GGB" 를 뗀 9자리와 같다 (예: 233001450 ↔ GGB233001450, 내장 DB 와 일치 확인).
 */
object Gyeonggi {
    const val PREFIX = "GGB"
    fun isGyeonggi(tagoId: String) = tagoId.startsWith(PREFIX)
    fun std(tagoId: String) = tagoId.removePrefix(PREFIX)
    fun tago(stdId: String) = PREFIX + stdId.trim()
    /** TAGO 도시코드: 경기 시군은 31xxx */
    fun isGyeonggiCity(cityCode: String) = cityCode.startsWith("31")

    /** routeTypeCd → 이름 (routeTypeName 이 없을 때) */
    fun typeName(cd: Int?): String? = when (cd) {
        11 -> "직행좌석형시내버스"; 12 -> "좌석형시내버스"; 13 -> "일반형시내버스"; 14 -> "광역급행형시내버스"
        15 -> "따복형시내버스"; 16 -> "경기순환버스"; 21 -> "직행좌석형농어촌버스"; 22 -> "좌석형농어촌버스"
        23 -> "일반형농어촌버스"; 30 -> "마을버스"; 41 -> "고속형시외버스"; 42 -> "좌석형시외버스"
        43 -> "일반형시외버스"; 51 -> "리무진공항버스"; 52 -> "좌석형공항버스"; 53 -> "일반형공항버스"
        else -> null
    }

    /** lowPlate: 0 일반, 1 저상, 2 2층, 5 전세, 6 예약, 7 트롤리 */
    fun vehicleType(lowPlate: Int?): String? = when (lowPlate) {
        1 -> "저상버스"; 2 -> "2층버스"; 5 -> "전세버스"; 6 -> "예약버스"; 7 -> "트롤리"; else -> null
    }
}

object GyeonggiMappers {
    private fun type(r: Row) = r.str("routeTypeName") ?: Gyeonggi.typeName(r.int("routeTypeCd"))

    /** 정류소 도착: 한 행에 첫째·둘째 차량. predictTimeSec(초) 우선, 없으면 predictTime(분) */
    fun arrivals(r: Row): List<BusArrival> {
        if (r.str("flag").equals("STOP", true)) return emptyList() // 운행 종료
        return (1..2).mapNotNull { n ->
            val sec = r.int("predictTimeSec$n") ?: r.int("predictTime$n")?.times(60) ?: return@mapNotNull null
            BusArrival(
                routeId = Gyeonggi.tago(r.text("routeId")),
                routeNo = r.text("routeName"),
                routeType = type(r),
                vehicleType = Gyeonggi.vehicleType(r.int("lowPlate$n")),
                prevStops = r.int("locationNo$n"),
                arrSeconds = sec,
                atStop = r.str("stationNm$n"),
                towards = r.str("routeDestName"),
            )
        }
    }

    /** 노선 정보 (목록 검색·항목 조회·정류소 경유노선 공통) */
    fun route(r: Row) = BusRoute(
        routeId = Gyeonggi.tago(r.text("routeId")),
        routeNo = r.text("routeName"),
        routeType = type(r),
        startName = r.str("startStationName"),
        endName = r.str("endStationName") ?: r.str("routeDestName"),
        firstTime = TagoTime.clockText(r.str("upFirstTime")),
        lastTime = TagoTime.clockText(r.str("upLastTime")),
        interval = r.int("peekAlloc"),
        intervalSat = r.int("satPeekAlloc"),
        intervalSun = r.int("sunPeekAlloc"),
        cityCode = "31",
    )

    /** 경유 정류소 (좌표 포함: x 경도, y 위도). 회차점(turnYn=Y)까지 상행 "0", 그 뒤 하행 "1" */
    fun routeStops(rows: List<Row>): List<RouteStop> {
        val sorted = rows.filter { it.str("stationId") != null }.sortedBy { it.int("stationSeq") ?: 0 }
        val turn = sorted.indexOfFirst { it.str("turnYn").equals("Y", true) }
        return sorted.mapIndexed { i, r ->
            RouteStop(
                nodeId = Gyeonggi.tago(r.text("stationId")),
                name = r.text("stationName"),
                nodeNo = r.str("mobileNo"),
                ord = r.int("stationSeq") ?: 0,
                upDown = if (turn < 0) null else if (i <= turn) "0" else "1",
                lat = r.double("y"),
                lng = r.double("x"),
            )
        }
    }

    /** 노선 형상 (lineSeq 순) */
    fun line(rows: List<Row>): List<LatLng> = rows
        .mapNotNull { r -> val y = r.double("y"); val x = r.double("x"); if (y == null || x == null) null else (r.int("lineSeq") ?: 0) to LatLng(y, x) }
        .sortedBy { it.first }
        .map { it.second }

    /**
     * 버스 위치: 정류소 순번 + 상태(도착·출발·교차로통과). 좌표는 없음 → 정류장 순번으로 움직임을 추정한다.
     */
    fun buses(rows: List<Row>): List<BusLocation> = rows.mapNotNull { r ->
        BusLocation(
            nodeId = r.str("stationId")?.let(Gyeonggi::tago),
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

/**
 * 경기 GBIS 응답 해석: JSON(format=json) 과 XML 모두.
 * 실제 응답(2026-10-12)은 <response><msgHeader>…<resultCode>…</msgHeader><msgBody><busRouteList>…</busRouteList>…</msgBody></response>.
 * resultCode 0 정상, 4 결과 없음(빈 목록), 그 외 오류. data.go.kr 게이트웨이 오류(키 미등록 등)는 OpenAPI_ServiceResponse.
 */
object GbisParser {
    fun rows(body: String, key: String): List<com.eunho.tagohub.core.Row> {
        val text = body.trim().removePrefix("\uFEFF")
        return if (text.startsWith("<")) xmlRows(text, key) else jsonRows(text, key)
    }

    private fun check(code: Int?, msg: String?): Boolean {
        if (code == 4) return false
        if (code != null && code != 0) throw com.eunho.tagohub.core.TagoApiException("G$code", "경기 버스 API 오류 ($code) ${msg.orEmpty()}".trim())
        return true
    }

    private fun jsonRows(text: String, key: String): List<com.eunho.tagohub.core.Row> {
        val root = com.eunho.tagohub.core.MiniJson.parse(text)
        com.eunho.tagohub.core.MiniJson.findRows(root, "resultMessage").firstOrNull()?.let { h ->
            if (!check(h.int("resultCode"), h.str("resultMessage"))) return emptyList()
        }
        return com.eunho.tagohub.core.MiniJson.findRows(root, key)
    }

    private fun xmlRows(text: String, key: String): List<com.eunho.tagohub.core.Row> {
        val doc = javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newDocumentBuilder().parse(org.xml.sax.InputSource(java.io.StringReader(text)))
        fun first(tag: String) = doc.getElementsByTagName(tag).let { if (it.length > 0) it.item(0).textContent?.trim() else null }
        // 게이트웨이 오류: <OpenAPI_ServiceResponse><cmmMsgHeader><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg>…
        first("returnAuthMsg")?.takeIf { it.isNotBlank() && it != "NORMAL_SERVICE" }?.let {
            throw com.eunho.tagohub.core.TagoApiException("AUTH", "경기 버스 API: $it (${first("returnReasonCode").orEmpty()})")
        }
        if (!check(first("resultCode")?.toIntOrNull(), first("resultMessage"))) return emptyList()
        // 행 = key 를 자식으로 가진 요소 (busRouteList, busRouteStationList, busRouteLineList, busRouteInfoItem …)
        val out = ArrayList<com.eunho.tagohub.core.Row>()
        val all = doc.getElementsByTagName(key)
        val seen = HashSet<org.w3c.dom.Node>()
        for (i in 0 until all.length) {
            val parent = all.item(i).parentNode as? org.w3c.dom.Element ?: continue
            if (!seen.add(parent)) continue
            val map = HashMap<String, String>()
            val ch = parent.childNodes
            for (j in 0 until ch.length) {
                val c = ch.item(j)
                if (c is org.w3c.dom.Element) map[c.tagName] = c.textContent ?: ""
            }
            out += com.eunho.tagohub.core.Row(map)
        }
        return out
    }
}
