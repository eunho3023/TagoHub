package com.eunho.tagohub

import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.data.RouteLive
import com.eunho.tagohub.data.RouteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 노선 배치도: 고른 정류장까지 몇 정류장·몇 분 */
class RouteApproachTest {
    private val seg: (Int) -> Double = { 120.0 }   // 정류장 사이 2분

    @Test fun busAtStopCountsStopsAndTime() {
        val a = RouteLive.approach(2f, 5, seg)!!
        assertEquals(3, a.stops)
        assertEquals(360.0, a.sec, 1e-9)
        assertEquals("3정류장 전 · 6분 후", RouteLive.approachText(a))
    }

    @Test fun busBetweenStopsSubtractsProgress() {
        val a = RouteLive.approach(4.5f, 5, seg)!!   // 4~5번째 사이 가운데 → 1정류장 전, 1분
        assertEquals(1, a.stops)
        assertEquals(60.0, a.sec, 1e-9)
        assertEquals("1정류장 전 · 1분", RouteLive.approachText(a, short = true))
        assertEquals("1정류장 전 · 곧 도착", RouteLive.approachText(RouteLive.approach(4.8f, 5, seg)!!))
    }

    @Test fun arrivedAndPassed() {
        assertEquals("도착", RouteLive.approachText(RouteLive.approach(5f, 5, seg)!!))
        assertNull(RouteLive.approach(5.5f, 5, seg))   // 이미 지나감
    }

    /** 정류장에 서 있는 버스(도착 상태·GPS 가 정류장에 있음)는 그 정류장에 있어야 함. 첫 정류장으로 세면 모든 버스가 같은 "N정류장 전"으로 겹쳐 보였음 */
    @Test fun stoppedBusStaysAtItsStop() {
        val stops = (0..15).map { RouteStop("N$it", "정류장$it", null, it + 1, null, 35.8, 127.0 + it * 0.003) }
        val stopped = BusLocation(null, null, 13, "1749", 35.8, 127.0 + 12 * 0.003 + 0.0001, state = 1)   // 12번째 정류장, 도착 상태
        val gps = BusLocation(null, null, 9, "1750", 35.8, 127.0 + 8 * 0.003 + 0.0002)                   // 8번째 정류장 바로 옆 GPS
        val m = RouteLive.motions(listOf(stopped, gps), stops, since = { _, _ -> null }, travelSec = { _, _ -> 120.0 })
        assertEquals(listOf(12f, 8f), m.map { it.fraction(0) })
        val coming = m.mapNotNull { RouteLive.approach(it.fraction(0), 15, seg) }.sortedBy { it.sec }
        assertEquals(listOf(3, 7), coming.map { it.stops })   // 서로 다른 두 버스, 같은 값으로 겹치지 않음
    }
}
