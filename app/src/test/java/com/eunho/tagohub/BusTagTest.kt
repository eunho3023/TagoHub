package com.eunho.tagohub

import com.eunho.tagohub.data.BusLocation
import com.eunho.tagohub.ui.bus.busTag
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** 노선 띠·지도 버스 이름표: 차량번호 전체 + 저상/일반 */
class BusTagTest {
    private fun bus(no: String?, low: Boolean?) = BusLocation(null, null, 1, no, null, null, lowFloor = low)

    @Test
    fun tag() {
        assertEquals("서울75사2644 · 저상", busTag(bus("서울75사2644", true)))
        assertEquals("경기70바6024 · 일반", busTag(bus("경기70바6024", false)))
        assertEquals("대전75자2373", busTag(bus(" 대전75자2373 ", null)))
        assertNull(busTag(bus("#3", true)))
        assertNull(busTag(bus(null, true)))
    }
}
