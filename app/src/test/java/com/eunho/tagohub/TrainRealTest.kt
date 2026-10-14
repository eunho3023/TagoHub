package com.eunho.tagohub

import com.eunho.tagohub.core.TagoXml
import com.eunho.tagohub.data.Mappers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** TAGO 기차(TrainInfo) 실제 응답 (2026-10, 사용자가 브라우저로 받은 것) */
class TrainRealTest {
    private fun rows(name: String) = TagoXml.parse(javaClass.getResource("/fixtures/real_$name.xml")!!.readText(Charsets.UTF_8)).rows

    @Test
    fun cities_kinds_stations() {
        val cities = rows("GetCtyCodeList").map(Mappers::city)
        assertEquals(16, cities.size)
        assertEquals("11", cities[0].code); assertEquals("서울특별시", cities[0].name)
        val kinds = rows("GetVhcleKndList").map(Mappers::trainKind)
        assertEquals(15, kinds.size)
        assertEquals("00", kinds[0].id); assertEquals("KTX", kinds[0].name)        // 앞의 0 이 사라지지 않음
        val st = rows("GetCtyAcctoTrainSttnList").map { Mappers.trainStation(it, "서울특별시") }
        assertEquals(10, st.size)
        assertEquals("NAT010000", st[0].id); assertEquals("서울", st[0].name)
    }

    @Test
    fun trips_timesAndFare() {
        val trips = rows("GetStrtpntAlocFndTrainInfo").map(Mappers::trainTrip)
        assertEquals(12, trips.size)
        val t = trips[0]
        assertEquals("무궁화호", t.kind)
        assertEquals("1151", t.number)                                               // 응답 "01151" → 앞의 0 떼고 표시
        assertEquals("2026-10-14T06:37", t.dep.toString())                           // 14자리 시각
        assertEquals("2026-10-14T07:51", t.arr.toString())
        assertEquals(listOf("어른" to 9300), t.prices)
        assertTrue(trips.all { it.dep != null && it.arr != null })
    }
}
