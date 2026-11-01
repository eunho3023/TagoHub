package com.eunho.tagohub

import com.eunho.tagohub.data.SignalPlans
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalDate

/** UTIC 신호 운영계획 응답(2026-11 실제 모양을 줄인 것) */
class SignalPlansTest {
    private val ops = """[{"totCount":"4","resultCode":"0","resultMsg":"NORMAL_SERVICE"},
        {"REGION_CD":"L02","INT_NO":"1001","INT_NM":"신광4거리","INT_PLAN_NO":"1","INT_PLAN_IDX_NO":"1","OPER_PLAN_HH":"6","OPER_PLAN_MI":"0",
         "INT_OPER_CYCLE_VAL":"160","INT_OPER_OFFSET_VAL":"45","A_RING_1_PHASE_VAL":"63","A_RING_2_PHASE_VAL":"45","A_RING_3_PHASE_VAL":"52","A_RING_4_PHASE_VAL":"0",
         "B_RING_1_PHASE_VAL":"63","B_RING_2_PHASE_VAL":"45","B_RING_3_PHASE_VAL":"52"},
        {"REGION_CD":"L02","INT_NO":"1001","INT_PLAN_NO":"1","OPER_PLAN_HH":"7","OPER_PLAN_MI":"0","INT_OPER_CYCLE_VAL":"170","INT_OPER_OFFSET_VAL":"50",
         "A_RING_1_PHASE_VAL":"70","A_RING_2_PHASE_VAL":"45","A_RING_3_PHASE_VAL":"55","B_RING_1_PHASE_VAL":"40","B_RING_2_PHASE_VAL":"75","B_RING_3_PHASE_VAL":"55"},
        {"REGION_CD":"L02","INT_NO":"1001","INT_PLAN_NO":"1","OPER_PLAN_HH":"22","OPER_PLAN_MI":"30","INT_OPER_CYCLE_VAL":"120","INT_OPER_OFFSET_VAL":"0","A_RING_1_PHASE_VAL":"60","A_RING_2_PHASE_VAL":"60"},
        {"REGION_CD":"L02","INT_NO":"1002","INT_PLAN_NO":"1","OPER_PLAN_HH":"6","OPER_PLAN_MI":"0","INT_OPER_CYCLE_VAL":"999"}]"""

    private val weekdays = """[{"totCount":"8"},
        {"INT_NO":"1001","PLAN_DY":"1","INT_PLAN_NO":"4"},{"INT_NO":"1001","PLAN_DY":"2","INT_PLAN_NO":"2"},{"INT_NO":"1001","PLAN_DY":"3","INT_PLAN_NO":"1"},
        {"INT_NO":"1001","PLAN_DY":"4","INT_PLAN_NO":"1"},{"INT_NO":"1001","PLAN_DY":"5","INT_PLAN_NO":"1"},{"INT_NO":"1001","PLAN_DY":"6","INT_PLAN_NO":"1"},
        {"INT_NO":"1001","PLAN_DY":"7","INT_PLAN_NO":"3"},{"INT_NO":"1002","PLAN_DY":"1","INT_PLAN_NO":"9"}]"""

    private val holidays = """[{"totCount":"3"},
        {"INT_NO":"1001","HOLY_PLAN_MM":"1","HOLY_PLAN_DD":"1","INT_PLAN_NO":"4"},
        {"INT_NO":"1001","HOLY_PLAN_MM":"0","HOLY_PLAN_DD":"0","INT_PLAN_NO":"0"},
        {"INT_NO":"1001","HOLY_PLAN_MM":"8","HOLY_PLAN_DD":"15","INT_PLAN_NO":"4"}]"""

    @Test
    fun slotsAreForThisIntersectionOnlyAndTrailingZeroPhasesAreDropped() {
        val slots = SignalPlans.parseSlots(ops, "1001")
        assertEquals(listOf(360, 420, 1350), slots.map { it.startMin })
        assertEquals(listOf(63, 45, 52), slots[0].ringA)
        assertEquals(160, slots[0].cycleSec)
        assertEquals(45, slots[0].offsetSec)
        assertEquals("22:30", slots[2].startLabel)
        assertTrue(slots[2].ringB.isEmpty())
    }

    @Test
    fun dayCodeOneIsSundayLikeTheRealData() {
        assertEquals(1, SignalPlans.dayCode(LocalDate.of(2026, 11, 1)))   // 일요일
        assertEquals(2, SignalPlans.dayCode(LocalDate.of(2026, 11, 2)))   // 월요일
        assertEquals(7, SignalPlans.dayCode(LocalDate.of(2026, 11, 7)))   // 토요일
    }

    @Test
    fun holidaysWinOverWeekdaysAndEmptyHolidayRowsAreIgnored() {
        val wd = SignalPlans.parseWeekdays(weekdays, "1001")
        val hd = SignalPlans.parseHolidays(holidays, "1001")
        assertEquals(2, hd.size)
        assertEquals(1 to false, SignalPlans.planFor(LocalDate.of(2026, 11, 4), wd, hd))   // 수요일 → 평일 계획 1
        assertEquals(3 to false, SignalPlans.planFor(LocalDate.of(2026, 11, 7), wd, hd))   // 토요일 → 3
        assertEquals(4 to true, SignalPlans.planFor(LocalDate.of(2026, 8, 15), wd, hd))    // 광복절 → 특수일 4
        assertNull(SignalPlans.planFor(LocalDate.of(2026, 11, 4), emptyMap(), emptyMap()))
    }

    @Test
    fun currentSlotIsTheLastStartedOneAndBeforeTheFirstItWrapsFromYesterday() {
        val slots = SignalPlans.parseSlots(ops, "1001")
        val noon = SignalPlans.today(slots, 1, false, 12 * 60)
        assertEquals(420, noon.current?.startMin)
        assertEquals(1350, noon.next?.startMin)
        val night = SignalPlans.today(slots, 1, false, 3 * 60)          // 새벽 3시: 전날 22:30 시간대가 이어짐
        assertEquals(1350, night.current?.startMin)
        assertEquals(360, night.next?.startMin)
        val late = SignalPlans.today(slots, 1, false, 23 * 60)
        assertNull(late.next)
        assertTrue(SignalPlans.today(slots, 7, false, 600).slots.isEmpty())
    }

    @Test
    fun intersectionTableAndIdParts() {
        val list = SignalPlans.parseIntersections("L02\t1001\t신광4거리\t37.464368\t126.636053\n깨짐\nL29\t429\t\t35.88\t128.69\n")
        assertEquals(listOf("utic:L02:1001", "utic:L29:429"), list.map { it.id })
        assertEquals("교차로", list[1].name)
        assertEquals("L02" to "1001", SignalPlans.parts(list[0].id))
        assertTrue(SignalPlans.isPlanId(list[0].id))
        assertFalse(SignalPlans.isPlanId("seoul:1"))
        assertNull(SignalPlans.parts("utic:L02"))
    }

    @Test
    fun brokenResponsesGiveNothing() {
        assertTrue(SignalPlans.parseSlots("깨짐", "1").isEmpty())
        assertTrue(SignalPlans.parseWeekdays("{}", "1").isEmpty())
        assertTrue(SignalPlans.parseHolidays("[]", "1").isEmpty())
    }
}
