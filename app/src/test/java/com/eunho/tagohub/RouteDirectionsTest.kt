package com.eunho.tagohub

import com.eunho.tagohub.core.JeonjuXml
import com.eunho.tagohub.data.JeonjuMappers
import com.eunho.tagohub.data.RouteDirections
import com.eunho.tagohub.data.RouteStop
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class RouteDirectionsTest {
    private fun st(id: String, ord: Int, lat: Double, lng: Double, ud: String? = null, name: String = id) =
        RouteStop(id, name, null, ord, ud, lat, lng)

    @Test
    fun upDownCodes_splitWhereCodeChanges() {
        val stops = listOf(st("A", 1, 35.80, 127.10, "0"), st("B", 2, 35.80, 127.11, "0"), st("C", 3, 35.80, 127.12, "0"),
            st("C'", 4, 35.8001, 127.12, "1"), st("B'", 5, 35.8001, 127.11, "1"), st("A'", 6, 35.8001, 127.10, "1"))
        val t = RouteDirections.turnIndex(stops)
        assertEquals(2, t)                                   // C 가 첫 방향의 끝
        assertEquals(0..2, RouteDirections.range(6, t, 0))
        assertEquals(2..5, RouteDirections.range(6, t, 1))
        assertEquals(1, RouteDirections.dirOf(stops, t, "B'"))
        assertEquals("C", RouteDirections.label(stops, t, 0))
    }

    @Test
    fun loopWithoutCodes_notSplit_showAll() {
        // 동쪽 끝까지 갔다가 돌아오는 순환이라도 회차 표시(방향 코드)가 없으면 추측하지 않음 → 방면 없이 전체
        val stops = listOf(st("S", 1, 35.80, 127.10, name = "기점"), st("a", 2, 35.80, 127.11), st("FAR", 3, 35.80, 127.13),
            st("b", 4, 35.8001, 127.11), st("S2", 5, 35.8001, 127.10, name = "기점"))
        assertEquals(null, RouteDirections.turnIndex(stops))
        assertEquals(0 until 5, RouteDirections.range(stops.size, null, 0))   // 목록 = 전체
        // 같은 노선에 회차 표시가 있으면 그 자리에서 나눔
        val coded = stops.mapIndexed { i, s -> s.copy(upDown = if (i <= 2) "0" else "1") }
        assertEquals(2, RouteDirections.turnIndex(coded))
    }

    @Test
    fun oneWayList_notSplit() {
        val stops = (0..5).map { st("s$it", it + 1, 35.80, 127.10 + it * 0.01) }
        assertEquals(null, RouteDirections.turnIndex(stops))
        assertEquals(0 until 6, RouteDirections.range(6, null, 0))
    }

    /** 실제 전주 노선(305001271): 전주대학교 출발 → 전주대학교(길 건너편) 도착하는 순환 */
    @Test
    fun realJeonjuLoop_withoutTurnMark_showAll() {
        val rows = JeonjuXml.parse(javaClass.getResource("/fixtures/jeonju_positions.xml")!!.readText(Charsets.UTF_8))
        val stops = JeonjuMappers.routeStops(rows) { null }
        // 좌표가 없어도 첫·끝 정류장 이름이 같으면 순환으로 본다
        assertEquals("전주대학교", stops.first().name)
        assertEquals("전주대학교", stops.last().name)
        // 전주시 노선 정류장 응답에는 회차 표시가 없음 → 나누지 않고 전체 (가운데·가장 먼 정류장 추측 안 함)
        if (stops.any { it.upDown.isNullOrBlank() }) assertEquals(null, RouteDirections.turnIndex(stops))
        else assertTrue(RouteDirections.turnIndex(stops) != null)
    }

    /** 편도 노선(첫·끝 정류장이 멀고 이름도 다름)은 방향을 나누지 않음 → 방면 버튼 없음 */
    @Test
    fun oneWayWithCodes_notSplit() {
        // 방향 코드가 바뀌어도 첫·끝이 다른 곳이면 회차 노선이 아님
        val stops = listOf(st("A", 1, 35.80, 127.10, "0"), st("B", 2, 35.80, 127.11, "0"),
            st("C", 3, 35.80, 127.12, "1"), st("D", 4, 35.80, 127.13, "1"))
        assertEquals(null, RouteDirections.turnIndex(stops))
        assertEquals(true, RouteDirections.isRoundTrip("전주대캠퍼스", "전주대캠퍼스"))
        assertEquals(false, RouteDirections.isRoundTrip("평화동종점", "전주대학교"))
    }
}
