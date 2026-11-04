package com.eunho.tagohub.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ArrivalStatsTest {
    // 2024-06-03 (월) 00:00 한국 시각 = 2024-06-02T15:00:00Z
    private val mon0 = 1_717_340_400L
    private fun at(dayOffset: Int, h: Int, m: Int) = mon0 + dayOffset * 86_400L + h * 3600 + m * 60

    @Test fun groupsSameWeekdayIntoSlots() {
        val hits = listOf(
            ArrivalStats.Hit("S", at(0, 6, 9)), ArrivalStats.Hit("S", at(7, 6, 7)), ArrivalStats.Hit("S", at(14, 6, 10)),
            ArrivalStats.Hit("S", at(0, 6, 21)), ArrivalStats.Hit("S", at(1, 6, 30)), ArrivalStats.Hit("X", at(0, 6, 9)),
        )
        val t = ArrivalStats.table(hits, "S", 1)
        assertEquals(3, t.days)
        assertEquals(listOf(369, 381), t.slots.map { it.expected })
        assertEquals(3, t.slots[0].days)
        assertEquals(1, ArrivalStats.table(hits, "S", 2).slots.size)
    }

    @Test fun nextAfterNeedsTwoDays() {
        val t = ArrivalStats.Table(3, listOf(ArrivalStats.Slot(480, 478, 483, 3), ArrivalStats.Slot(500, 500, 500, 1)))
        assertEquals(480, ArrivalStats.nextAfter(t, 470, 470))
        assertNull(ArrivalStats.nextAfter(t, 480, 470))
    }

    @Test fun arrivalTextPadsSeconds() {
        assertEquals("2분 05초", ArrivalText.time(125))
    }
}
