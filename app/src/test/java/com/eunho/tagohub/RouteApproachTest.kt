package com.eunho.tagohub

import com.eunho.tagohub.data.RouteLive
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
}
