package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TransitTest {
    @Test fun subwayTimes() {
        assertEquals(5 * 60 + 30, TransitRepo.serviceMinute("053000"))
        assertEquals(24 * 60 + 25, TransitRepo.serviceMinute("002500"))   // 새벽은 24시 뒤로
        assertNull(TransitRepo.serviceMinute("000000"))
        assertNull(TransitRepo.serviceMinute(null))
        val s = TransitRepo.parseSchedule(listOf(
            Triple("060500", "060430", "소요산"), Triple("053000", null, "광운대"), Triple("060500", "060430", "소요산"), Triple(null, "231000", "청량리"),
        ))
        assertEquals(listOf(330, 365, 1390), s.map { it.min })
        assertEquals(true, s.last().arrivalOnly)
        assertEquals("02", TransitRepo.dayCode(6)); assertEquals("03", TransitRepo.dayCode(7)); assertEquals("01", TransitRepo.dayCode(3))
    }

    @Test fun trainInfoMergesByNumber() {
        val legs = listOf(TrainTrip("001", null, 313, 474, null), TrainTrip("1201", null, 400, 700, null))
        val out = TransitRepo.withInfo(legs, mapOf("1" to ("KTX" to 59800)))
        assertEquals("KTX", out[0].kind); assertEquals(59800, out[0].fare)
        assertNull(out[1].kind)
        assertEquals("2시간 41분", duration(474 - 313)); assertEquals("48분", duration(48))
    }
}
