package com.eunho.tagohub

import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.data.RouteSpeed
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test

class RouteSpeedTest {
    private fun bus(ord: Int, v: String = "1234") = BusLocation(null, null, ord, v, null, null)
    private val usual = { a: Int, b: Int -> (b - a) * 2.0 } // 정류장 하나에 평소 2분
    private val min = 60_000L

    @Before
    fun clear() = RouteSpeed.reset()

    @Test
    fun unknownRouteIsNotAdjusted() {
        assertEquals(1.0, RouteSpeed.factor("R"), 0.0)
    }

    @Test
    fun slowTripRaisesTheFactor() {
        RouteSpeed.observe("R", listOf(bus(1)), 0, usual)          // 처음 본 위치: 도착 시각을 모름
        RouteSpeed.observe("R", listOf(bus(3)), 4 * min, usual)    // 첫 이동: 기준 시각만 잡음
        assertEquals(1.0, RouteSpeed.factor("R", 4 * min), 0.0)
        RouteSpeed.observe("R", listOf(bus(6)), 4 * min + 12 * min, usual) // 3정류장 평소 6분 → 12분 걸림
        assertEquals(2.0, RouteSpeed.factor("R", 16 * min), 0.001)
    }

    @Test
    fun factorIsClampedAndExpires() {
        RouteSpeed.observe("R", listOf(bus(1)), 0, usual)
        RouteSpeed.observe("R", listOf(bus(2)), min, usual)
        RouteSpeed.observe("R", listOf(bus(5)), 11 * min, usual)   // 평소 6분 → 10분(1.67)
        assertEquals(10.0 / 6, RouteSpeed.factor("R", 11 * min), 0.001)
        assertEquals(1.0, RouteSpeed.factor("R", 40 * min), 0.0)   // 20분 넘게 새 관측이 없으면 버림
    }

    @Test
    fun shortHopsAreTooNoisyToUse() {
        RouteSpeed.observe("R", listOf(bus(1)), 0, usual)
        RouteSpeed.observe("R", listOf(bus(2)), min, usual)
        RouteSpeed.observe("R", listOf(bus(3)), 5 * min, usual)    // 평소 2분짜리 구간은 버림
        assertEquals(1.0, RouteSpeed.factor("R", 5 * min), 0.0)
    }
}
