package com.eunho.tagohub

import com.eunho.tagohub.data.DriveRoad
import com.eunho.tagohub.data.KakaoDrive
import com.eunho.tagohub.data.LatLng
import com.eunho.tagohub.ui.map.trafficColor
import com.eunho.tagohub.ui.map.trafficSegments
import com.eunho.tagohub.ui.map.trafficSummary
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class DriveSummaryTest {
    private fun road(state: Int, meters: Int, vararg p: Pair<Double, Double>) =
        DriveRoad("도로", meters, meters / 20, state, p.map { LatLng(it.first, it.second) })

    @Test
    fun sameColorRoadsAreMergedAndJoinedWithoutGaps() {
        val roads = listOf(
            road(4, 1000, 37.0 to 127.0, 37.0 to 127.01),
            road(4, 1000, 37.0 to 127.01, 37.0 to 127.02),
            road(1, 500, 37.0 to 127.02, 37.0 to 127.03),
            road(4, 800, 37.0 to 127.03, 37.0 to 127.04),
        )
        val segs = trafficSegments(roads)
        assertEquals(listOf(trafficColor(4), trafficColor(1), trafficColor(4)), segs.map { it.color })
        // 색이 바뀌는 곳에서 이전 선의 끝점에서 이어짐
        assertEquals(segs[0].points.last(), segs[1].points.first())
        assertEquals(segs[1].points.last(), segs[2].points.first())
    }

    @Test
    fun summaryNamesCongestionAndSlowDistances() {
        assertEquals("정체 없음", trafficSummary(listOf(road(4, 5000, 37.0 to 127.0, 37.0 to 127.1))))
        val s = trafficSummary(listOf(road(1, 3200, 37.0 to 127.0, 37.0 to 127.1), road(3, 12100, 37.0 to 127.1, 37.0 to 127.2), road(4, 9000, 37.0 to 127.2, 37.0 to 127.3)))
        assertEquals("정체 3.2km · 서행 12.1km", s)
        assertNull(trafficSummary(emptyList()))
    }

    @Test
    fun realRouteGivesFewerColoredLinesThanRoads() {
        val f = listOf("src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json", "app/src/test/resources/fixtures/kakao_drive_gangnam_daejeon.json")
            .map { File(it) }.first { it.exists() }
        val route = KakaoDrive.parse(f.readText(Charsets.UTF_8)).second!!
        val segs = trafficSegments(route.roads)
        assertTrue("선 ${segs.size} / 도로 ${route.roads.size}", segs.size in 1 until route.roads.size)
        assertNotNull(trafficSummary(route.roads))
    }
}
