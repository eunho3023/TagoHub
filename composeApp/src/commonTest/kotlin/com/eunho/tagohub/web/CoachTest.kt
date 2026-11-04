package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CoachTest {
    @Test fun parsesCoachRows() {
        val t = TransitRepo.parseCoach(
            listOf(
                listOf("202611052330", "202611060210", "우등", "35000"),
                listOf("202611050600", "202611050820", "", "0"),
                listOf("202611050600", "202611050820", "", "0"),
                listOf(null, null, "x", "1"),
            ),
        )
        assertEquals(listOf(360, 1410), t.map { it.depMin })
        assertEquals("일반", t[0].grade)
        assertNull(t[0].fare)
        assertEquals(1440 + 130, t[1].arrMin)   // 자정 넘어 도착
        assertEquals(35000, t[1].fare)
        assertEquals(true, CoachTerminal("NAEK010", "서울경부", "서울").express)
        assertEquals(false, CoachTerminal("NAI3214401", "전주", "전북").express)
    }
}
