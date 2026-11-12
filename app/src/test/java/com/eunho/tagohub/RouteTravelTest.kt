package com.eunho.tagohub

import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.data.RouteGeom
import com.eunho.tagohub.data.RouteLive
import com.eunho.tagohub.data.RouteStop
import com.eunho.tagohub.data.RouteTravel
import com.eunho.tagohub.data.SegmentTimes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** 구간 실측 통행시간을 시간표·지도 움직임에 쓰는 계산 */
class RouteTravelTest {
    private val nowSec = System.currentTimeMillis() / 1000

    /** 1 → 2 → 3 으로 이어지는 노선 (실측은 구간 단위로 쌓인다) */
    private val threeStops = listOf(
        RouteStop("a", "A", null, 1, null, 37.0, 127.0),
        RouteStop("b", "B", null, 2, null, 37.001, 127.0),
        RouteStop("c", "C", null, 3, null, 37.002, 127.0),
    )

    /** [routeId] 의 구간 기록만 있는 임시 저장소를 만들고, 같은 노선의 이전 캐시가 섞이지 않게 초기화한다 */
    private fun setup(routeId: String, lines: List<String>) {
        val root = createTempDir()
        SegmentTimes.reset()
        SegmentTimes.init(root)
        File(root, "seg_times").resolve("$routeId.tsv").writeText(lines.joinToString("") { "$it\n" })
    }

    @Test
    fun measuredSegmentBeatsDistanceEstimate() {
        setup("MEAS", listOf("1\t2\t120\t$nowSec"))
        // 실측 2분이 있으면 거리 어림(9분) 대신 그것을 쓴다
        assertEquals(2.0, RouteTravel.minutes("MEAS", 1, 2) { _, _ -> 9.0 }, 0.001)
        assertTrue(RouteTravel.usesMeasured("MEAS", threeStops, 1, 2))
    }

    @Test
    fun fallsBackWhenNoRecord() {
        setup("NONE", listOf("5\t6\t120\t$nowSec"))
        assertEquals(9.0, RouteTravel.minutes("NONE", 1, 2) { _, _ -> 9.0 }, 0.001)
        assertEquals(false, RouteTravel.usesMeasured("NONE", threeStops, 1, 2))
    }

    @Test
    fun profileUsesMeasuredOnlyWhereRecorded() {
        setup("PART", listOf("2\t3\t120\t$nowSec"))
        val f = RouteTravel.minutesFn("PART", threeStops) { _, _ -> 1.0 }
        // 1→2 는 거리 어림 1분, 2→3 은 실측 2분
        assertEquals(3.0, f(1, 3), 0.001)
        assertEquals(1.0, f(1, 2), 0.001)
        assertEquals(2.0, f(2, 3), 0.001)
        assertTrue(RouteTravel.usesMeasured("PART", threeStops, 1, 3))
    }

    @Test
    fun minutesFunctionAsksEachSegmentOnce() {
        setup("MEMO", listOf("5\t6\t120\t$nowSec"))
        var calls = 0
        val f = RouteTravel.minutesFn("MEMO", threeStops) { _, _ -> calls++; 1.5 }
        // 운행이 수백 번이어도 같은 구간은 한 번만 계산한다
        repeat(200) { assertEquals(3.0, f(1, 3), 0.001) }
        assertEquals(2, calls)
    }

    @Test
    fun travelSecondsPreferMeasured() {
        setup("SEC", listOf("1\t2\t90\t$nowSec"))
        assertEquals(90.0, RouteTravel.travelSec("SEC", 1, 2) { _, _ -> 10.0 }, 0.001)
    }

    @Test
    fun progressWaitsAtStopThenReachesNext() {
        // 정차 25초 동안은 정류장, 그 뒤로는 다음 정류장까지 시간에 비례해 움직이고, 닿으면 1에서 기다린다
        assertEquals(0.0, RouteLive.progress(0L, 20_000L, 60.0), 1e-9)
        assertEquals(0.5, RouteLive.progress(0L, 55_000L, 60.0), 1e-9)
        assertEquals(1.0, RouteLive.progress(0L, 600_000L, 60.0), 1e-9)
        assertEquals(0.0, RouteLive.progress(null, 600_000L, 60.0), 1e-9)
    }

    @Test
    fun segmentFollowsRouteShapeBetweenStops() {
        val stops = listOf(
            RouteStop("a", "A", null, 1, null, 37.0, 127.0),
            RouteStop("b", "B", null, 2, null, 37.01, 127.0),
        )
        val shape = listOf(LatLng(37.0, 127.0), LatLng(37.005, 127.001), LatLng(37.01, 127.0))
        val seg = RouteGeom(shape, stops).segment(1, 2)!!
        assertEquals(37.0, seg.first().lat, 1e-9)
        assertEquals(37.01, seg.last().lat, 1e-9)
        // 정류장을 직선으로 잇지 않고 노선 선형의 꺾인 점을 지난다
        assertTrue(seg.any { it.lng > 127.0005 })
    }

    @Test
    fun segmentIsStraightWhenNoShape() {
        val stops = listOf(
            RouteStop("a", "A", null, 1, null, 37.0, 127.0),
            RouteStop("b", "B", null, 2, null, 37.01, 127.0),
        )
        val seg = RouteGeom(null, stops).segment(1, 2)!!
        assertEquals(2, seg.size)
        assertEquals(37.0, seg.first().lat, 1e-9)
        assertEquals(37.01, seg.last().lat, 1e-9)
    }
}
