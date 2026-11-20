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
    /** 정류장 사이 길 고치기: 이어지는 두 정류장 사이를 고친 길로 바꿔 끼움 (웹 roadline.js applySegFixes 와 같음) */
    @Test
    fun segFixes_spliceBetweenStops() {
        val line = listOf(LatLng(35.80, 127.10), LatLng(35.80, 127.101), LatLng(35.80, 127.102), LatLng(35.80, 127.103), LatLng(35.80, 127.104))
        val fix = com.eunho.tagohub.data.SegFixes.Fix("TSB1", "TSB2", listOf(LatLng(35.8001, 127.101), LatLng(35.801, 127.1015), LatLng(35.8001, 127.1022)))
        val stops = listOf("TSB0" to LatLng(35.80, 127.10), "TSB1" to LatLng(35.8001, 127.101), "TSB2" to LatLng(35.8001, 127.102), "TSB9" to LatLng(35.80, 127.104))
        val out = com.eunho.tagohub.data.SegFixes.apply(line, stops, listOf(fix))
        assertEquals(listOf(line[0], line[1]) + fix.path + listOf(line[2], line[3], line[4]), out)
        assertEquals(line, com.eunho.tagohub.data.SegFixes.apply(line, listOf(stops[2], stops[1]), listOf(fix)))
        // 병무청 회차 길이 표에 있음
        assertTrue(com.eunho.tagohub.data.SegFixes.all.any { it.from == "TSB305000104" && it.to == "TSB305000070" && it.path.size > 20 })
    }

    /** 회차 정류장(앞뒤 정류장이 거의 같은 자리)에서 조각을 끊어 가는 길·돌아오는 길을 따로 물음 (서버 shape.js chunks 와 같음) */
    @Test
    fun chunks_splitAtTurnaround() {
        val a = LatLng(35.6377, 127.1551); val turn = LatLng(35.6373, 127.1360); val b = LatLng(35.6378, 127.1550)
        val stops = listOf(LatLng(35.6300, 127.1550), a, turn, b, LatLng(35.6442, 127.1549), LatLng(35.6537, 127.1548))
        assertTrue(Osrm.turnsAt(stops, 2))
        val parts = Osrm.chunks(stops, 6)
        assertEquals(listOf(3, 4), parts.map { it.size })
        assertEquals(turn, parts[1][0])
    }

    /** 골목 들르기는 빼고, 회차·회전교차로·터미널 들르기·정류장 없는 굽은 길은 남김 (웹 test/roadline.test.js 와 같은 경우) */
    @Test
    fun despike_removesSideTripsKeepsTurnaroundsAndRoundabouts() {
        val a = LatLng(35.80, 127.10); val b = LatLng(35.80, 127.1010); val c = LatLng(35.80, 127.1020); val e = LatLng(35.80, 127.1035)
        val l1 = LatLng(35.8003, 127.1010); val l2 = LatLng(35.8003, 127.1013); val l3 = LatLng(35.8000, 127.1013)
        val tip = LatLng(35.8004, 127.1010)
        // 골목 들르기: 같은 자리로 나와 가던 방향으로 계속, 또는 교차로에서 꺾여 나감
        assertEquals(listOf(a, b, c), Shapes.despike(listOf(a, b, tip, b, c)))
        assertEquals(listOf(a, b, c), Shapes.despike(listOf(a, b, l1, l2, l3, b, c)))
        val south = LatLng(35.7990, 127.1010)
        assertEquals(listOf(a, b, south), Shapes.despike(listOf(a, b, l1, l2, l3, b, south)))
        // 회차: 왔던 쪽으로 되돌아감
        val back = LatLng(35.79995, 127.1000)
        val loop = listOf(a, b, l1, l2, l3, b, back)
        assertEquals(loop, Shapes.despike(loop))
        val stub = listOf(a, b, tip, b, back)
        assertEquals(stub, Shapes.despike(stub))
        // 회전교차로: b 에서 들어가 시계 반대 방향으로 270도 돌고 북쪽으로 나감
        val k = 111_320.0 * Math.cos(Math.toRadians(35.8)); val cLng = 127.1010 + 15 / k
        val ring = (180..450 step 15).map { d -> val t = Math.toRadians(d.toDouble()); LatLng(35.80 + 15 * Math.sin(t) / 110_540.0, cLng + 15 * Math.cos(t) / k) }
        val round = listOf(a) + ring + LatLng(ring.last().lat + 0.0004, ring.last().lng)
        assertEquals(round, Shapes.despike(round))
        // 큰길 옆에 찍힌 정류장 때문에 옆길로 빠졌다 들어온 우회는 곧게, 정류장 없거나 터미널이면 그대로
        val s1 = LatLng(35.8005, 127.1012); val s2 = LatLng(35.8005, 127.1020); val back2 = LatLng(35.80, 127.1022)
        val detour = listOf(a, b, s1, s2, back2, e)
        assertEquals(listOf(a, b, back2, e), Shapes.despike(detour, listOf(LatLng(35.8004, 127.1015) to "풍남문")))
        assertEquals(detour, Shapes.despike(detour))
        assertEquals(detour, Shapes.despike(detour, listOf(LatLng(35.8004, 127.1015) to "강진터미널")))
        val lp = listOf(a, b, l1, l2, l3, b, c)
        assertEquals(lp, Shapes.despike(lp, listOf(LatLng(35.8003, 127.1011) to "임실터미널")))
        // 노선 정류장 차례가 되돌아가는 곳(길 건너 같은 정류장에 이어 섬)은 꺾어 나가도 회차로 남김
        val turnOff = listOf(a, b, l1, l2, l3, b, south)
        val pair = listOf(LatLng(35.79, 127.09) to "앞", LatLng(35.8003, 127.10105) to "기린대로 병무청", LatLng(35.8003, 127.10125) to "기린대로 병무청", LatLng(35.79, 127.11) to "뒤")
        assertEquals(turnOff, Shapes.despike(turnOff, pair))
    }

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
    fun project_findsPositionAlongPath() {
        val path = (0..10).map { LatLng(35.80, 127.10 + it * 0.001) } // 동쪽 약 900m
        val (f, d) = Shapes.project(path, LatLng(35.8001, 127.105))     // 가운데, 11m 북쪽
        assertTrue(abs(f - 0.5) < 0.01)
        assertTrue(d in 8.0..14.0)
        val (f2, _) = Shapes.project(path, LatLng(35.80, 127.20))       // 끝을 한참 지난 점
        assertEquals(1.0, f2, 1e-9)
    }

    // 곧은 길 펴기 (웹 roadline.test.js 와 같은 경우): x m 동쪽, y m 북쪽
    private val kx = 111_320.0 * Math.cos(Math.toRadians(35.8))
    private fun at(x: Double, y: Double) = LatLng(35.8 + y / 110_540.0, 127.1 + x / kx)
    private fun yOf(p: LatLng) = (p.lat - 35.8) * 110_540.0

    @Test
    fun straighten_flattensJaggedBumpOnStraightRoad() {
        val bump = listOf(0.0, 6.0, 12.0, 5.0, 11.0, 4.0, 12.0)
        val line = (0..40).map { n ->
            val x = n * 15.0
            val y = if (x in 250.0..340.0) bump.getOrElse(Math.round((x - 250) / 15).toInt()) { 0.0 } else if (n % 2 == 1) 2.0 else -1.0
            at(x, y)
        }
        val s = Shapes.straighten(line)
        assertTrue("점 ${s.size}개", s.size < line.size / 3)
        s.drop(1).dropLast(1).forEach { assertTrue("y ${yOf(it)}", abs(yOf(it)) <= 3.0) }
    }

    @Test
    fun straighten_keepsRealBendsRoundaboutsAndTerminals() {
        val bend = listOf(at(-100.0, 0.0), at(0.0, 0.0), at(70.0, 8.0), at(140.0, 15.0), at(210.0, 8.0), at(280.0, 0.0), at(380.0, 0.0))
        assertEquals(bend, Shapes.straighten(bend))
        val ring = mutableListOf(at(-100.0, 0.0), at(-50.0, 0.0), at(-12.0, 0.0))
        for (d in 180 downTo 0 step 20) ring += at(12 * Math.cos(Math.toRadians(d.toDouble())), -12 * Math.sin(Math.toRadians(d.toDouble())))
        ring += listOf(at(50.0, 0.0), at(100.0, 0.0))
        assertTrue(Shapes.straighten(ring).any { yOf(it) < -8 })
        val hump = listOf(0.0, 8.0, 12.0, 8.0, 0.0)
        val visit = (0..26).map { n -> val x = n * 15.0; at(x, if (x in 180.0..240.0) hump[Math.round((x - 180) / 15).toInt()] else 0.0) }
        assertTrue(Shapes.straighten(visit, listOf(at(210.0, 18.0) to "임실터미널")).any { yOf(it) > 10 })
    }
}
