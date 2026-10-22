package com.eunho.tagohub

import com.eunho.tagohub.data.Place
import com.eunho.tagohub.data.TrainStationGeo
import com.eunho.tagohub.data.TrainStationGeo.Point
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기차역: 위치·운행 여부 파일 읽기, 가까운 역, 정차역 조회 범위(길목) */
class TrainStationGeoTest {
    private val seoul = Place("S", "서울")
    private val daejeon = Place("D", "대전")
    private val busan = Place("B", "부산")
    private val suwon = Place("SW", "수원")        // 서울-부산 길목
    private val gangneung = Place("G", "강릉")     // 길목에서 멀리
    private val noGeo = Place("N", "위치모름")
    private val closed = Place("C", "폐역")

    private fun setUp() = TrainStationGeo.setForTest(
        mapOf(
            "S" to Point(37.5547, 126.9707), "D" to Point(36.3322, 127.4343), "B" to Point(35.1151, 129.0413),
            "SW" to Point(37.2660, 127.0001), "G" to Point(37.7640, 128.8997), "C" to Point(37.55, 126.97),
        ),
        unserved = setOf("C"),
    )

    @After
    fun reset() = TrainStationGeo.setForTest(emptyMap(), emptySet())

    @Test
    fun parsesPointsAndIdsAndIgnoresBrokenLines() {
        val p = TrainStationGeo.parsePoints("NAT1\t37.5\t127.0\n\nNAT2\tabc\t1\nNAT3\t35.1\t129.0\n")
        assertEquals(setOf("NAT1", "NAT3"), p.keys)
        assertEquals(37.5, p["NAT1"]!!.lat, 0.0)
        assertEquals(setOf("NAT9", "NAT8"), TrainStationGeo.parseIds("NAT9\t검암\n\nNAT8\n"))
    }

    @Test
    fun nearestSkipsClosedStationsAndStationsWithoutPosition() {
        setUp()
        // 서울역 바로 옆에 있어도 폐역은 빼고, 위치를 모르는 역은 후보가 아님
        val r = TrainStationGeo.nearest(listOf(seoul, closed, noGeo, daejeon, busan, suwon), 37.5547, 126.9707, 2)
        assertEquals(listOf("서울", "수원"), r.map { it.first.name })
        assertTrue(r[0].second < r[1].second)
    }

    @Test
    fun corridorKeepsStationsAlongTheLineAndUnknownPositionsButDropsFarOnes() {
        setUp()
        val all = listOf(suwon, daejeon, gangneung, noGeo)
        val r = TrainStationGeo.corridor(all, seoul, busan).map { it.name }
        assertTrue("수원" in r && "대전" in r)       // 서울-부산 선 위
        assertTrue("위치모름" in r)                   // 위치를 모르면 남김
        assertTrue("강릉" !in r)                      // 선에서 멀리 떨어짐
    }

    @Test
    fun corridorFallsBackToEverythingWhenAnEndpointHasNoPosition() {
        setUp()
        val all = listOf(suwon, gangneung)
        assertEquals(all, TrainStationGeo.corridor(all, noGeo, busan))
    }

    @Test
    fun distanceToSegmentIsZeroOnTheLineAndGrowsAwayFromIt() {
        val a = Point(37.0, 127.0)
        val b = Point(37.0, 128.0)
        assertEquals(0.0, TrainStationGeo.distanceToSegmentKm(Point(37.0, 127.5), a, b), 0.01)
        assertEquals(111.0, TrainStationGeo.distanceToSegmentKm(Point(38.0, 127.5), a, b), 1.0)
        // 선분 끝 너머는 끝점까지 거리
        assertEquals(TrainStationGeo.distanceToSegmentKm(Point(37.0, 129.0), a, b), 88.8, 1.0)
    }
}
