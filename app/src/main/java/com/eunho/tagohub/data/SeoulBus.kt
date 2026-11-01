package com.eunho.tagohub.data

import com.eunho.tagohub.core.Row
import com.eunho.tagohub.core.TagoApiException
import com.eunho.tagohub.core.TagoTime
import org.w3c.dom.Element
import org.w3c.dom.Node
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 서울시 버스 API (ws.bus.go.kr). 정류장 ID = "SEB"+stId, 노선 ID = "SEB"+busRouteId
 * (앱에 넣은 서울 노선 파일의 ROUTE_ID 와 같은 체계).
 */
object SeoulBus {
    const val PREFIX = "SEB"
    fun isSeoul(id: String) = id.startsWith(PREFIX)
    fun std(id: String) = id.removePrefix(PREFIX)
    fun app(stdId: String) = PREFIX + stdId.trim()

    /** 정류소 번호는 5자리 (종로·중구 …의 '01001'). 엑셀 등을 거치며 앞의 0 이 빠진 '1001' 도 맞춰 줌 */
    fun ars(id: String): String = id.trim().let { if (it.isNotEmpty() && it.all(Char::isDigit)) it.padStart(5, '0') else it }

    /** routeType: 1 공항, 2 마을, 3 간선, 4 지선, 5 순환, 6 광역, 7 인천, 8 경기, 9 폐지, 10 관광, 13 동행, 14 한강, 15 심야, 0 공용 */
    fun typeName(code: String?): String? = when (code?.trim()) {
        "1" -> "공항"; "2" -> "마을"; "3" -> "간선"; "4" -> "지선"; "5" -> "순환"; "6" -> "광역"
        "7" -> "인천"; "8" -> "경기"; "10" -> "관광"; "13" -> "동행"; "14" -> "한강"; "15" -> "심야"; "0" -> "공용"
        else -> null
    }

    /**
     * 도착 메시지 → (남은 초, 남은 정류장 수).
     * "8분2초후[4번째 전]", "12분후[5번째 전]", "곧 도착", "운행종료", "출발대기"
     */
    fun parseArrMsg(msg: String?): Pair<Int?, Int?>? {
        val m = msg?.trim() ?: return null
        if (m.isEmpty() || "운행종료" in m || "출발대기" in m || "회차대기" in m) return null
        val stops = Regex("\\[(\\d+)번째").find(m)?.groupValues?.get(1)?.toIntOrNull()
        if ("곧 도착" in m) return 30 to (stops ?: 0)
        val min = Regex("(\\d+)분").find(m)?.groupValues?.get(1)?.toIntOrNull()
        val sec = Regex("(\\d+)초").find(m)?.groupValues?.get(1)?.toIntOrNull()
        if (min == null && sec == null) return null
        return ((min ?: 0) * 60 + (sec ?: 0)) to stops
    }
}

/** 서울시 API 응답: <ServiceResult><msgHeader><headerCd>0</headerCd>…<msgBody><itemList>…</itemList>… */
object SeoulBusXml {
    fun parse(body: String): List<Row> {
        val text = body.trim().removePrefix("\uFEFF")
        if (!text.startsWith("<")) throw TagoApiException("FORMAT", "서울 버스 API 응답 형식 오류: ${text.take(120)}")
        val doc = try {
            DocumentBuilderFactory.newInstance().apply {
                runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            }.newDocumentBuilder().parse(InputSource(StringReader(text)))
        } catch (e: Exception) {
            throw TagoApiException("FORMAT", "서울 버스 API 응답을 해석하지 못했습니다: ${e.message}")
        }
        fun first(tag: String) = doc.getElementsByTagName(tag).let { if (it.length > 0) it.item(0).textContent?.trim() else null }
        // data.go.kr 게이트웨이 오류 (키 미등록 등)
        first("returnAuthMsg")?.let { if (it.isNotBlank()) throw TagoApiException("AUTH", "서울 버스 API: $it (${first("returnReasonCode").orEmpty()})") }
        val code = first("headerCd")
        if (code == "4") return emptyList() // 결과 없음
        if (code != null && code != "0") throw TagoApiException("S$code", "서울 버스 API 오류 ($code) ${first("headerMsg").orEmpty()}".trim())
        val items = doc.getElementsByTagName("itemList")
        return (0 until items.length).mapNotNull { i ->
            val el = items.item(i) as? Element ?: return@mapNotNull null
            val map = HashMap<String, String>()
            val ch = el.childNodes
            for (j in 0 until ch.length) {
                val c = ch.item(j)
                if (c.nodeType == Node.ELEMENT_NODE) map[c.nodeName] = c.textContent ?: ""
            }
            Row(map)
        }
    }
}

object SeoulMappers2 {
    /** 노선 정류장 (getStaionByRoute) 에서 회차 정류장: 첫 정류장이 아닌 transYn=Y 중 순번이 가장 앞인 것 */
    fun turnStation(rows: List<Row>): String? = rows
        .sortedBy { it.str("seq")?.toIntOrNull() ?: Int.MAX_VALUE }
        .drop(1)
        .firstOrNull { it.str("transYn") == "Y" }
        ?.str("station")?.let(SeoulBus::app)

    /**
     * 노선 정류장 (getStaionByRoute): 순번 순서, 좌표(gpsX 경도·gpsY 위도), 정류장 번호(arsId).
     * 회차 정류장(transYn)으로 상·하행을 나눔. 앱 안 서울 노선 표에 없는 노선(서울 정류장을 지나는 경기·인천 노선 등)에 씀
     */
    fun routeStops(rows: List<Row>): List<RouteStop> {
        val stops = rows.mapNotNull { r ->
            RouteStop(
                nodeId = r.str("station")?.let(SeoulBus::app) ?: return@mapNotNull null,
                name = r.str("stationNm") ?: return@mapNotNull null,
                nodeNo = r.str("arsId")?.takeIf { it != "0" && it.isNotBlank() },
                ord = r.str("seq")?.toIntOrNull() ?: return@mapNotNull null,
                upDown = null,
                lat = r.double("gpsY"),
                lng = r.double("gpsX"),
            )
        }.sortedBy { it.ord }
        return RouteDirections.markTurn(stops, turnStation(rows))
    }

    /** 정류장 도착 (getStationByUid): 한 행에 노선별 첫째·둘째 버스 */
    fun arrivals(r: Row): List<BusArrival> = (1..2).mapNotNull { n ->
        val (sec, stops) = SeoulBus.parseArrMsg(r.str("arrmsg$n")) ?: return@mapNotNull null
        BusArrival(
            routeId = SeoulBus.app(r.text("busRouteId")),
            routeNo = r.str("busRouteAbrv") ?: r.text("rtNm"),
            routeType = SeoulBus.typeName(r.str("routeType")),
            vehicleType = when (r.str("busType$n")) { "1" -> "저상버스"; "2" -> "굴절버스"; else -> null },
            prevStops = stops,
            arrSeconds = sec,
            atStop = r.str("stationNm$n"),
            towards = r.str("adirection"),
        )
    }

    /** 노선 버스 위치 (getBusPosByRtid): 맵매칭 GPS(gpsX 경도, gpsY 위도), 구간순번 = 정류장 순번 */
    fun buses(rows: List<Row>): List<BusLocation> = rows.mapNotNull { r ->
        if (r.str("isrunyn") == "0") return@mapNotNull null // 운행 종료 차량
        BusLocation(
            nodeId = r.str("lastStnId")?.let(SeoulBus::app),
            nodeName = null,
            ord = r.int("sectOrd"),
            vehicleNo = r.str("plainNo") ?: r.str("vehId") ?: return@mapNotNull null,
            lat = r.double("gpsY", "tmY"),
            lng = r.double("gpsX", "tmX"),
            lowFloor = r.str("busType")?.let { it == "1" },
        )
    }

    /** 노선 기본정보 (getRouteInfo) */
    fun routeInfo(r: Row) = BusRoute(
        routeId = SeoulBus.app(r.text("busRouteId")),
        routeNo = r.str("busRouteAbrv") ?: r.text("busRouteNm"),
        routeType = SeoulBus.typeName(r.str("routeType")),
        startName = r.str("stStationNm"),
        endName = r.str("edStationNm"),
        firstTime = TagoTime.clockText(r.str("firstBusTm")),
        lastTime = TagoTime.clockText(r.str("lastBusTm")),
        interval = r.int("term"),
        intervalSat = null,
        intervalSun = null,
        cityCode = "11",
    )

    /** 노선 모양 (getRoutePath): no 순서 */
    fun path(rows: List<Row>): List<LatLng> = rows
        .mapNotNull { r -> val y = r.double("gpsY"); val x = r.double("gpsX"); if (y == null || x == null) null else (r.int("no") ?: 0) to LatLng(y, x) }
        .sortedBy { it.first }.map { it.second }
}
