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

class PreferPositionTest {
    @Test
    fun positionWinsWhenTheBusIsAlreadyHereOrMuchNearerThanTheArrivalApiSays() {
        assertEquals(true, RouteLive.preferPosition(1, listOf(0)))          // 이미 와 있는데 API 는 1정류장 전
        assertEquals(true, RouteLive.preferPosition(7, listOf(3)))          // 74번 풍남중: API 7정류장 전, 위치엔 3정류장 앞에 버스
        assertEquals(true, RouteLive.preferPosition(null, listOf(5)))       // API 에 값이 없음
    }

    @Test
    fun aNearerBusIsExtraWhenAnotherBusSitsWhereTheApiSaysItIs() {
        // 2번 노선 풍남중: API 5정류장 전, 위치엔 3정류장·5정류장 전 버스가 있음 → 3정류장 전 버스는 API 가 빠뜨린 것
        assertEquals(true, RouteLive.preferPosition(5, listOf(3, 5, 12)))
        assertEquals(true, RouteLive.preferPosition(5, listOf(3, 6)))
    }

    @Test
    fun smallDifferencesWithoutASecondBusAreTheSameBusCountedDifferently() {
        assertEquals(false, RouteLive.preferPosition(5, listOf(3)))
        assertEquals(false, RouteLive.preferPosition(3, listOf(2)))
        assertEquals(false, RouteLive.preferPosition(5, emptyList()))       // 위치에 오는 버스가 없음
        assertEquals(false, RouteLive.preferPosition(2, listOf(6)))         // 위치가 더 멀면 도착정보
    }

    @Test
    fun allAwayListsEveryBusBehindTheStopNearestFirst() {
        assertEquals(listOf(0, 2, 7), RouteLive.allAway(10, listOf(3, 10, 8, 12)))
    }
}
