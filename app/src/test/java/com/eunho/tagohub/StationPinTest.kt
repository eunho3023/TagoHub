package com.eunho.tagohub

import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.StationIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class StationPinTest {
    private fun st(code: String, line: String, lat: Double, lng: Double) = MetroStation(code, "당산", line, lat, lng, "x")

    // 당산: 2호선과 9호선 역이 약 180m 떨어진 환승역
    private val group = StationIndex(listOf(st("a", "2호선", 37.5342, 126.9026), st("b", "9호선", 37.5342, 126.9046))).groups.single()

    @Test
    fun pinGoesOnTheChosenLinesStationNotTheAverage() {
        assertEquals(126.9026, group.stationOn("2호선")!!.lng, 1e-9)
        assertEquals(126.9046, group.stationOn("9호선")!!.lng, 1e-9)
    }

    @Test
    fun withoutALineItUsesTheStationNearestToTheTap() {
        assertEquals("9호선", group.stationOn(null, 37.5343 to 126.9044)!!.line)
        assertEquals("2호선", group.stationOn("없는선", 37.5343 to 126.9027)!!.line)   // 모르는 노선이면 누른 곳에서 가까운 역
    }
}
