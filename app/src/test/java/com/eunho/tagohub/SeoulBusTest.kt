package com.eunho.tagohub

import com.eunho.tagohub.data.SeoulBus
import com.eunho.tagohub.data.SeoulMappers2
import com.eunho.tagohub.data.SeoulBusXml
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 서울시 버스 API 명세의 필드·구조로 만든 응답 */
class SeoulBusTest {
    private fun wrap(items: String, cd: String = "0") =
        """<?xml version="1.0" encoding="UTF-8"?><ServiceResult><comMsgHeader/><msgHeader><headerCd>$cd</headerCd><headerMsg>정상적으로 처리되었습니다.</headerMsg><itemCount>0</itemCount></msgHeader><msgBody>$items</msgBody></ServiceResult>"""

    @Test
    fun arsIsPaddedToFiveDigits() {
        assertEquals("01001", SeoulBus.ars("1001"))     // 엑셀을 거치며 앞의 0 이 빠진 번호도
        assertEquals("01001", SeoulBus.ars(" 01001 "))
        assertEquals("11486", SeoulBus.ars("11486"))
        assertEquals("", SeoulBus.ars(""))
    }

    @Test
    fun arrMsg_parsing() {
        assertEquals(482 to 4, SeoulBus.parseArrMsg("8분2초후[4번째 전]"))
        assertEquals(720 to 5, SeoulBus.parseArrMsg("12분후[5번째 전]"))
        assertEquals(30 to 0, SeoulBus.parseArrMsg("곧 도착"))
        assertEquals(null, SeoulBus.parseArrMsg("운행종료"))
        assertEquals(null, SeoulBus.parseArrMsg("출발대기"))
    }

    @Test
    fun stationArrivals_twoBusesPerRoute() {
        val rows = SeoulBusXml.parse(wrap("""<itemList><adirection>노원역</adirection><arrmsg1>3분12초후[2번째 전]</arrmsg1><arrmsg2>14분후[8번째 전]</arrmsg2>
            <arsId>11486</arsId><busRouteAbrv>147</busRouteAbrv><busRouteId>100100026</busRouteId><busType1>1</busType1><busType2>0</busType2>
            <routeType>3</routeType><rtNm>147</rtNm><stationNm1>월계동</stationNm1><stationNm2>상계동</stationNm2><stId>110000384</stId></itemList>
            <itemList><arrmsg1>운행종료</arrmsg1><arrmsg2>운행종료</arrmsg2><busRouteAbrv>N15</busRouteAbrv><busRouteId>100100999</busRouteId><routeType>15</routeType></itemList>"""))
        val a = rows.flatMap(SeoulMappers2::arrivals)
        assertEquals(2, a.size) // 운행종료 노선은 없음
        assertEquals("SEB100100026", a[0].routeId)
        assertEquals("147", a[0].routeNo)
        assertEquals("간선", a[0].routeType)
        assertEquals(192, a[0].arrSeconds)
        assertEquals(2, a[0].prevStops)
        assertEquals("저상버스", a[0].vehicleType)
        assertEquals("월계동", a[0].atStop)
        assertEquals(840, a[1].arrSeconds)
    }

    @Test
    fun busPositions_gps() {
        val b = SeoulMappers2.buses(SeoulBusXml.parse(wrap("""<itemList><busType>1</busType><gpsX>127.06447</gpsX><gpsY>37.627618</gpsY>
            <isrunyn>1</isrunyn><lastStnId>110000384</lastStnId><plainNo>서울74사1234</plainNo><sectOrd>12</sectOrd><vehId>111</vehId></itemList>
            <itemList><isrunyn>0</isrunyn><plainNo>서울74사9999</plainNo><gpsX>127.0</gpsX><gpsY>37.6</gpsY></itemList>""")))
        val x = b.single() // 운행 종료 차량은 빠짐
        assertEquals("서울74사1234", x.vehicleNo)
        assertEquals(12, x.ord)
        assertEquals(37.627618, x.lat!!, 1e-9)
        assertEquals(true, x.lowFloor)
    }

    @Test
    fun routeInfo_andErrors() {
        val r = SeoulMappers2.routeInfo(SeoulBusXml.parse(wrap("""<itemList><busRouteId>100100026</busRouteId><busRouteNm>147</busRouteNm><routeType>3</routeType>
            <stStationNm>월계동</stStationNm><edStationNm>강남역</edStationNm><term>9</term><firstBusTm>20261011040000</firstBusTm><lastBusTm>20261011223000</lastBusTm></itemList>""")).single())
        assertEquals("04:00", r.firstTime)
        assertEquals("22:30", r.lastTime)
        assertEquals(9, r.interval)
        assertEquals(0, SeoulBusXml.parse(wrap("", cd = "4")).size) // 결과 없음 = 빈 목록
        try {
            SeoulBusXml.parse(wrap("", cd = "7")); throw AssertionError("should fail")
        } catch (e: Exception) { assertTrue(e.message!!.contains("(7)")) }
    }
}
