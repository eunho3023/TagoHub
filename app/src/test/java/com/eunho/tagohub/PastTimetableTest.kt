package com.eunho.tagohub

import com.eunho.tagohub.data.ArrivalStats
import com.eunho.tagohub.ui.map.PastTimetable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneOffset

class PastTimetableTest {

    /** 한국 시각 → 기록(epochSec) */
    private fun hit(nodeId: String, at: LocalDateTime): ArrivalStats.Hit =
        ArrivalStats.Hit(nodeId, at.toEpochSecond(ZoneOffset.ofHours(9)))

    @Test
    fun `오늘이 그 요일이면 지난주부터 세 주를 센다`() {
        val today = LocalDate.of(2026, 11, 10) // 화요일
        assertEquals(
            listOf(LocalDate.of(2026, 11, 3), LocalDate.of(2026, 10, 27), LocalDate.of(2026, 10, 20)),
            PastTimetable.weeks(DayOfWeek.TUESDAY, today),
        )
    }

    @Test
    fun `다른 요일은 이번 주 해당 요일이 1주 전`() {
        val today = LocalDate.of(2026, 11, 10) // 화요일
        assertEquals(
            listOf(LocalDate.of(2026, 11, 9), LocalDate.of(2026, 11, 2), LocalDate.of(2026, 10, 26)),
            PastTimetable.weeks(DayOfWeek.MONDAY, today),
        )
    }

    @Test
    fun `기록을 날짜별로 나눠 세 열에 담는다`() {
        val today = LocalDate.of(2026, 11, 10)
        val hits = listOf(
            hit("N1", LocalDateTime.of(2026, 11, 3, 6, 22)),
            hit("N1", LocalDateTime.of(2026, 11, 3, 6, 50)),
            hit("N1", LocalDateTime.of(2026, 10, 27, 6, 21)),
            hit("N2", LocalDateTime.of(2026, 11, 3, 7, 0)), // 다른 정류장 기록은 빼야 함
        )
        val columns = PastTimetable.columns(hits, "N1", DayOfWeek.TUESDAY, today)
        assertEquals(listOf("1주 전", "2주 전", "3주 전"), columns.map { it.label })
        assertEquals(listOf(6 * 60 + 22, 6 * 60 + 50), columns[0].minutes)
        assertEquals(listOf(6 * 60 + 21), columns[1].minutes)
        assertTrue(columns[2].minutes.isEmpty())
    }

    @Test
    fun `시각이 하나도 없으면 빈 시간표`() {
        val today = LocalDate.of(2026, 11, 10)
        val columns = PastTimetable.columns(emptyList(), "N1", DayOfWeek.TUESDAY, today)
        assertTrue(PastTimetable.isEmpty(columns))
        assertNull(PastTimetable.hourRange(columns))
    }

    @Test
    fun `시간대 칩 범위는 기록이 있는 시의 최소와 최대`() {
        val today = LocalDate.of(2026, 11, 10)
        val hits = listOf(
            hit("N1", LocalDateTime.of(2026, 11, 3, 6, 22)),
            hit("N1", LocalDateTime.of(2026, 11, 3, 22, 37)),
        )
        val columns = PastTimetable.columns(hits, "N1", DayOfWeek.TUESDAY, today)
        assertEquals(6..22, PastTimetable.hourRange(columns))
    }

    @Test
    fun `열 머리글과 요일 이름`() {
        assertEquals("11.3.(화)", PastTimetable.dateLabel(LocalDate.of(2026, 11, 3)))
        assertEquals("휴일", PastTimetable.dayLabel(DayOfWeek.SUNDAY))
        assertEquals("월", PastTimetable.dayLabel(DayOfWeek.MONDAY))
    }
}
