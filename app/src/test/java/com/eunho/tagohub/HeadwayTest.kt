package com.eunho.tagohub

import com.eunho.tagohub.data.ArrivalText
import com.eunho.tagohub.data.BusArrival
import com.eunho.tagohub.data.Headway
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class HeadwayTest {
    /** 309번 평일 실제 출발 시각 (회차 1~5) */
    private val r309 = listOf(600, 625, 650, 703, 718, 742, 807, 832, 857, 919, 942, 1007, 1032, 1057, 1122, 1147, 1212, 1237, 1302,
        1332, 1357, 1422, 1447, 1512, 1537, 1602, 1627, 1652, 1717, 1743, 1809, 1834, 1859, 1924, 1949, 2014, 2039, 2104, 2128,
        2152, 2216, 2240).map { it / 100 * 60 + it % 100 }

    @Test
    fun gapFromRealDepartures() {
        assertEquals(25, Headway.gapNear(r309, 12 * 60))      // 낮에는 25분 간격
        assertEquals(24, Headway.gapNear(r309, 22 * 60))      // 밤 21:52·22:16·22:40
        assertNull(Headway.gapNear(r309, 23 * 60))            // 막차(22:40)가 떠난 뒤
    }

    @Test
    fun gapIgnoresRareRoutes() {
        assertNull(Headway.gapNear(listOf(7 * 60, 12 * 60, 18 * 60), 11 * 60))   // 하루 세 번(5~6시간 간격)은 "다음 차"로 안 보여 줌
        assertNull(Headway.gapNear(listOf(9 * 60), 8 * 60))                       // 출발이 하나뿐
    }

    @Test
    fun intervalWithinService() {
        assertEquals(15, Headway.fromInterval(15, "05:30", "22:40", 12 * 60))
        assertNull(Headway.fromInterval(15, "05:30", "22:40", 23 * 60))          // 막차가 떠난 뒤
        assertEquals(15, Headway.fromInterval(15, "05:30", "00:30", 23 * 60 + 50)) // 막차가 자정을 넘김
        assertEquals(15, Headway.fromInterval(15, "05:30", "00:30", 10))           // 자정 지나 막차 전
        assertNull(Headway.fromInterval(15, "05:30", "00:30", 60))                 // 자정 지나 막차 뒤
        assertNull(Headway.fromInterval(180, "05:30", "22:40", 12 * 60))         // 간격이 너무 김
        assertEquals(10, Headway.fromInterval(10, null, null, 12 * 60))           // 첫차·막차를 모르면 간격만
    }

    @Test
    fun scheduledSecondText() {
        val a = BusArrival("R", "309", null, null, prevStops = null, arrSeconds = 29 * 60, estimated = true, scheduled = true)
        assertEquals("약 29분 · 배차 기준", ArrivalText.line(a))
    }
}
