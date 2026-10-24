package com.eunho.tagohub

import com.eunho.tagohub.data.ArrivalSmoothing
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.SubwayArrival
import org.junit.Assert.assertEquals
import org.junit.Test
import java.time.LocalDateTime

/** 새로고침해도 남은 시간이 거꾸로 늘어나지 않기 */
class ArrivalSmoothingTest {
    private fun bus(route: String, sec: Int?, stops: Int? = 3) = BusArrival(route, route, null, null, stops, sec)

    @Test
    fun busKeepsTheShownValueWhenServerIsSlightlyHigher() {
        // 120초로 받은 뒤 30초가 흘러 화면은 90초. 새로고침했더니 서버는 105초 → 90초 유지
        val out = ArrivalSmoothing.bus(listOf(bus("A", 120)), 30, listOf(bus("A", 105)))
        assertEquals(90, out[0].arrSeconds)
    }

    @Test
    fun busFollowsServerWhenLowerOrMuchHigherOrGoesBack() {
        assertEquals(60, ArrivalSmoothing.bus(listOf(bus("A", 120)), 30, listOf(bus("A", 60)))[0].arrSeconds)           // 더 가까워짐
        assertEquals(400, ArrivalSmoothing.bus(listOf(bus("A", 120)), 30, listOf(bus("A", 400)))[0].arrSeconds)         // 많이 늘면 다른 버스·지연
        assertEquals(105, ArrivalSmoothing.bus(listOf(bus("A", 120, 3)), 30, listOf(bus("A", 105, 5)))[0].arrSeconds)   // 정류장 수가 늘면 뒤로 간 것
        assertEquals(500, ArrivalSmoothing.bus(listOf(bus("A", 20)), 30, listOf(bus("A", 500)))[0].arrSeconds)          // 이미 도착한 버스 다음 차
    }

    @Test
    fun busPairsFirstWithFirstPerRoute() {
        val prev = listOf(bus("A", 100), bus("A", 700), bus("B", 50))
        val next = listOf(bus("A", 780), bus("A", 110), bus("B", 49))
        val out = ArrivalSmoothing.bus(prev, 10, next)
        assertEquals(listOf(690, 90, 40), out.map { it.arrSeconds })   // 700초였던 둘째 버스는 690 유지, B 는 50-10=40 이 서버 49 보다 작아 40 유지
    }

    @Test
    fun subwayKeepsShownMinutesAndResetsReceivedTime() {
        val t0 = LocalDateTime.of(2026, 10, 24, 12, 0, 0)
        fun a(sec: Int, at: LocalDateTime) = SubwayArrival("1002", "내선", "2호선", sec, "", "강남", 1, "성수", false, false, "1", at, "2201")
        val out = ArrivalSmoothing.subway(listOf(a(120, t0)), listOf(a(115, t0.plusSeconds(30))), t0.plusSeconds(30))
        assertEquals(90, out[0].seconds)
        assertEquals(t0.plusSeconds(30), out[0].received)
    }
}

class PassedBusTest {
    private fun a(sec: Int?, stops: Int?) = BusArrival("A", "A", null, null, stops, sec)

    @Test
    fun removesOnlyNearBusesWhoseTimeRanOut() {
        val p = com.eunho.tagohub.data.PassedBus
        assertEquals(false, p.passed(a(45, 1), 44))      // 아직 1초 남음
        assertEquals(false, p.passed(a(45, 1), 47))      // 0초 지났지만 여유 3초 안
        assertEquals(true, p.passed(a(45, 1), 49))       // 지나감
        assertEquals(true, p.passed(a(20, 0), 30))       // 이번 정류장 차례였던 버스
        assertEquals(false, p.passed(a(45, 5), 100))     // 멀리 있던 버스는 서버가 다시 알려줄 때까지 둠
        assertEquals(false, p.passed(a(null, 1), 100))
    }
}

class PassedByPositionTest {
    private val p = com.eunho.tagohub.data.PassedByPosition

    @Test
    fun passedOnlyWhenABusJustLeftAndNoneIsComing() {
        assertEquals(true, p.passed(listOf(20), listOf(21, 5)))      // 방금 지나감, 오는 버스 없음
        assertEquals(false, p.passed(listOf(20), listOf(21, 18)))    // 뒤따라 오는 버스가 있음
        assertEquals(false, p.passed(listOf(20), listOf(20)))        // 지금 정류장에 있음
        assertEquals(false, p.passed(listOf(20), listOf(5)))         // 멀리 있음 → 모름
        assertEquals(false, p.passed(listOf(20), emptyList()))       // 위치 없음 → 모름
        assertEquals(false, p.passed(listOf(3, 40), listOf(41)))     // 순환 노선: 정류장이 두 번 나와 판단 안 함
    }
}

class RailMatchTest {
    @Test
    fun matchesSameNameNearbyOnly() {
        val g = com.eunho.tagohub.data.TrainStationGeo
        g.set(listOf(
            com.eunho.tagohub.data.TrainStationGeo.Station("a", "서울", "서울특별시", com.eunho.tagohub.data.TrainStationGeo.Point(37.5547, 126.9708), true),
            com.eunho.tagohub.data.TrainStationGeo.Station("b", "광운대", "서울특별시", com.eunho.tagohub.data.TrainStationGeo.Point(37.6238, 127.0617), false),
            com.eunho.tagohub.data.TrainStationGeo.Station("c", "중앙", "경기도", com.eunho.tagohub.data.TrainStationGeo.Point(37.3, 126.9), true),
        ))
        assertEquals("a", g.matchNear("서울역", 37.5558, 126.9723)?.id)    // 서울역 지하철(약 200m)
        assertEquals(null, g.matchNear("광운대", 37.6238, 127.0617))        // 열차가 서지 않는 역은 제외
        assertEquals(null, g.matchNear("중앙", 35.1, 129.0))                // 이름만 같은 먼 역
    }
}

class StationPoiTest {
    @Test
    fun stationNamesWinOverSameNamedBusStops() {
        val p = com.eunho.tagohub.data.PoiMatch
        assertEquals(true, p.looksLikeStation("강남역"))
        assertEquals(true, p.looksLikeStation("강남역 2호선"))
        assertEquals(true, p.looksLikeStation("신촌역 2번출구"))
        assertEquals(true, p.looksLikeStation("서울역(1호선)"))
        assertEquals(false, p.looksLikeStation("강남역.강남역사거리"))
        assertEquals(false, p.looksLikeStation("서울역 롯데마트"))
        assertEquals(false, p.looksLikeStation("강남역 정류장"))
    }
}
