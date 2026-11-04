package com.eunho.tagohub.data

import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AirTableTest {
    private val text = "D\tKAL\tKE1201\tGMP\tCJU\t07:00\t08:10\t월화수목금\t2026-11-01\t2026-11-30\n" +
        "D\tJJA\t7C101\tGMP\tCJU\t06:00\t07:05\t토일\t2026-11-01\t2026-11-30\n" +
        "D\tKAL\tKE1299\tGMP\tCJU\t23:30\t00:40\t수\t2026-11-01\t2026-11-30\n" +
        "I\tKAL\tKE001\tICN\tNRT\t09:00\t11:30\t월\t2026-11-01\t2026-11-30\n" +
        "bad line\n"

    @Test fun flightsOnDayAndProjection() {
        val rows = AirTable.parse(text)
        assertEquals(4, rows.size)
        val wed = AirTable.flights(rows, "GMP", "CJU", LocalDate(2026, 11, 4))        // 수요일
        assertEquals(listOf("KE1201", "KE1299"), wed.flights.map { it.flight })
        assertEquals(24 * 60 + 40, wed.flights[1].arrMin)                              // 자정 넘어 도착
        assertEquals(listOf("7C101"), AirTable.flights(rows, "GMP", "CJU", LocalDate(2026, 11, 7)).flights.map { it.flight })
        val later = AirTable.flights(rows, "GMP", "CJU", LocalDate(2026, 12, 9))       // 파일 기간 뒤 수요일 → 예상
        assertTrue(later.projected)
        assertEquals(2, later.flights.size)
        assertEquals(listOf("GMP", "CJU"), AirTable.domesticAirports(rows))
    }
}
