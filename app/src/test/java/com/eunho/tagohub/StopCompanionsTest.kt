package com.eunho.tagohub

import com.eunho.tagohub.data.StopCompanions
import org.junit.Assert.assertEquals
import org.junit.Test

class StopCompanionsTest {
    @Test
    fun parsesPrecomputedCompanionTable() {
        // pipeline/build_stop_companions.js 가 만드는 형식 그대로
        val text = "ADB354000001\tTSB354000013|TS354|3540013|길안정류장|36.458615|128.891158;" +
            "YDB9420|37330||길안정류장|36.458641|128.891281\n"
        val m = StopCompanions.parse(text)
        val list = m.getValue("ADB354000001")
        assertEquals(2, list.size)
        assertEquals("TS354", list[0].cityCode)
        assertEquals("TSB354000013", list[0].nodeId)
        assertEquals("길안정류장", list[0].name)
        assertEquals(null, list[1].nodeNo)   // 번호가 비면 null
        assertEquals(36.458641, list[1].lat!!, 0.000001)
    }

    @Test
    fun ignoresBrokenLines() {
        assertEquals(0, StopCompanions.parse("").size)
        assertEquals(0, StopCompanions.parse("no-tab-here\n").size)
        assertEquals(0, StopCompanions.parse("A1\tbad|line\n").size)
    }
}
