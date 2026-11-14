package com.eunho.tagohub

import com.eunho.tagohub.data.EditPath
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.RouteStop
import com.eunho.tagohub.data.RouteTravel
import org.junit.Assert.assertEquals
import org.junit.Test

class MapEditsTest {
    private val a = LatLng(35.8000, 127.1000)
    private val b = LatLng(35.8000, 127.1100)   // 동쪽 약 900m
    private val c = LatLng(35.8100, 127.1100)

    @Test fun simplifyKeepsCornersDropsStraightPoints() {
        val mid = LatLng(35.8000, 127.1050)  // a-b 직선 위
        assertEquals(listOf(a, b, c), EditPath.simplify(listOf(a, mid, b, c), 6.0))
    }

    @Test fun insertGoesBetweenNearestSegment() {
        val p = LatLng(35.8002, 127.1050)    // a-b 선 바로 옆 (약 22m)
        val (out, at) = EditPath.insertNear(listOf(a, b, c), p, 80.0)
        assertEquals(1, at)
        assertEquals(listOf(a, p, b, c), out)
        // 선에서 멀면 가까운 끝에 이음
        val far = LatLng(35.7900, 127.0990)
        assertEquals(0, EditPath.insertNear(listOf(a, b, c), far, 80.0).second)
    }

    @Test fun nearestVertexWithinFinger() {
        assertEquals(1, EditPath.nearestVertex(listOf(a, b, c), LatLng(35.8001, 127.1101), 30.0))
        assertEquals(-1, EditPath.nearestVertex(listOf(a, b, c), LatLng(35.8050, 127.1050), 30.0))
    }

    /** 정류장 시간표 소요시간이 웹(busdata.js offsetMinutes)과 같은 식: 거리 ÷ 300m/분 + 정류장마다 0.3분 (좌표 없는 정류장도 0.3분) */
    @Test fun timetableTravelMatchesWeb() {
        val stops = listOf(
            RouteStop("A", "A", null, 1, null, 35.8, 127.1),
            RouteStop("B", "B", null, 2, null, null, null),
            RouteStop("C", "C", null, 3, null, 35.8, 127.11),
        )
        val f = RouteTravel.timetableFn(stops)
        // 좌표가 빠진 구간은 거리 0 → 정류장 2개 × 0.3분
        assertEquals(0.6, f(1, 3), 1e-9)
        val stops2 = listOf(stops[0], stops[2].copy(ord = 2))
        val m = RouteTravel.webMeters(35.8, 127.1, 35.8, 127.11)
        assertEquals(m / 300.0 + 0.3, RouteTravel.timetableFn(stops2)(1, 2), 1e-9)
    }
}
