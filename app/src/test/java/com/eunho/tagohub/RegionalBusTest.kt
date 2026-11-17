package com.eunho.tagohub

import com.eunho.tagohub.data.RegionalBus
import com.eunho.tagohub.data.RegionalKind
import com.eunho.tagohub.data.RegionalMappers
import com.eunho.tagohub.data.RouteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RegionalBusTest {
    @Test
    fun stopIdPrefixesMapToRegions() {
        assertEquals(RegionalKind.BUSAN to "505780000", RegionalBus.of("BSB505780000", "21")!!.let { it.kind to it.stdId })
        assertEquals(RegionalKind.ULSAN to "196040122", RegionalBus.of("USB196040122", "26")!!.let { it.kind to it.stdId })
        assertNull(RegionalBus.of("GGB196040122", "26"))   // 앱·TAGO 의 울산 정류장은 USB
        assertEquals(RegionalKind.GWANGJU to "2873", RegionalBus.of("KJB2873", "24")!!.let { it.kind to it.stdId })
        assertEquals(RegionalKind.YEOSU to "325001135", RegionalBus.of("YEB325001135", "36020")!!.let { it.kind to it.stdId })
        // 경기(GGB)·광주시(경기, 38090) 는 해당 없음
        assertNull(RegionalBus.of("GGB233000001", "31010"))
        assertNull(RegionalBus.of("GJB1", "38090"))
        assertEquals(RegionalKind.DAEJEON to "8001378", RegionalBus.of("DJB8001378", "25")!!.let { it.kind to it.stdId })
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

    /** 새 주소: 노선번호만 오고 방면은 REMARK */
    @Test
    fun ulsanGatewayDirectionFromRemark() {
        val xml = """<tableInfo><resultCode>200</resultCode><resultMsg>SUCCESS</resultMsg><list>
            <row><VEHICLENO>울산71자2207</VEHICLENO><PREVSTOPCNT>3</PREVSTOPCNT><ARRIVALTIME>217</ARRIVALTIME><ROUTEID>195000199</ROUTEID>
            <REMARK>처용마을입구 방면</REMARK><STOPID>196040122</STOPID><PRESENTSTOPNM>울산119안전센터</PRESENTSTOPNM><ROUTENM>725</ROUTENM></row>
            </list></tableInfo>"""
        val a = RegionalMappers.ulsan(RegionalMappers.xmlRows(xml, "울산")).single()
        assertEquals("725", a.routeNo)
        assertEquals("USB195000199", a.routeId)
        assertEquals("처용마을입구", a.towards)
        assertEquals("울산119안전센터", a.atStop)
        assertEquals(217, a.arrSeconds)
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

    /** 실제 광주 응답 모양 (RESPONSE 로 한 겹 더 감싸짐) */
    @Test
    fun gwangjuRealResponseShape() {
        val json = """{"RESPONSE":{"RESULT":{"RESULT_MSG":"정상적으로 처리되었습니다.","RESULT_CODE":"SUCCESS"},"ARRIVE_LIST":{"ITEM":[{"ARRIVE":"","LINE_ID":5,"LINE_NAME":"첨단09","SHORT_LINE_NAME":"첨단09","BUS_ID":"772165","METRO_FLAG":null,"CURR_STOP_ID":2367,"BUSSTOP_NAME":"북성중","REMAIN_MIN":1,"REMAIN_STOP":1,"DIR_START":"무등산국립공원(증심사)","DIR_END":"첨단","LOW_BUS":"1","ARRIVE_FLAG":1,"LINE_KIND":1}]}}}"""
        val a = RegionalMappers.gwangju(RegionalMappers.gwangjuRows(json)).single()
        assertEquals("첨단09", a.routeNo)
        assertEquals("첨단", a.towards)
        assertEquals("북성중", a.atStop)
        assertEquals(60, a.arrSeconds)
    }

    @Test(expected = java.io.IOException::class)
    fun gwangjuNestedErrorIsNotSilentlyEmpty() {
        RegionalMappers.gwangjuRows("""{"RESPONSE":{"RESULT":{"RESULT_CODE":"ERROR","RESULT_MSG":"잘못된 요청"}}}""")
    }

    /** 대전 BIS 정류장 도착 (2026-11-17 실제 응답 모양). MSG_TP 07 은 출발 전 차라 EXTIME_SEC 가 출발 시각(1440 = 14:40) */
    @Test
    fun daejeonArrivalsAndWaitingBus() {
        val xml = """<?xml version="1.0" encoding="UTF-8" standalone="yes"?><ServiceResult><msgHeader><currentPage>1</currentPage><headerCd>0</headerCd><headerMsg>정상적으로 처리되었습니다.</headerMsg><itemCnt>2</itemCnt></msgHeader><msgBody>
            <itemList><BUS_NODE_ID>8001378</BUS_NODE_ID><CAR_REG_NO>대전75자9224</CAR_REG_NO><DESTINATION>탄방역종점</DESTINATION><EXTIME_MIN>3</EXTIME_MIN><EXTIME_SEC>128</EXTIME_SEC><MSG_TP>03</MSG_TP><ROUTE_CD>30300039</ROUTE_CD><ROUTE_NO>104</ROUTE_NO><ROUTE_TP>2  </ROUTE_TP><STATUS_POS>2</STATUS_POS></itemList>
            <itemList><BUS_NODE_ID>8001378</BUS_NODE_ID><DESTINATION>탑립동</DESTINATION><EXTIME_MIN>1</EXTIME_MIN><EXTIME_SEC>1448</EXTIME_SEC><MSG_TP>07</MSG_TP><ROUTE_CD>30300096</ROUTE_CD><ROUTE_NO>918</ROUTE_NO><ROUTE_TP>2</ROUTE_TP><STATUS_POS>2</STATUS_POS></itemList>
            </msgBody></ServiceResult>"""
        val list = RegionalMappers.daejeon(RegionalMappers.xmlRows(xml, "대전"))
        val a = list.first()
        assertEquals("DJB30300039", a.routeId)
        assertEquals("104", a.routeNo)
        assertEquals("간선버스", a.routeType)
        assertEquals(128, a.arrSeconds)
        assertEquals(2, a.prevStops)
        assertEquals("탄방역종점", a.towards)
        val waiting = list.last()
        assertEquals("918", waiting.routeNo)
        assertNull(waiting.arrSeconds)
        assertNull(waiting.prevStops)
        assertEquals("광역버스", RegionalMappers.daejeonType("7"))
    }

    @Test(expected = java.io.IOException::class)
    fun daejeonHeaderErrorThrows() {
        RegionalMappers.xmlRows("<ServiceResult><msgHeader><headerCd>2</headerCd><headerMsg>필수 요청 Parameter가 존재하지 않습니다.</headerMsg></msgHeader><msgBody/></ServiceResult>", "대전")
    }

    /** 대전 버스 위치: 순번이 없어 정류장 ID 로 찾고, 같은 정류장이 두 번 있으면 DIR 0 앞쪽·1 뒤쪽 */
    @Test
    fun daejeonBusesMatchStopsByIdAndDirection() {
        val xml = """<ServiceResult><msgHeader><headerCd>0</headerCd></msgHeader><msgBody>
            <itemList><BUS_NODE_ID>8002589</BUS_NODE_ID><DIR>0</DIR><GPS_LATI>36.359108</GPS_LATI><GPS_LONG>127.309164</GPS_LONG><PLATE_NO>대전75자9263</PLATE_NO></itemList>
            <itemList><BUS_NODE_ID>8002589</BUS_NODE_ID><DIR>1</DIR><GPS_LATI>36.359108</GPS_LATI><GPS_LONG>127.309164</GPS_LONG><PLATE_NO>대전75자9217</PLATE_NO></itemList>
            <itemList><BUS_NODE_ID>9999999</BUS_NODE_ID><DIR>0</DIR><PLATE_NO>대전75자1</PLATE_NO></itemList>
            </msgBody></ServiceResult>"""
        val stops = listOf(
            RouteStop("DJB8002589", "가", null, 7, null, 36.359108, 127.309164),
            RouteStop("DJB8001000", "나", null, 30, null, null, null),
            RouteStop("DJB8002589", "가", null, 64, null, 36.359108, 127.309164),
        )
        val b = RegionalMappers.daejeonBuses(RegionalMappers.xmlRows(xml, "대전"), stops)
        assertEquals(listOf(7, 64, null), b.map { it.ord })
        assertEquals("DJB8002589", b[0].nodeId)
        assertEquals("대전75자9263", b[0].vehicleNo)
        assertEquals(36.359108, b[0].lat!!, 1e-9)
    }
}
