package com.eunho.tagohub.web

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RecentTest {
    @Test fun stopRoundTrip() {
        val s = WebStop("DJB8001793", "대전역\t앞", 36.33, 127.43, "41570", "25", listOf("102"))
        val back = Recent.stopOf(Recent.encodeStop(s))!!
        assertEquals("대전역 앞", back.name)          // 탭은 띄어쓰기로
        assertEquals(36.33, back.lat)
        assertEquals("41570", back.no)
        assertNull(Recent.stopOf("x\ty"))
    }

    @Test fun stationRoundTrip() {
        val s = SubwayStationW("MTRS11150", "서울역", "1호선")
        assertEquals(s, Recent.stationOf(Recent.encodeStation(s)))
    }
}
