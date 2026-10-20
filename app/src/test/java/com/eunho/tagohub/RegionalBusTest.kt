package com.eunho.tagohub

import com.eunho.tagohub.data.RegionalBus
import com.eunho.tagohub.data.RegionalKind
import com.eunho.tagohub.data.RegionalMappers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionalBusTest {
    @Test
    fun stopIdPrefixesMapToRegions() {
        assertEquals(RegionalKind.BUSAN to "505780000", RegionalBus.of("BSB505780000", "21")!!.let { it.kind to it.stdId })
        assertEquals(RegionalKind.ULSAN to "196040122", RegionalBus.of("GGB196040122", "26")!!.let { it.kind to it.stdId })
        assertEquals(RegionalKind.GWANGJU to "2873", RegionalBus.of("KJB2873", "24")!!.let { it.kind to it.stdId })
        assertEquals(RegionalKind.YEOSU to "325001135", RegionalBus.of("YEB325001135", "36020")!!.let { it.kind to it.stdId })
        // 경기(GGB)·광주시(경기, 38090) 는 해당 없음
        assertNull(RegionalBus.of("GGB233000001", "31010"))
        assertNull(RegionalBus.of("GJB1", "38090"))
    }

    @Test
    fun busanTwoVehiclesPerRowAndWaitingSkipped() {
        val xml = """<response><header><resultCode>00</resultCode></header><body><items>
            <item><lineno>1010</lineno><lineid>5201010000</lineid><bustype>급행버스</bustype>
            <min1>5</min1><station1>2</station1><lowplate1>1</lowplate1>
            <min2>운행대기</min2><station2>5</station2><lowplate2>0</lowplate2></item>
            </items></body></response>"""
        val list = RegionalMappers.busan(RegionalMappers.xmlRows(xml, "부산"))
        assertEquals(1, list.size)
        assertEquals("1010", list[0].routeNo)
        assertEquals(300, list[0].arrSeconds)
        assertEquals(2, list[0].prevStops)
        assertEquals("저상버스", list[0].vehicleType)
    }

    @Test
    fun ulsanSplitsRouteAndDirection() {
        val xml = """<tableInfo><resultCode>200</resultCode><resultMsg>SUCCESS</resultMsg><list>
            <row><PREVSTOPCNT>5</PREVSTOPCNT><ARRIVALTIME>245</ARRIVALTIME><STOPID>196040122</STOPID>
            <PRESENTSTOPNM>변전소삼거리</PRESENTSTOPNM><ROUTENM>527(태화강역방면)</ROUTENM></row>
            </list></tableInfo>"""
        val a = RegionalMappers.ulsan(RegionalMappers.xmlRows(xml, "울산")).single()
        assertEquals("527", a.routeNo)
        assertEquals("태화강역", a.towards)
        assertEquals("변전소삼거리", a.atStop)
        assertEquals(245, a.arrSeconds)
        assertEquals(5, a.prevStops)
    }

    @Test
    fun gwangjuJson() {
        val json = """{"RESULT":{"RESULT_MSG":"ok","RESULT_CODE":"SUCCESS"},"BUSSTOP_LIST":[
            {"REMAIN_MIN":5,"REMAIN_STOP":1,"DIR_END":"첨단","BUSSTOP_NAME":"북성중","LOW_BUS":"1","LINE_ID":5,"LINE_NAME":"첨단09"},
            {"REMAIN_MIN":7,"REMAIN_STOP":4,"DIR_END":"공항","BUSSTOP_NAME":"광주일고","LOW_BUS":"0","LINE_ID":224,"LINE_NAME":"1000"}],"ROW_COUNT":2}"""
        val list = RegionalMappers.gwangju(RegionalMappers.gwangjuRows(json))
        assertEquals(listOf("첨단09", "1000"), list.map { it.routeNo })
        assertEquals("첨단", list[0].towards)
        assertEquals("북성중", list[0].atStop)
        assertEquals(300, list[0].arrSeconds)
        assertEquals("저상버스", list[0].vehicleType)
    }

    @Test
    fun yeosuTimetableTextHasNoArrivalSeconds() {
        val xml = """<response><header><resultCode>0</resultCode></header><body><items>
            <item><ROUTE_ID>325000107</ROUTE_ID><ROUTE_NAME>100A</ROUTE_NAME><ED_STOP_NAME>봉수</ED_STOP_NAME>
            <LAST_STOP_NAME>미평초등학교</LAST_STOP_NAME><RSTOP>3</RSTOP><PROVIDE_TYPE>4분</PROVIDE_TYPE></item>
            <item><ROUTE_ID>325000108</ROUTE_ID><ROUTE_NAME>200</ROUTE_NAME><RSTOP>기점</RSTOP>
            <PROVIDE_TYPE>15시 35분 출발 예정</PROVIDE_TYPE></item>
            </items></body></response>"""
        val list = RegionalMappers.yeosu(RegionalMappers.xmlRows(xml, "여수"))
        assertEquals(240, list.first { it.routeNo == "100A" }.arrSeconds)
        assertEquals("봉수", list.first { it.routeNo == "100A" }.towards)
        val waiting = list.first { it.routeNo == "200" }
        assertNull(waiting.arrSeconds)
        assertNull(waiting.prevStops)
    }

    @Test(expected = java.io.IOException::class)
    fun failureCodeThrows() {
        RegionalMappers.xmlRows("<tableInfo><resultCode>300</resultCode><resultMsg>FAIL</resultMsg></tableInfo>", "울산")
    }
}
