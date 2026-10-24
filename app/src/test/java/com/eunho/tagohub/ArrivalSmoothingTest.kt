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
