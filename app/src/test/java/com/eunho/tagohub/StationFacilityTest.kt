package com.eunho.tagohub

import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.StationFacilityIndex
import com.eunho.tagohub.data.StationGroup
import org.junit.Assert.assertEquals
import org.junit.Test

class StationFacilityTest {
    @Test
    fun parsesFacilitiesAndMatchesStationLineAndOperator() {
        val index = StationFacilityIndex.parseTsv(
            """
                operator	line	station	category	detail
                한국철도공사	경의중앙선	서울역	주소	도로명주소: 서울로 1
                한국철도공사	경의중앙선	서울역	주소	도로명주소: 서울로 1
                서울교통공사	1호선	서울역	주소	도로명주소: 종로로 2
                서울교통공사	2호선	시청	물품보관함	위치: 1번 출구
            """.trimIndent()
        )
        val station = MetroStation(
            code = "S1",
            name = "서울역(경의선)",
            line = "경의·중앙선",
            lat = 37.55,
            lng = 126.97,
            operator = "코레일",
        )
        val group = StationGroup(
            id = "seoul",
            name = "서울역",
            lat = station.lat,
            lng = station.lng,
            stations = listOf(station),
        )

        assertEquals(
            listOf("도로명주소: 서울로 1"),
            index.forStation(group).map { it.detail },
        )
    }
}
