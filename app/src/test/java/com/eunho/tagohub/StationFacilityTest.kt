package com.eunho.tagohub

import com.eunho.tagohub.data.MetroStation
import com.eunho.tagohub.data.StationFacilityIndex
import com.eunho.tagohub.data.StationGroup
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class StationFacilityTest {
    /** 앱에 든 편의정보 파일에 서울교통공사 자료(연락처·환승거리·승강설비 …)가 들어 있고 강남역에 붙는지 */
    @Test
    fun bundledFileHasSeoulMetroInfoForGangnam() {
        val file = listOf("src/main/assets/subway_station_facilities.tsv", "app/src/main/assets/subway_station_facilities.tsv")
            .map { java.io.File(it) }.first { it.exists() }
        val index = StationFacilityIndex.parseTsv(file.readText(Charsets.UTF_8).removePrefix("﻿"))
        val station = MetroStation("0222", "강남", "2호선", 37.4979, 127.0276, "서울교통공사")
        val group = StationGroup("gangnam", "강남", station.lat, station.lng, listOf(station))
        val byCategory = index.forStation(group).groupBy { it.category }
        for (c in listOf("역 연락처", "환승 거리·시간", "승강설비", "물품보관함", "화장실")) {
            assertTrue("$c 없음", byCategory[c].orEmpty().isNotEmpty())
        }
        assertTrue(byCategory["환승 거리·시간"]!!.any { "신분당선" in it.detail && "214m" in it.detail })
    }

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
