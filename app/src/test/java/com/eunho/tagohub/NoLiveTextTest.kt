package com.eunho.tagohub

import com.eunho.tagohub.data.NoLiveText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NoLiveTextTest {
    private fun at(h: Int, m: Int) = h * 60 + m

    @Test
    fun endedShowsNextFirstBusAndWait() {
        // 구례 7-4: 06:10~19:30. 새벽 1:41 → 첫차까지 4시간 29분
        val night = NoLiveText.describe("06:10", "19:30", 290, at(1, 41))!!
        assertTrue(night.ended)
        assertEquals("운행종료", night.main)
        assertEquals("첫차 06:10 · 4시간 29분 후", night.sub)
        // 막차 뒤 90분 넘음 → 내일 첫차
        assertEquals("내일 첫차 06:10 · 8시간 40분 후", NoLiveText.describe("06:10", "19:30", null, at(21, 30))!!.sub)
        // 막차 뒤 90분 안 → 아직 달릴 수 있음
        assertEquals("막차 출발함", NoLiveText.describe("06:10", "19:30", null, at(20, 0))!!.main)
    }

    @Test
    fun inServiceShowsNextDepartureOrLastBus() {
        val t = NoLiveText.describe("06:00", "22:00", 20, at(14, 25))!!   // 06:00 부터 20분마다 → 14:40
        assertEquals("다음 출발 약 14:40", t.main)
        assertEquals("15분 후 · 기점", t.sub)
        assertEquals(Pair("막차 22:00", "1시간 5분 후 · 기점"), NoLiveText.describe("06:00", "22:00", null, at(20, 55))!!.let { it.main to it.sub })
        assertEquals("첫차 06:00", NoLiveText.describe("06:00", "22:00", null, at(5, 45))!!.main)   // 첫차 30분 전부터
        assertEquals("1시간", NoLiveText.duration(60))
        assertNull(NoLiveText.describe(null, "22:00", 10, at(12, 0)))
    }

    @Test
    fun lastBusAfterMidnight() {
        // 05:30~00:30: 23:50 은 운행 중, 막차까지 40분
        assertEquals("막차 00:30", NoLiveText.describe("05:30", "00:30", null, at(23, 50))!!.main)
        assertEquals("운행종료", NoLiveText.describe("05:30", "00:30", null, at(3, 0))!!.main)
    }
}
