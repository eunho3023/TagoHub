package com.eunho.tagohub

import com.eunho.tagohub.data.AirportGeo
import com.eunho.tagohub.data.CoachTerminalGeo
import com.eunho.tagohub.data.Place
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/** 선택 화면의 거리순·지역별에 쓰는 장소 위치표 */
class PlaceGeoTest {
    @Test
    fun terminalFileParses() {
        val t = CoachTerminalGeo.parse("NAEK010\t서울경부\t서울\t37.506495\t127.008268\nNAEK124\t공항왕복\t\t\t\nbad\n")
        assertEquals(2, t.size)
        assertEquals("서울", t["NAEK010"]!!.first)
        assertEquals(37.506495, t["NAEK010"]!!.second!!.first, 1e-9)
        assertNull(t["NAEK124"]!!.first)
        assertNull(t["NAEK124"]!!.second)
    }

    @Test
    fun airportsAreFoundByNameFragment() {
        val gmp = Place("NAARKSS", "김포국제공항")
        assertNotNull(AirportGeo.pointOf(gmp))
        assertEquals("서울", AirportGeo.regionOf(gmp))
        assertEquals("전남", AirportGeo.regionOf(Place("x", "여수공항")))
        assertNull(AirportGeo.pointOf(Place("x", "하네다")))
    }

    @Test
    fun enrichFillsOnlyBlankRegions() {
        val list = listOf(Place("1", "제주국제공항"), Place("2", "대구국제공항", "대구광역시"))
        val out = AirportGeo.enrich(list)
        assertEquals("제주", out[0].sub)
        assertEquals("대구광역시", out[1].sub)
    }

    @Test
    fun byDistanceSortsNearestFirstAndUnknownLast() {
        val list = listOf(Place("a", "제주국제공항"), Place("b", "김포국제공항"), Place("c", "나리타"))
        val sorted = AirportGeo.byDistance(list, 37.5, 127.0)       // 서울 근처
        assertEquals(listOf("김포국제공항", "제주국제공항", "나리타"), sorted.map { it.first.name })
        assertNull(sorted.last().second)
    }
}
