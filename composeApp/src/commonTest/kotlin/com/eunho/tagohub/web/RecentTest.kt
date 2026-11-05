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

class HomeWorkNoticesTest {
    @Test fun homeRoundTrip() {
        val p = WebPlace("우리\t집", "대전 서구", 36.35, 127.38, "아파트")
        val back = HomeWork.placeOf(HomeWork.encode(p))!!
        assertEquals("우리 집", back.name)
        assertEquals("대전 서구", back.address)
        assertEquals(127.38, back.lng)
        assertNull(HomeWork.placeOf("x\ty"))
    }

    @Test fun noticesNewestFirst() {
        val l = NoticesW.parse("""[{"date":"2026-10-15","title":"처음","body":"a"},{"date":"2026-11-01","title":"새것"},{"date":"2026-11-02"}]""")
        assertEquals(listOf("새것", "처음"), l.map { it.title })
        assertEquals("https://github.com/eunho3023/TagoHub/issues/new?title=%5B%EC%9B%B9%20%EC%A0%9C%EB%B3%B4%5D%20", NoticesW.reportUrl())
    }
}
