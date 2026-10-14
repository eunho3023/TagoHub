package com.eunho.tagohub

import com.eunho.tagohub.data.DayKind
import com.eunho.tagohub.data.JeonjuTimetable
import org.junit.Assert.assertEquals
import org.junit.Test

/** 전국 노선 예상 시간표 (첫차·막차·배차간격) */
class NationalTimetableTest {
    @Test
    fun startsEveryInterval_untilLast() {
        val s = JeonjuTimetable.fromInterval(JeonjuTimetable.parseClock("05:30")!!, JeonjuTimetable.parseClock("2300")!!, 20)
        assertEquals("05:30", JeonjuTimetable.clock(s.first().minute))
        assertEquals("05:50", JeonjuTimetable.clock(s[1].minute))
        assertEquals("23:00", JeonjuTimetable.clock(s.last().minute)) // 막차 포함
    }

    @Test
    fun lastAfterMidnight_andNoInterval() {
        val s = JeonjuTimetable.fromInterval(23 * 60, 30, 15)       // 23:00 ~ 00:30
        assertEquals("00:30", JeonjuTimetable.clock(s.last().minute))
        assertEquals(7, s.size)
        val only = JeonjuTimetable.fromInterval(6 * 60, 21 * 60, null) // 배차간격 모름 → 첫차·막차만
        assertEquals(listOf("06:00", "21:00"), only.map { JeonjuTimetable.clock(it.minute) })
    }

    @Test
    fun stopTimes_addOffsetFromRouteStart() {
        val starts = JeonjuTimetable.fromInterval(6 * 60, 7 * 60, 30, startSeq = 1)
        // 이 정류장이 노선 10번째, 정류장당 1.5분 → 13.5분 뒤
        val t = JeonjuTimetable.stopTimes(starts, listOf(10)) { a, b -> (b - a) * 1.5 }
        assertEquals(listOf("06:14", "06:44", "07:14"), t.map { JeonjuTimetable.clock(it) })
    }

    @Test
    fun intervalByDay() {
        assertEquals(15, JeonjuTimetable.intervalFor(DayKind.Weekday, 15, 20, 30))
        assertEquals(15, JeonjuTimetable.intervalFor(DayKind.Saturday, 15, null, 30))
        assertEquals(20, JeonjuTimetable.intervalFor(DayKind.Holiday, 15, 20, null))
    }
}
