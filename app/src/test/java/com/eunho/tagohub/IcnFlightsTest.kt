package com.eunho.tagohub

import com.eunho.tagohub.data.IcnFlights
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** 인천공항 운항 현황 실제 응답 (출발, 도쿄/나리타, 2026-10-15 브라우저 — 2026-10-12 편들) */
class IcnFlightsTest {
    private val all = IcnFlights.parse(javaClass.getResource("/fixtures/real_icn_departures_nrt.json")!!.readText(Charsets.UTF_8))

    @Test
    fun parse_realResponse() {
        assertEquals(10, all.size)                                   // items 는 바로 목록 (가이드의 {item:…} 아님)
        assertEquals("BX164", all[0].flightId); assertEquals("에어부산", all[0].airline)
        assertEquals("NRT", all[0].airportCode); assertEquals("2026-10-12T07:10", all[0].scheduled.toString())
        assertEquals("2026-10-12T07:12", all[0].estimated.toString())
        assertTrue(!all[2].master && all[2].flightId == "KE5741")       // 진에어 LJ201 공동운항
    }

    @Test
    fun trips_masterOnly_withStatus() {
        val trips = IcnFlights.toTrips(all, departure = true, date = LocalDate.of(2026, 10, 12))
        assertEquals(listOf("BX164", "LJ201", "ZE605", "LJ203", "ZE601", "RS705", "7C1101", "OZ102"), trips.map { it.number })   // 공동운항 2편 빼고 8편
        assertEquals("에어부산 · 출발 · T2 · 탑승구 287 · 예정 07:10", trips[0].kind)
        assertEquals("2026-10-12T07:12", trips[0].dep.toString())          // 변경(실제) 시각
        assertEquals("인천", trips[0].depName); assertEquals("도쿄/나리타", trips[0].arrName)
        assertEquals("이스타항공 · 출발 · T1 · 탑승구 24 · 예정 07:25", trips[2].kind)
        assertEquals(0, IcnFlights.toTrips(all, departure = true, date = LocalDate.of(2026, 10, 13)).size)   // 다른 날짜는 빠짐
    }
}
