package com.eunho.tagohub

import com.eunho.tagohub.data.KacIntl
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 한국공항공사 국제선 실제 응답 (김포→하네다, 2026-10-15 브라우저): 같은 NH862 가 기간별로 여러 행 */
class KacIntlTest {
    private val all = KacIntl.parse(javaClass.getResource("/fixtures/real_kac_intl_gmp_hnd.json")!!.readText(Charsets.UTF_8))

    @Test
    fun parse_realResponse() {
        assertEquals(10, all.size)
        val f = all[0]
        assertEquals("NH862", f.num); assertEquals("전일본공수", f.airline)
        assertEquals("GMP", f.airportCode); assertEquals("HND", f.cityCode); assertTrue(f.out)
        assertEquals("07:40", f.time.toString()); assertEquals(7, f.days.size)
    }

    @Test
    fun forDate_onlyCurrentPeriod() {
        // 2026-10-15(목): 2026-03-30~2026-10-25 기간의 07:40 하나만 (옛 2014·2020·2024·2025 기간은 빠짐)
        val today = KacIntl.forDate(all, LocalDate.of(2026, 10, 15))
        assertEquals(listOf("NH862 07:40"), today.map { "${it.num} ${it.time}" })
        // 2026-01-15: 겨울 기간(2025-10-27~2026-03-29) 07:45
        assertEquals(listOf("NH862 07:45"), KacIntl.forDate(all, LocalDate.of(2026, 1, 15)).map { "${it.num} ${it.time}" })
        val trips = KacIntl.toTrips(today, LocalDate.of(2026, 10, 15))
        assertEquals("서울/김포", trips[0].depName); assertEquals("도쿄/하네다", trips[0].arrName)
        assertEquals("2026-10-15T07:40", trips[0].dep.toString()); assertEquals(null, trips[0].arr)   // 출발편: 도착 시각은 응답에 없음
    }

    @Test
    fun places_search() {
        assertEquals("GMP", KacIntl.places("김포").first().id)
        assertEquals("HND", KacIntl.places("하네다").first().id)
        assertTrue(KacIntl.places("hnd").isNotEmpty() && KacIntl.isKac("PUS") && !KacIntl.isKac("HND"))
    }
}
