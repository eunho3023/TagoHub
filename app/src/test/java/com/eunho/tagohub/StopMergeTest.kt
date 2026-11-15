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

    /** 같은 자리에 정류장이 셋 넘게 몰린 곳: 가까운 짝부터 시군마다 하나씩 묶음 (예전엔 서로 가장 가까운 짝만 묶어 하나가 따로 떴음) */
    @Test
    fun crowdedSpot_pairsNearestFirst() {
        val t9 = BusStop("TS305", "TSB305000009", "전주교도소", "3050009", 35.779292, 127.129298)
        val t10 = BusStop("TS305", "TSB305000010", "전주교도소", "3050010", 35.779309, 127.129207)
        val j279 = BusStop("35010", "JUB305100279", "전주교도소", "30279", 35.779327, 127.129321)
        val j280 = BusStop("35010", "JUB305100280", "전주교도소", "30280", 35.779692, 127.129315) // 약 44m: 이름이 같아 묶음
        val g = StopMerge.merge(listOf(t9, t10, j279, j280), preferCity = "35010").map { it.all.map { s -> s.nodeNo }.toSet() }.toSet()
        assertEquals(setOf(setOf("30279", "3050009"), setOf("30280", "3050010")), g)

        val a = BusStop("35010", "JUB311100210", "하천마을", "30210", 35.766702, 127.003897)
        val b = BusStop("35060", "TSB311000958", "하천마을", "3110958", 35.766709, 127.00385)
        val c = BusStop("35010", "JUB311100209", "하천마을", "30209", 35.766728, 127.003814)
        val d = BusStop("35060", "TSB311001034", "하천마을", "3111034", 35.766732, 127.003979)
        assertEquals(2, StopMerge.merge(listOf(a, b, c, d)).size)
        assertTrue(StopMerge.merge(listOf(a, b, c, d)).all { it.others.size == 1 })
    }

    /** 이름이 다르면 30m 까지만, 같으면 60m 까지 */
    @Test
    fun sameNameReachesFurther() {
        val x = BusStop("35010", "J1", "신흥리", "1", 35.8000, 127.1000)
        val y = BusStop("TS312", "T1", "신흥리", "2", 35.8004, 127.1000)   // 약 44m
        val z = BusStop("TS312", "T2", "다른이름", "3", 35.8004, 127.1000)
        assertEquals(1, StopMerge.merge(listOf(x, y)).size)
        assertEquals(2, StopMerge.merge(listOf(x, z)).size)
        assertEquals(listOf("T1"), StopMerge.companions(x, listOf(y, z)).map { it.nodeId })
    }
}
