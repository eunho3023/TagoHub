package com.eunho.tagohub

import com.eunho.tagohub.core.TagoTime
import org.junit.Assert.assertEquals
import org.junit.Test

/** 시간표상 운행 시간 안인지 (실시간 위치가 안 오는 노선 문구, 웹 busdata.js inService 와 같은 경우) */
class InServiceTest {
    @Test
    fun firstToLast() {
        assertEquals(true, TagoTime.inService("07:40", "18:00", 17 * 60 + 58))   // 여수 365-5 오후 5시 58분
        assertEquals(false, TagoTime.inService("07:40", "18:00", 18 * 60 + 30))
        assertEquals(false, TagoTime.inService("07:40", "18:00", 7 * 60))
        assertEquals(true, TagoTime.inService("05:30", "00:30", 23 * 60 + 50))   // 막차가 자정 넘어
        assertEquals(true, TagoTime.inService("05:30", "00:30", 10))
        assertEquals(false, TagoTime.inService("05:30", "00:30", 4 * 60))
        assertEquals(false, TagoTime.inService(null, "18:00", 600))
    }
}
