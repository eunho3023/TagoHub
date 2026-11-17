package com.eunho.tagohub

import com.eunho.tagohub.core.Fmt
import com.eunho.tagohub.data.ArrivalText
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.Crowd
import org.junit.Assert.assertEquals
import org.junit.Test

/** TAGO 버스도착정보 가이드 예시: arrtime 816초, arrprevstationcnt 15, vehicletp 저상버스, routetp 마을버스 */
class ArrivalTextTest {
    private val guide = BusArrival("DJB30300002", "5", "마을버스", "저상버스", 15, 816)

    @Test
    fun guideExample_secondsStopsLowFloor() {
        assertEquals("13분 · 15정류장 전 · 저상", ArrivalText.line(guide))
        assertEquals("13분 · 15정류장 전 · 저상", ArrivalText.line(guide, elapsedSec = 10))   // 매초 줄어듦
        assertEquals("43초 · 1정류장 전", ArrivalText.line(BusArrival("r", "5", null, "일반차량", 1, 43)))
        assertEquals("정류장 도착", ArrivalText.line(BusArrival("r", "5", null, null, 0, 20)))   // 0정류장 전 = 이미 와 있음
        assertEquals("정보없음", ArrivalText.line(BusArrival("r", "5", null, null, null, null)))
        assertEquals("곧 도착 · 15정류장 전 · 저상", ArrivalText.line(guide, elapsedSec = 900))       // 시간이 지나면 0 에서 멈춤 (10초마다 새로 받음)
        assertEquals("2분 05초", Fmt.arrival(125))
    }

    /** 서울·경기 도착 API 의 혼잡도·빈자리 (2026-11-17 실제 응답 값) */
    @Test
    fun crowdAndSeats() {
        assertEquals("여유", Crowd.seoul("3", "0"))
        assertEquals("매우혼잡", Crowd.seoul("6", null))
        assertEquals("만차", Crowd.seoul("5", "1"))
        assertEquals(null, Crowd.seoul("0", "0"))
        assertEquals("빈자리 22", Crowd.gyeonggi(1, 22))
        assertEquals("여유", Crowd.gyeonggi(1, 0))       // 좌석 없는 시내버스는 0
        assertEquals(null, Crowd.gyeonggi(null, -1))
        assertEquals("7분 · 3정류장 전 · 저상 · 혼잡", ArrivalText.line(BusArrival("r", "5", null, "저상버스", 3, 420, crowd = "혼잡")))
        assertEquals("정류장 도착 · 빈자리 5", ArrivalText.line(BusArrival("r", "5", null, null, 0, 10, crowd = "빈자리 5")))
    }
}
