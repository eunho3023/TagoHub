package com.eunho.tagohub

import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.data.RouteLive
import com.eunho.tagohub.data.RouteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BusPlacementTest {
    // 동쪽으로 0.003° 경도(위도 37 에서 약 267m)씩 떨어진 정류장 4개
    private val stops = (0..3).map { RouteStop("N$it", "정류장$it", null, it + 1, null, 37.0, 127.0 + it * 0.003) }
    private fun bus(ord: Int, lng: Double?, state: Int? = null) = BusLocation(null, null, ord, "v$ord", 37.0, lng, state = state)

    @Test
    fun busesAtTheStopStayThereAndDepartedOnesMoveBetween() {
        val atStop = bus(2, 127.003 + 0.0002)              // 2번째 정류장 근처
        val gone = bus(3, 127.006 + 0.0015)                // 3번째 정류장을 떠나 다음으로
        val p = RouteLive.placement(listOf(atStop, gone), stops)
        assertEquals(listOf(atStop), p.at[1])
        assertEquals(listOf(gone), p.between[2])
        assertTrue(p.at[2].isNullOrEmpty())                // 떠난 버스는 그 정류장에서 빠짐
        assertEquals(2, p.fractions.size)
        assertEquals(1f, p.fractions[0], 1e-6f)
        assertEquals(2.5f, p.fractions[1], 0.1f)           // 3번째와 4번째 사이 가운데쯤
    }

    @Test
    fun justDepartedStateWithoutGpsIsDrawnShortlyAfterTheStop() {
        val p = RouteLive.placement(listOf(bus(2, null, state = 2)), stops)
        assertEquals(1.3f, p.fractions.single(), 0.01f)
        assertEquals(1, p.between[1]?.size)
    }

    @Test
    fun fractionsAreClampedAwayFromTheStopDots() {
        val farAhead = bus(2, 127.003 + 0.0035)            // 거의 다음 정류장
        val f = RouteLive.placement(listOf(farAhead), stops).fractions.single()
        assertTrue(f in 1.15f..1.85f)
    }
}
