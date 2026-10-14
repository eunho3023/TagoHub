package com.eunho.tagohub

import com.eunho.tagohub.core.MiniJson
import com.eunho.tagohub.data.Jeju
import com.eunho.tagohub.data.JejuMappers
import com.eunho.tagohub.data.LatLng
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 제주 BIS Open-API 가이드의 필드명으로 만든 응답 (실제 응답 구조는 미확인 → 구조가 달라도 행을 찾는지 검증) */
class JejuTest {
    private val arrivalsWrapped = """{"resultCode":"0","resultList":[
        {"ROUTE_ID":"405090001","ROUTE_NUM":"365","ROUTE_SUB_NM":"제주버스터미널","ARRV_STATION_ID":"405000155",
         "CURR_STATION_ID":"405000150","CURR_STATION_NM":"제주시청","PREDICT_TRAV_TM":4,"REMAIN_STATION":3,
         "VH_ID":"7031","PLATE_NO":"제주79자1234","LOW_PLATE_TP":"Y"},
        {"ROUTE_ID":"405090001","ROUTE_NUM":"365","PREDICT_TRAV_TM":19,"REMAIN_STATION":12,"PLATE_NO":"제주79자5678","LOW_PLATE_TP":"N"}]}"""

    @Test
    fun arrivals_anyWrapper() {
        val rows = MiniJson.findRows(MiniJson.parse(arrivalsWrapped), "ROUTE_ID")
        val a = rows.map(JejuMappers::arrival)
        assertEquals(2, a.size)
        assertEquals("JEB405090001", a[0].routeId)
        assertEquals("365", a[0].routeNo)
        assertEquals(240, a[0].arrSeconds)   // 4분
        assertEquals(3, a[0].prevStops)
        assertEquals("저상버스", a[0].vehicleType)
        assertEquals("제주시청", a[0].atStop)
        assertEquals(null, a[1].vehicleType)
        // 배열이 바로 오는 응답도
        val bare = MiniJson.findRows(MiniJson.parse("""[{"ROUTE_ID":"1","ROUTE_NUM":"201"}]"""), "ROUTE_ID")
        assertEquals("201", bare.single().text("ROUTE_NUM"))
        // 더 깊이 감싼 응답도
        val deep = MiniJson.findRows(MiniJson.parse("""{"body":{"items":{"item":[{"ROUTE_ID":"9"}]}}}"""), "ROUTE_ID")
        assertEquals(1, deep.size)
    }

    @Test
    fun routeStops_andBusesWithOrd() {
        val stopsJson = """[{"ROUTE_ID":"405090001","STATION_ORD":1,"STATION_ID":"406000191","STATION_NM":"앞동산[남]","ROUTE_DIST":0},
                            {"ROUTE_ID":"405090001","STATION_ORD":2,"STATION_ID":"406000421","STATION_NM":"천지동주민센터","ROUTE_DIST":820}]"""
        val coords = mapOf("JEB406000191" to LatLng(33.25, 126.56), "JEB406000421" to LatLng(33.252, 126.565))
        val stops = JejuMappers.routeStops(MiniJson.findRows(MiniJson.parse(stopsJson), "STATION_ID")) { coords[it] }
        assertEquals(listOf("JEB406000191", "JEB406000421"), stops.map { it.nodeId })
        assertEquals(33.25, stops[0].lat!!, 1e-9)

        val busJson = """{"list":[
          {"ROUTE_ID":"405090001","ROUTE_NUM":"365","CURR_STATION_ID":"406000421","CURR_STATION_NM":"천지동주민센터",
           "VH_ID":"7031","PLATE_NO":"제주79자1234","LOW_PLATE_TP":"Y","LOCAL_X":126.5651,"LOCAL_Y":33.2521},
          {"ROUTE_ID":"999999999","PLATE_NO":"다른노선","LOCAL_X":126.0,"LOCAL_Y":33.0}]}"""
        val buses = JejuMappers.buses(MiniJson.findRows(MiniJson.parse(busJson), "ROUTE_ID"), "405090001", stops)
        val b = buses.single()
        assertEquals("제주79자1234", b.vehicleNo)
        assertEquals(2, b.ord)                 // 최근 정류장 → 노선 순번
        assertEquals(33.2521, b.lat!!, 1e-9)
        assertEquals(true, b.lowFloor)
    }

    @Test
    fun routeList() {
        val r = JejuMappers.route(MiniJson.findRows(MiniJson.parse(
            """[{"ROUTE_ID":"405090001","ROUTE_NM":"365(제주버스터미널)","ROUTE_NUM":"365","FIRST_TM":"0600","LAST_TM":"2130",
                "MAX_INTERVAL":30,"MIN_INTERVAL":15,"ORGT_NM":"제주버스터미널","DST_NM":"서귀포"}]"""), "ROUTE_ID").single())
        assertEquals("JEB405090001", r.routeId)
        assertEquals("06:00", r.firstTime)
        assertEquals("21:30", r.lastTime)
        assertEquals("39", r.cityCode)
        assertTrue(Jeju.isJeju(r.routeId))
    }

    /** 사용자가 받은 실제 응답 (앞동산[남] 406000191): 배열이 바로 오고, ID·시간은 숫자 */
    @Test
    fun realArrivalsResponse() {
        val text = javaClass.getResource("/fixtures/jeju_arrivals.json")!!.readText(Charsets.UTF_8)
        val a = MiniJson.findRows(MiniJson.parse(text), "ROUTE_ID").map(JejuMappers::arrival)
        assertEquals(listOf("231", "510", "201", "621"), a.map { it.routeNo })
        assertEquals("JEB405323102", a[0].routeId)
        assertEquals(180, a[0].arrSeconds)              // 3분
        assertEquals(3, a[0].prevStops)
        assertEquals("상효입구", a[0].atStop)
        assertEquals("제주버스터미널(종점)", a[0].towards)
        assertEquals("저상버스", a[1].vehicleType)       // 510번 LOW_PLATE_TP Y
        assertEquals(null, a[0].vehicleType)
        assertEquals(40 * 60, a[3].arrSeconds)
        assertEquals(38, a[3].prevStops)
    }
}
