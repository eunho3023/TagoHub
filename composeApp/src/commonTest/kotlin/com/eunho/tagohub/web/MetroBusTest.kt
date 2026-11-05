package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class MetroBusTest {
    @Test fun whoAsksWhom() {
        val seoul = WebStop("SEB100000002", "창경궁", 37.57, 126.99, "1002", "11", emptyList())
        val ggInSeoul = WebStop("GGB100000001", "종로2가", 37.56, 126.98, "1001", "11", emptyList())
        val ulsanGgb = WebStop("GGB196040138", "울산 정류장", 35.5, 129.3, null, "26", emptyList())
        assertTrue(MetroBus.seoulStop(seoul))
        assertEquals("01002", MetroBus.ars(seoul.no))
        assertTrue(MetroBus.ggStop(ggInSeoul))
        assertFalse(MetroBus.ggStop(ulsanGgb))
        assertFalse(WebRepo.tagoStop(seoul))
        assertFalse(WebRepo.tagoStop(ggInSeoul))
        assertTrue(WebRepo.tagoStop(ulsanGgb))       // 울산 GGB 는 TAGO
        assertNull(MetroBus.ggId("GGB12345"))
    }

    @Test fun seoulArrivalsAndBuses() {
        val body = """{"comMsgHeader":{},"msgHeader":{"headerCd":"0","headerMsg":"정상"},"msgBody":{"itemList":[
            {"busRouteId":"100100118","busRouteAbrv":"472","routeType":"3","arrmsg1":"3분12초후[2번째 전]","arrmsg2":"곧 도착","busType1":"1","busType2":"0","adirection":"신사역"},
            {"busRouteId":"100100007","rtNm":"102","routeType":"3","arrmsg1":"운행종료","arrmsg2":"운행종료"}]}}"""
        val a = MetroBus.seoulArrivals(body)
        assertEquals(listOf(192, 30), a.map { it.arrSeconds })
        assertEquals("SEB100100118", a[0].routeId)
        assertEquals("간선", a[0].routeType)
        assertEquals("저상버스", a[0].vehicleType)
        assertEquals(2, a[0].prevStops)
        assertEquals("신사역", a[0].towards)

        val pos = """{"msgHeader":{"headerCd":"0"},"msgBody":{"itemList":[
            {"vehId":"1","plainNo":"서울74사1234","sectOrd":"5","lastStnId":"110000093","gpsX":"127.06","gpsY":"37.65","busType":"1","isrunyn":"1"},
            {"vehId":"2","plainNo":"서울74사9999","isrunyn":"0"}]}}"""
        val b = MetroBus.seoulBuses(pos)
        assertEquals(1, b.size)
        assertEquals("SEB110000093", b[0].nodeId)
        assertEquals(37.65, b[0].lat)
    }

    @Test fun gyeonggiArrivalsAndBuses() {
        val body = """{"response":{"msgHeader":{"resultCode":0,"resultMessage":"정상"},"msgBody":{"busArrivalList":
            {"routeId":234000315,"routeName":"9401","routeTypeCd":11,"predictTimeSec1":245,"predictTime2":17,"locationNo1":3,"lowPlate1":1,"flag":"PASS","routeDestName":"서울역"}}}}"""
        val a = MetroBus.ggArrivals(body)
        assertEquals(listOf(245, 1020), a.map { it.arrSeconds })
        assertEquals("GGB234000315", a[0].routeId)
        assertEquals("직행좌석형시내버스", a[0].routeType)
        assertEquals("저상버스", a[0].vehicleType)

        val none = """{"response":{"msgHeader":{"resultCode":4,"resultMessage":"결과가 존재하지 않습니다."}}}"""
        assertEquals(emptyList(), MetroBus.ggArrivals(none))

        val pos = """{"response":{"msgBody":{"busLocationList":[{"plateNo":"경기70아1234","stationId":233001450,"stationSeq":12,"stateCd":1,"lowPlate":0}]}}}"""
        val b = MetroBus.ggBuses(pos)
        assertEquals("GGB233001450", b[0].nodeId)
        assertEquals(12, b[0].ord)
        assertNull(b[0].lat)
    }
}
