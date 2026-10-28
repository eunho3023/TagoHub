package com.eunho.tagohub

import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.StationIndex
import org.junit.Assert.assertEquals
import org.junit.Test

class StationDataFixesTest {
    private fun st(code: String, line: String, lat: Double, lng: Double) = MetroStation(code, "연신내", line, lat, lng, "서울교통공사")

    @Test
    fun line6YeonsinnaeWithTheWrongLongitudeJoinsLine3AsOneTransferStation() {
        // 표준데이터: 6호선 연신내 경도가 127.0914(신내 근처)로 잘못 적혀 있음
        val index = StationIndex(listOf(st("0311", "3호선", 37.618855, 126.920859), st("2615", "6호선", 37.617293, 127.091375)))
        assertEquals(1, index.groups.size)
        assertEquals(setOf("3호선", "6호선"), index.groups.single().stations.map { it.line }.toSet())
        assertEquals(126.9212, index.stations.first { it.line == "6호선" }.lng, 1e-9)
    }

    @Test
    fun otherStationsAreLeftAlone() {
        val index = StationIndex(listOf(st("0311", "3호선", 37.618855, 126.920859)))
        assertEquals(126.920859, index.stations.single().lng, 1e-9)
    }
}
