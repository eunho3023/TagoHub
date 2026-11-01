package com.eunho.tagohub

import com.eunho.tagohub.data.ExMapLayers
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.Shapes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HighwayAndShapeTest {
    @Test
    fun burstKeepsOnlyRowsWithCoordinatesAndDropsPromotions() {
        val rows = listOf(
            // altitude 가 경도, latitude 가 위도
            mapOf("accType" to "작업", "roadNM" to "남해선", "smsText" to "** 2차로 작업 중 **", "accPointNM" to "김해졸음쉼터(153K)", "startEndTypeCode" to "부산방향", "accProcessNM" to "진행", "latitude" to "35.220105", "altitude" to "128.862517", "nosunNM" to "0100", "seriesNM" to "21", "accDate" to "2026.06.16", "accHour" to "19:49:29"),
            mapOf("accType" to "작업", "smsText" to "좌표 없음"),
            mapOf("accType" to "이벤트/홍보", "smsText" to "상황실 문의", "latitude" to "35.2", "altitude" to "128.8"),
        )
        val f = ExMapLayers.burst(rows).single()
        assertEquals(35.220105, f.lat, 1e-9)
        assertEquals(128.862517, f.lng, 1e-9)
        assertEquals("작업 · 남해선", f.title)
        assertTrue("2차로 작업 중" in f.details && "**" !in f.details)
        assertTrue("부산방향" in f.details)
    }

    @Test
    fun safeMergesRowsOfTheSameIncidentAtItsStartPoint() {
        fun row(rx: String) = mapOf("incId" to "I1", "contentMsg" to "서상부근 작업중 주의요망", "startX" to "127.69912", "startY" to "35.64965", "rX" to rx, "rY" to "35.5", "routeName" to "통영대전선", "updownDiv" to "E", "analDtmc" to "20261025233000")
        val f = ExMapLayers.safe(listOf(row("127.7"), row("127.8"), mapOf("contentMsg" to "id 없음"))).single()
        assertEquals("서상부근 작업중 주의요망", f.title)
        assertEquals(35.64965, f.lat, 1e-9)
        assertEquals(127.69912, f.lng, 1e-9)
        assertTrue("종점 방향" in f.details && "10.25 23:30 기준" in f.details)
    }

    @Test
    fun coverageCountsStopsNearTheLine() {
        val line = listOf(LatLng(37.0, 127.0), LatLng(37.0, 127.01))
        val stops = listOf(LatLng(37.0001, 127.002), LatLng(37.0001, 127.008), LatLng(37.01, 127.005))   // 마지막은 1km 떨어짐
        assertEquals(2.0 / 3.0, Shapes.coverage(line, stops, 100.0), 1e-9)
        assertEquals(0.0, Shapes.coverage(emptyList(), stops, 100.0), 0.0)
    }

    @Test
    fun roomBelowPutsTheRouteAboveTheCoveringPanel() {
        val pts = listOf(LatLng(37.50, 127.00), LatLng(37.52, 127.02))
        val out = Shapes.withRoomBelow(pts, 0.7)
        assertEquals(3, out.size)
        // 남북 폭 0.02 의 0.7/0.3 배만큼 남쪽 → 위쪽 30% 에 노선이 들어옴
        assertEquals(37.50 - 0.02 * 0.7 / 0.3, out.last().lat, 1e-9)
        assertEquals(127.01, out.last().lng, 1e-9)
        assertEquals(pts, Shapes.withRoomBelow(pts, 0.0))
        assertEquals(pts.take(1), Shapes.withRoomBelow(pts.take(1), 0.7))
    }

    @Test
    fun smoothingIsOnlyOptionalForSparseLines() {
        // 정류장 세 개를 잇는 꺾인 선: 둥글게 하면 가운데 정류장(꼭짓점)을 지나지 않게 됨 → 성긴 선에는 쓰지 않음
        val stops = listOf(LatLng(37.50, 127.00), LatLng(37.51, 127.00), LatLng(37.51, 127.01))
        assertTrue(Shapes.smooth(stops).none { it == stops[1] })
    }
}
