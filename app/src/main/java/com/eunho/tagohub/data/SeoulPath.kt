package com.eunho.tagohub.data

import com.eunho.tagohub.core.Geo
import com.eunho.tagohub.core.TagoApiException
import org.w3c.dom.Element
import org.xml.sax.InputSource
import java.io.StringReader
import javax.xml.parsers.DocumentBuilderFactory

/**
 * 서울시 대중교통 환승경로 조회 (ws.bus.go.kr/api/rest/pathinfo).
 * 출발·도착 좌표 → 경로마다 거리·소요시간(분)·구간 목록(노선, 타는 곳, 내리는 곳 + 좌표).
 */
data class SeoulPathLeg(
    val routeId: String?, val routeNm: String,
    val fid: String?, val fname: String, val fx: Double?, val fy: Double?,
    val tid: String?, val tname: String, val tx: Double?, val ty: Double?,
    /** 지하철 구간의 역 사이 구간 수 = 지나가는 역 수 (실제 응답의 railLinkList 개수). 버스는 0 */
    val stations: Int = 0,
) {
    /** 지하철 구간: "2호선", "신분당선", "공항철도", "경의중앙선" … (버스는 "602", "N15", "강남01") */
    val subway: Boolean get() = Regex("(호선|선|철도)$|GTX").containsMatchIn(routeNm.trim())
}

data class SeoulPath(val distance: Int?, val minutes: Int?, val legs: List<SeoulPathLeg>)

object SeoulPathParser {
    fun parse(body: String): List<SeoulPath> {
        val text = body.trim().removePrefix("\uFEFF")
        if (!text.startsWith("<")) throw TagoApiException("FORMAT", "서울 경로검색 응답 형식 오류: ${text.take(120)}")
        val doc = DocumentBuilderFactory.newInstance().apply {
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        }.newDocumentBuilder().parse(InputSource(StringReader(text)))
        fun first(tag: String) = doc.getElementsByTagName(tag).let { if (it.length > 0) it.item(0).textContent?.trim() else null }
        first("returnAuthMsg")?.let { if (it.isNotBlank()) throw TagoApiException("AUTH", "서울 경로검색: $it") }
        when (val code = first("headerCd")) {
            null, "0" -> Unit
            "7", "4", "3" -> return emptyList() // 경로 없음 / 노선·정류소 없음
            else -> throw TagoApiException("P$code", "서울 경로검색 오류 ($code) ${first("headerMsg").orEmpty()}".trim())
        }
        val items = doc.getElementsByTagName("itemList")
        return (0 until items.length).mapNotNull { i ->
            val item = items.item(i) as? Element ?: return@mapNotNull null
            fun direct(el: Element, tag: String): String? {
                val ch = el.childNodes
                for (j in 0 until ch.length) {
                    val c = ch.item(j)
                    if (c is Element && c.tagName == tag) return c.textContent?.trim()?.ifEmpty { null }
                }
                return null
            }
            val legs = item.getElementsByTagName("pathList").let { pl ->
                (0 until pl.length).mapNotNull { k ->
                    val e = pl.item(k) as? Element ?: return@mapNotNull null
                    SeoulPathLeg(
                        routeId = direct(e, "routeId"), routeNm = direct(e, "routeNm") ?: return@mapNotNull null,
                        fid = direct(e, "fid"), fname = direct(e, "fname").orEmpty(),
                        fx = direct(e, "fx")?.toDoubleOrNull(), fy = direct(e, "fy")?.toDoubleOrNull(),
                        tid = direct(e, "tid"), tname = direct(e, "tname").orEmpty(),
                        tx = direct(e, "tx")?.toDoubleOrNull(), ty = direct(e, "ty")?.toDoubleOrNull(),
                        stations = e.getElementsByTagName("railLinkList").length,
                    )
                }
            }
            if (legs.isEmpty()) null
            else SeoulPath(direct(item, "distance")?.toIntOrNull(), direct(item, "time")?.toIntOrNull(), legs)
        }
    }

    /**
     * 앱 길찾기 결과로: 버스 구간은 서울 노선·정류장 ID(SEB…)로 이어서 지도·실시간에 쓰고,
     * 지하철 구간은 좌표로 그린다. 소요시간은 API 값 + 첫 차 대기.
     */
    /**
     * busStops: 버스 구간 정류장 수 계산 (노선 ID, 타는 정류장 ID, 내리는 정류장 ID → 정류장 수).
     * 응답에 정류장 수가 없어서 앱 안 서울 노선 정류장 순서로 센다.
     */
    fun toPlan(
        p: SeoulPath, fromLat: Double, fromLng: Double, toLat: Double, toLng: Double,
        busStops: (String, String, String) -> Int? = { _, _, _ -> null },
    ): TripPlan? {
        val first = p.legs.first()
        val last = p.legs.last()
        fun d(aLat: Double?, aLng: Double?, bLat: Double?, bLng: Double?) =
            if (aLat == null || aLng == null || bLat == null || bLng == null) 0.0 else Geo.meters(aLat, aLng, bLat, bLng)
        val legs = p.legs.map { l ->
            val routeId = if (l.subway) "SUBWAY:${l.routeNm}" else SeoulBus.app(l.routeId ?: l.routeNm)
            val board = if (l.subway) "SUBWAY:${l.fid}" else SeoulBus.app(l.fid.orEmpty())
            val alight = if (l.subway) "SUBWAY:${l.tid}" else SeoulBus.app(l.tid.orEmpty())
            TripLeg(
                routeId = routeId,
                routeNo = l.routeNm,
                boardStopId = board,
                boardName = l.fname,
                alightStopId = alight,
                alightName = l.tname,
                fromSeq = 0, toSeq = 0,
                stops = if (l.subway) l.stations else (busStops(routeId, board, alight) ?: 0),
                meters = d(l.fy, l.fx, l.ty, l.tx),
                subway = l.subway,
                boardLat = l.fy, boardLng = l.fx, alightLat = l.ty, alightLng = l.tx,
            )
        }
        val transferWalk = p.legs.zipWithNext().sumOf { (a, b) -> d(a.ty, a.tx, b.fy, b.fx) }
        return TripPlan(
            origin = BusStop("11", legs.first().boardStopId, first.fname, null, first.fy, first.fx),
            dest = BusStop("11", legs.last().alightStopId, last.tname, null, last.ty, last.tx),
            walkStartM = d(fromLat, fromLng, first.fy, first.fx),
            walkEndM = d(last.ty, last.tx, toLat, toLng),
            legs = legs,
            transferWalkM = transferWalk,
            fixedMinutes = p.minutes,
            source = "서울시 경로검색",
        )
    }

    /** 서울시 경로검색을 쓸 수 있는 범위 (서울·수도권 대략) */
    fun covers(lat: Double, lng: Double) = lat in 37.20..37.80 && lng in 126.55..127.40
}
