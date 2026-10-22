package com.eunho.tagohub

import com.eunho.tagohub.data.Place
import com.eunho.tagohub.data.TrainStationGeo
import com.eunho.tagohub.data.TrainStationGeo.Point
import com.eunho.tagohub.data.TrainStationGeo.Station
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 기차역: 역 목록 파일 읽기, 가까운 역, 정차역 조회 범위(길목) */
class TrainStationGeoTest {
    private val seoul = Place("S", "서울")
    private val daejeon = Place("D", "대전")
    private val busan = Place("B", "부산")
    private val suwon = Place("SW", "수원")        // 서울-부산 길목
    private val gangneung = Place("G", "강릉")     // 길목에서 멀리
    private val noGeo = Place("N", "위치모름")
    private val closed = Place("C", "폐역")

    private fun setUp() = TrainStationGeo.set(
        listOf(
            Station("S", "서울", "서울특별시", Point(37.5547, 126.9707), true),
            Station("D", "대전", "대전광역시", Point(36.3322, 127.4343), true),
            Station("B", "부산", "부산광역시", Point(35.1151, 129.0413), true),
            Station("SW", "수원", "경기도", Point(37.2660, 127.0001), true),
            Station("G", "강릉", "강원특별자치도", Point(37.7640, 128.8997), true),
            Station("C", "폐역", "서울특별시", Point(37.55, 126.97), false),
            Station("N", "위치모름", "", null, true),
        ),
    )

    @After
    fun reset() = TrainStationGeo.set(emptyList())

    @Test
    fun parsesStationLinesAndIgnoresBrokenOnes() {
        val list = TrainStationGeo.parse("NAT1\t서울\t서울특별시\t37.5\t127.0\t1\nbroken\nNAT2\t가남\t경기도\t\t\t0\n\nNAT3\t\t경기도\t1\t2\t1\n")
        assertEquals(listOf("NAT1", "NAT2"), list.map { it.id })
        assertEquals(Point(37.5, 127.0), list[0].point)
        assertNull(list[1].point)                 // 위치를 모르는 역은 위치만 비움
        assertTrue(list[0].served && !list[1].served)
    }

    @Test
    fun placesAreTheServedStationsSortedByNameWithRegion() {
        setUp()
        val names = TrainStationGeo.places().map { it.name }
        assertEquals(names.sorted(), names)
        assertTrue("폐역" !in names)            // 운행하지 않는 역은 목록에서 뺌
        assertEquals("경기도", TrainStationGeo.places().first { it.name == "수원" }.sub)
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
