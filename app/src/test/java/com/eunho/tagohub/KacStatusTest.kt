package com.eunho.tagohub

import com.eunho.tagohub.data.KacStatus
import com.eunho.tagohub.data.Trip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDateTime

class KacStatusTest {
    private fun wrap(items: String) =
        """{"response":{"header":{"resultCode":"00","resultMsg":"NORMAL SERVICE."},"body":{"numOfRows":100,"pageNo":1,"totalCount":3,"items":{"item":$items}}}}"""

    private val body = wrap(
        """[{"airFln":"RS6347","airlineKorean":"에어서울","std":"202610290600","etd":"202610290623","io":"O","city":"CJU","rmkKor":"출발","gate":"8","icaoApmCd":"A321","aplRegNo":"HL8072","cdsrYn":"N","boardingKor":"서울/김포","arrivedKor":"제주"},
            {"airFln":"KE5153","airlineKorean":"대한항공","std":"202610290605","etd":"202610290605","io":"O","city":"CJU","rmkKor":"결항","cdsrYn":"Y","boardingKor":"서울/김포","arrivedKor":"제주"},
            {"airFln":"OZ8900","airlineKorean":"아시아나항공","std":"202610290745","etd":"202610290742","io":"I","city":"CJU","rmkKor":"도착","cdsrYn":"N","boardingKor":"제주","arrivedKor":"서울/김포"}]""",
    )
    private val rows = KacStatus.parse(body)
    private fun at(h: Int, m: Int) = LocalDateTime.of(2026, 10, 29, h, m)
    private fun trip(no: String, dep: LocalDateTime) = Trip("에어서울", no, "김포", "제주", dep, null, emptyList())

    @Test
    fun routeKeepsOnlyThatDirectionAndOtherAirport() {
        assertEquals(listOf("RS6347", "KE5153"), KacStatus.route(rows, true, "CJU").map { it.flightId })
        assertEquals(listOf("OZ8900"), KacStatus.route(rows, false, "CJU").map { it.flightId })
        assertTrue(KacStatus.route(rows, true, "PUS").isEmpty())
    }

    @Test
    fun overlayAddsStatusGateAircraftAndChangedTime() {
        val out = KacStatus.overlay(listOf(trip("RS 6347", at(6, 0))), KacStatus.route(rows, true, "CJU"), null).single()
        assertEquals("에어서울 · 출발 · 탑승구 8 · A321 HL8072 · 예정 06:00", out.kind)
        assertEquals(at(6, 23), out.dep)
    }

    @Test
    fun cancelledFlightIsMarked() {
        val out = KacStatus.overlay(listOf(trip("KE5153", at(6, 5))), KacStatus.route(rows, true, "CJU"), null).single()
        assertTrue("결항" in out.kind)
    }

    @Test
    fun flightsWithoutStatusStayAsTheyAre() {
        val t = trip("ZZ1", at(9, 0))
        assertEquals(listOf(t), KacStatus.overlay(listOf(t), KacStatus.route(rows, true, "CJU"), null))
    }

    @Test
    fun emptyScheduleIsBuiltFromRowsWithoutCodeshares() {
        val out = KacStatus.overlay(emptyList(), KacStatus.route(rows, true, "CJU"), null)
        assertEquals(listOf("RS6347"), out.map { it.number })
        assertEquals("제주", out.single().arrName)
    }
}
