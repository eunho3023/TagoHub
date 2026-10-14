package com.eunho.tagohub

import com.eunho.tagohub.data.GbisParser
import com.eunho.tagohub.data.GyeonggiMappers
import com.eunho.tagohub.data.RouteDirections
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 경기 GBIS 실제 응답 (2026-10-12, 브라우저로 연 XML) */
class GbisRealTest {
    private fun xml(name: String) = javaClass.getResource("/fixtures/gbis_$name.xml")!!.readText(Charsets.UTF_8)

    @Test
    fun routeList_search12() {
        val r = GbisParser.rows(xml("getBusRouteListv2"), "routeId").map(GyeonggiMappers::route)
        assertEquals(46, r.size)
        assertEquals("GGB216000070", r[0].routeId)
        assertEquals("1200", r[0].routeNo)
        assertEquals("직행좌석형시내버스", r[0].routeType)
        assertEquals("원시역1번출구", r[0].startName)
        assertEquals("수원역10번출구.역전시장", r[0].endName)
        assertTrue(r.any { it.routeNo == "1241(예약)" })
        assertTrue(r.any { it.routeType == "마을버스" })
    }

    @Test
    fun routeInfo_98() {
        val info = GyeonggiMappers.route(GbisParser.rows(xml("getBusRouteInfoItemv2"), "routeId").single())
        assertEquals("GGB200000085", info.routeId)
        assertEquals("98", info.routeNo)
        assertEquals("04:50", info.firstTime)
        assertEquals("22:10", info.lastTime)
        assertEquals(18, info.interval)       // 평일 최소 배차
        assertEquals(23, info.intervalSat)
        assertEquals(25, info.intervalSun)
    }

    @Test
    fun routeStations_turnAtChungdam() {
        val stops = GyeonggiMappers.routeStops(GbisParser.rows(xml("getBusRouteStationListv2"), "stationId"))
        assertEquals(97, stops.size)
        assertEquals("성우스타우스", stops[0].name)
        assertEquals(37.3379333, stops[0].lat!!, 1e-7)
        assertEquals(127.1111833, stops[0].lng!!, 1e-7)
        // 회차점(turnYn=Y) 50번 청담초등학교앞까지 상행 "0", 그 뒤 하행 "1" → 방향 나누기
        assertEquals("0", stops[49].upDown)
        assertEquals("1", stops[50].upDown)
        assertEquals("청담초등학교앞", stops[RouteDirections.turnIndex(stops)!!].name)
    }

    @Test
    fun routeLine_721points() {
        val line = GyeonggiMappers.line(GbisParser.rows(xml("getBusRouteLineListv2"), "lineSeq"))
        assertEquals(721, line.size)
        assertEquals(37.00356, line.first().lat, 1e-6)
        assertEquals(127.14286, line.last().lng, 1e-6)
    }

    @Test
    fun errors_andEmpty() {
        val empty = """<response><msgHeader><resultCode>4</resultCode><resultMessage>결과가 존재하지 않습니다.</resultMessage></msgHeader><msgBody/></response>"""
        assertEquals(0, GbisParser.rows(empty, "routeId").size)
        val gw = """<OpenAPI_ServiceResponse><cmmMsgHeader><errMsg>SERVICE ERROR</errMsg><returnAuthMsg>SERVICE_KEY_IS_NOT_REGISTERED_ERROR</returnAuthMsg><returnReasonCode>30</returnReasonCode></cmmMsgHeader></OpenAPI_ServiceResponse>"""
        try { GbisParser.rows(gw, "routeId"); throw AssertionError("should fail") } catch (e: Exception) { assertTrue(e.message!!.contains("SERVICE_KEY_IS_NOT_REGISTERED")) }
        // JSON 도 그대로
        val json = """{"response":{"msgHeader":{"resultCode":0,"resultMessage":"정상"},"msgBody":{"busRouteList":[{"routeId":1,"routeName":"9"}]}}}"""
        assertEquals("9", GbisParser.rows(json, "routeId").single().text("routeName"))
    }

    /** 실제 JSON 응답 (format=json, "12" 검색): routeName·ID 가 따옴표 없는 숫자로 옴 → 그대로 문자열로 */
    @Test
    fun routeList_realJson_numbersStayExact() {
        val json = javaClass.getResource("/fixtures/gbis_routelist_real.json")!!.readText(Charsets.UTF_8)
        val r = GbisParser.rows(json, "routeId").map(GyeonggiMappers::route)
        assertEquals(46, r.size)
        assertEquals("1200", r[0].routeNo)               // 1200.0 이 아님
        assertEquals("GGB216000070", r[0].routeId)       // 2.1600007E8 이 아님
        assertEquals("원시역1번출구", r[0].startName)
        assertTrue(r.any { it.routeNo == "1200-1" })     // 문자열로 온 번호도
        // XML 로 받은 같은 검색 결과와 똑같이 해석됨
        val fromXml = GbisParser.rows(xml("getBusRouteListv2"), "routeId").map(GyeonggiMappers::route)
        assertEquals(fromXml.map { it.routeId to it.routeNo }, r.map { it.routeId to it.routeNo })
    }
}
