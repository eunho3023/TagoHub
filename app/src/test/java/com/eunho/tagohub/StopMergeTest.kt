package com.eunho.tagohub

import com.eunho.tagohub.data.BusStop
import com.eunho.tagohub.data.StopMerge
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** 내장 정류장 DB 의 실제 좌표 */
class StopMergeTest {
    private val jeonjuByc = BusStop("35010", "JUB312100690", "BYC", "30690", 35.820431, 127.007781)
    private val gimjeByc = BusStop("35060", "TSB311000313", "BYC", "3110313", 35.820441, 127.007777)       // 1m
    private val gimjeBycAcross = BusStop("35060", "TSB311001415", "BYC", "3111415", 35.820638, 127.007897) // 25m, 길 건너편
    private val jeonjuGwanchon = BusStop("35010", "JUB316100044", "관촌시장", "30044", 35.676806, 127.268678)
    private val imsilGwanchon = BusStop("35350", "TSB316000445", "관촌시장", "3160445", 35.676789, 127.268672)

    @Test
    fun sameSpot_mergedIntoOne_withBothNumbers() {
        val merged = StopMerge.merge(listOf(jeonjuByc, gimjeByc, gimjeBycAcross, jeonjuGwanchon, imsilGwanchon), preferCity = "35010")
        assertEquals(3, merged.size) // BYC(전주+김제), BYC 건너편(김제), 관촌시장(전주+임실)
        val byc = merged.first { it.main.nodeId == "JUB312100690" }
        assertEquals(listOf("TSB311000313"), byc.others.map { it.nodeId })
        assertEquals("30690 · 3110313", byc.numbers)
        // 길 건너편 김제 BYC 는 따로 (반대 방향)
        assertTrue(merged.any { it.main.nodeId == "TSB311001415" && it.others.isEmpty() })
        assertEquals("30044 · 3160445", merged.first { it.main.nodeId == "JUB316100044" }.numbers)
    }

    @Test
    fun mainStop_followsCurrentRegion() {
        val m = StopMerge.merge(listOf(jeonjuByc, gimjeByc), preferCity = "35060").single()
        assertEquals("TSB311000313", m.main.nodeId)
        assertEquals("3110313 · 30690", m.numbers)
    }
}
