package com.eunho.tagohub

import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.StationIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class StationTapTest {
    private fun st(code: String, line: String, lat: Double, lng: Double) = MetroStation(code, "당산", line, lat, lng, "서울교통공사")

    // 당산: 2호선과 9호선 역이 약 180m 떨어진 환승역 (중심점은 두 역 사이)
    private val index = StationIndex(listOf(st("a", "2호선", 37.5342, 126.9026), st("b", "9호선", 37.5342, 126.9046)))

    @Test
    fun tappingEitherPlatformOfAnInterchangeIsCloseToTheStation() {
        val group = index.groups.single()
        assertEquals(2, group.stations.size)
        // 중심점까지는 90m 가량이어도 실제 역(2호선) 바로 앞이면 가깝다
        assertEquals(true, group.distanceTo(37.5342, 126.9026) < 5.0)
        assertEquals(true, group.distanceTo(37.5342, 126.9046) < 5.0)
    }

    @Test
    fun aNamelessTapOnAStationIconFindsTheStationButNotFarAwayPlaces() {
        assertNotNull(index.nearestGroup(37.5343, 126.9027))
        assertNull(index.nearestGroup(37.5400, 126.9026))   // 600m 떨어진 곳
    }
}
