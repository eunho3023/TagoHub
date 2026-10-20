package com.eunho.tagohub

import com.eunho.tagohub.core.Fmt
import com.eunho.tagohub.data.ArrivalText
import com.eunho.tagohub.data.BusArrival
import org.junit.Assert.assertEquals
import org.junit.Test

/** TAGO 버스도착정보 가이드 예시: arrtime 816초, arrprevstationcnt 15, vehicletp 저상버스, routetp 마을버스 */
class ArrivalTextTest {
    private val guide = BusArrival("DJB30300002", "5", "마을버스", "저상버스", 15, 816)

    @Test
    fun guideExample_secondsStopsLowFloor() {
        assertEquals("13분 36초 · 15정류장 전 · 저상", ArrivalText.line(guide))
        assertEquals("13분 26초 · 15정류장 전 · 저상", ArrivalText.line(guide, elapsedSec = 10))   // 매초 줄어듦
        assertEquals("43초 · 1정류장 전", ArrivalText.line(BusArrival("r", "5", null, "일반차량", 1, 43)))
        assertEquals("곧 도착 · 이번 정류장", ArrivalText.line(BusArrival("r", "5", null, null, 0, 20)))
        assertEquals("정보없음", ArrivalText.line(BusArrival("r", "5", null, null, null, null)))
        assertEquals("곧 도착 · 15정류장 전 · 저상", ArrivalText.line(guide, elapsedSec = 900))       // 시간이 지나면 0 에서 멈춤 (10초마다 새로 받음)
        assertEquals("2분 05초", Fmt.arrival(125))
    }
}
