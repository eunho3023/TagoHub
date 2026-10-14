package com.eunho.tagohub

import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.Osrm
import com.eunho.tagohub.data.Polyline
import com.eunho.tagohub.data.Shapes
import com.eunho.tagohub.data.StationIndex
import com.eunho.tagohub.ui.map.BaseMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class ShapeTest {
    private fun fixture(name: String): String =
        javaClass.getResource("/fixtures/$name")!!.readText(Charsets.UTF_8)

    @Test
    fun polyline_decodesGoogleExample() {
        // 구글 문서의 예제 (정밀도 5)
        val p = Polyline.decode("_p~iF~ps|U_ulLnnqC_mqNvxq`@", 5)
        assertEquals(3, p.size)
        assertTrue(abs(p[0].lat - 38.5) < 1e-6 && abs(p[0].lng + 120.2) < 1e-6)
        assertTrue(abs(p[2].lat - 43.252) < 1e-6 && abs(p[2].lng + 126.453) < 1e-6)
    }

    @Test
    fun osrm_parsesRouteGeometry() {
        val g = Osrm.parseGeometry(fixture("osrm_route.json"))
        assertEquals(3, g!!.size)
        assertEquals(null, Osrm.parseGeometry("""{"code":"NoRoute","message":"x"}"""))
    }

    @Test
    fun osrm_chunksOverlapByOne() {
        val stops = (0 until 60).map { LatLng(36.0 + it * 0.001, 127.0) }
        val c = Osrm.chunks(stops, 25)
        assertEquals(listOf(25, 25, 12), c.map { it.size })
        assertEquals(c[0].last(), c[1].first())
        assertEquals(stops.last(), c.last().last())
    }

    @Test
    fun stopIndices_monotonicAndSegment() {
        // 직선 도로 위 꼭짓점 101개, 정류소 3개
        val shape = (0..100).map { LatLng(36.0 + it * 0.0001, 127.0) }
        val stops = listOf(LatLng(36.0, 127.0001), LatLng(36.005, 127.0001), LatLng(36.01, 127.0001))
        val idx = Shapes.stopIndices(shape, stops)
        assertEquals(listOf(0, 50, 100), idx.toList())
        val seg = Shapes.segment(stops, shape, idx, 0)
        assertTrue(seg.size >= 50)
        val mid = Shapes.along(seg, 0.5)
        assertTrue(abs(mid.lat - 36.0025) < 0.0002)
    }

    @Test
    fun subwayNeighbors_line1() {
        val index = StationIndex.parseTsv(fixture("subway_stations.tsv"))
        val cur = index.locate("1001", "종각")!!
        val dest = index.locate("1001", "소요산")!!
        val (prev, next) = index.neighbors("1001", cur, dest)
        assertEquals("종로3가", next?.norm)
        assertEquals("시청", prev?.norm)
        // 반대 방향(인천행)이면 앞뒤가 바뀐다
        val (p2, n2) = index.neighbors("1001", cur, index.locate("1001", "인천"))
        assertEquals("시청", n2?.norm)
        assertEquals("종로3가", p2?.norm)
    }

    /** 지도 종류 = 카카오맵의 기본·위성 (브이월드 백지도·야간지도는 없앰) */
    @Test
    fun baseMaps_kakaoOnly() {
        assertEquals(listOf("기본지도", "위성지도"), BaseMap.entries.map { it.label })
    }

    /**
     * 사용자가 캡처한 TAGO 버스위치 두 번 (전주 3-1번, 2026-10-10 12:40).
     * 정류장 순번이 그대로인 1394·1402 도 좌표가 바뀜 → 정류장 좌표가 아니라 실시간 GPS.
     */
    @Test
    fun tagoBusPositions_areLiveGps() {
        fun m(a: LatLng, b: LatLng) = com.eunho.tagohub.core.Geo.meters(a.lat, a.lng, b.lat, b.lng)
        val bus1394 = m(LatLng(35.832779, 127.167662), LatLng(35.8324, 127.167637))   // 27 → 27
        val bus1402 = m(LatLng(35.81264, 127.118833), LatLng(35.812895, 127.118298))  // 46 → 46
        val bus1333 = m(LatLng(35.824166, 127.098188), LatLng(35.824894, 127.101063)) // 3 → 4
        assertTrue(bus1394 in 30.0..60.0)
        assertTrue(bus1402 in 40.0..70.0)
        assertTrue(bus1333 in 250.0..300.0)
        // 움직임 판단 기준(8m)보다 크면 이동으로 본다
        assertTrue(bus1394 > 8 && bus1402 > 8)
    }

    @Test
    fun roadPath_followsShapeForward() {
        // ㄱ자 도로: 동쪽으로 갔다가 북쪽으로
        val shape = (0..10).map { LatLng(35.80, 127.10 + it * 0.001) } + (1..10).map { LatLng(35.80 + it * 0.001, 127.11) }
        val a = LatLng(35.8001, 127.103)   // 동쪽 구간 위
        val b = LatLng(35.805, 127.1101)   // 북쪽 구간 위
        val p = Shapes.roadPath(a, b, shape)
        assertTrue(p.size > 5)                                    // 모서리를 돌아서
        assertTrue(p.any { abs(it.lat - 35.80) < 1e-9 && abs(it.lng - 127.11) < 1e-9 })
        // 뒤로 가는 경우는 직선
        assertEquals(2, Shapes.roadPath(b, a, shape).size)
        // 선형이 없으면 직선
        assertEquals(2, Shapes.roadPath(a, b, null).size)
    }

    @Test
    fun project_findsPositionAlongPath() {
        val path = (0..10).map { LatLng(35.80, 127.10 + it * 0.001) } // 동쪽 약 900m
        val (f, d) = Shapes.project(path, LatLng(35.8001, 127.105))     // 가운데, 11m 북쪽
        assertTrue(abs(f - 0.5) < 0.01)
        assertTrue(d in 8.0..14.0)
        val (f2, _) = Shapes.project(path, LatLng(35.80, 127.20))       // 끝을 한참 지난 점
        assertEquals(1.0, f2, 1e-9)
    }
}
