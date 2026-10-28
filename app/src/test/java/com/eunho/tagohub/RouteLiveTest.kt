package com.eunho.tagohub

import com.eunho.tagohub.data.RouteLive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RouteLiveTest {
    @Test
    fun countsStopsFromTheNearestBusBehindTheStop() {
        // 버스가 3번·10번 정류장에 있을 때
        assertEquals(7, RouteLive.stopsAway(10, listOf(3)))
        assertEquals(2, RouteLive.stopsAway(12, listOf(3, 10)))     // 더 가까운 버스 기준
        assertEquals(0, RouteLive.stopsAway(10, listOf(3, 10)))     // 정차 중
        assertNull(RouteLive.stopsAway(2, listOf(3, 10)))           // 앞서 지나간 버스만 있으면 오는 버스 없음
        assertNull(RouteLive.stopsAway(5, emptyList()))
    }

    @Test
    fun textMatchesTheArrivalScreenWording() {
        assertEquals("7정류장 전 · 약 9분", RouteLive.text(7, 9))
        assertEquals("3정류장 전", RouteLive.text(3, null))
        assertEquals("1정류장 전 · 약 1분", RouteLive.text(1, 0))
        assertEquals("정류장 도착", RouteLive.text(0, null))
    }
}
