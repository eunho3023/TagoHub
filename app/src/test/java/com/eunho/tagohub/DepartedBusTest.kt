package com.eunho.tagohub

import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.data.RouteLive
import com.eunho.tagohub.data.RouteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DepartedBusTest {
    // 동쪽으로 약 0.001° 경도(위도 37 에서 ≈ 89m)씩 떨어진 정류장 4개
    private val stops = (0..3).map { RouteStop("N$it", "정류장$it", null, it + 1, null, 37.0, 127.0 + it * 0.003) }

    private fun bus(ord: Int, lng: Double?, lat: Double? = 37.0, state: Int? = null) =
        BusLocation(null, null, ord, "v", lat, lng, state = state)

    @Test
    fun busStillNearItsStopIsNotDeparted() {
        assertFalse(RouteLive.departed(bus(2, 127.003 + 0.0003), stops[1], stops[2]))   // 약 27m
    }

    @Test
    fun busFarFromItsStopTowardTheNextOneHasDeparted() {
        assertTrue(RouteLive.departed(bus(2, 127.003 + 0.0015), stops[1], stops[2]))
    }

    @Test
    fun busBehindItsStopIsNotDeparted() {
        // 정류장 뒤쪽(오기 전)으로 멀리 있으면 떠난 게 아님
        assertFalse(RouteLive.departed(bus(2, 127.003 - 0.0015), stops[1], stops[2]))
    }

    @Test
    fun busFarBeyondTheNextStopIsBadDataNotDeparted() {
        assertFalse(RouteLive.departed(bus(2, 127.003 + 0.0045), stops[1], stops[2]))
    }

    @Test
    fun withoutCoordinatesOrNextStopItStaysPut() {
        assertFalse(RouteLive.departed(bus(2, null), stops[1], stops[2]))
        assertFalse(RouteLive.departed(bus(4, 127.0099), stops[3], null))
    }

    @Test
    fun gyeonggiStateOverridesGps() {
        assertTrue(RouteLive.departed(bus(2, 127.003, state = 2), stops[1], stops[2]))
        assertFalse(RouteLive.departed(bus(2, 127.003 + 0.0015, state = 1), stops[1], stops[2]))
    }

    @Test
    fun departedBusIsGoneFromItsOwnStopButCountsForTheNext() {
        val p = RouteLive.positions(listOf(bus(2, 127.003 + 0.0015)), stops)
        assertEquals(listOf(1), p.left)
        assertNull(RouteLive.stopsAway(1, p.at, p.left))      // 방금 출발: 이 정류장엔 더 이상 도착이 아님
        assertEquals(1, RouteLive.stopsAway(2, p.at, p.left)) // 다음 정류장 기준 1정류장 전
        assertEquals(2, RouteLive.stopsAway(3, p.at, p.left))
    }

    @Test
    fun anotherBusStillAtTheStopKeepsItArrived() {
        val p = RouteLive.positions(listOf(bus(2, 127.003 + 0.0015), bus(2, 127.003 + 0.0001)), stops)
        assertEquals(0, RouteLive.stopsAway(1, p.at, p.left))
    }
}
